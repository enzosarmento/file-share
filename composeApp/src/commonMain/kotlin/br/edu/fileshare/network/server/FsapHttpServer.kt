package br.edu.fileshare.network.server

import br.edu.fileshare.core.concurrency.PlatformLock
import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.id.UuidGenerator
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.network.server.routes.FsapTransferServerServices
import br.edu.fileshare.network.server.routes.installTransferContentRoute
import br.edu.fileshare.network.server.routes.installTransferPrepareRoute
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapErrorFactory
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.FsapEnvelope
import br.edu.fileshare.protocol.model.FsapEnvelopeFactory
import br.edu.fileshare.protocol.model.FsapHeaders
import br.edu.fileshare.protocol.model.FsapJson
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.IdentifyPayload
import br.edu.fileshare.protocol.validation.FsapMessageValidator
import br.edu.fileshare.protocol.validation.ValidationResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlin.concurrent.Volatile

/**
 * Configuracao do servidor HTTP/TCP FSAP/1.0 independente de interface grafica.
 */
data class FsapHttpServerConfig(
    val identity: DeviceIdentity,
    val host: String = "0.0.0.0",
    val port: Int = identity.httpPort,
    val maxJsonBodyBytes: Int = FsapConstants.MAX_JSON_BODY_BYTES,
)

/**
 * Configura plugins padrao (negociacao JSON, tratamento global de erros FSAP) e rotas de identificacao
 * em qualquer [Application] Ktor, permitindo reutilizacao identica em `CIO` e `testApplication`.
 */
fun Application.configureFsapServer(
    config: FsapHttpServerConfig,
    logger: FsapLogger,
    clock: Clock = SystemClock,
    uuidGenerator: UuidGenerator = PlatformUuidGenerator,
    effectivePortProvider: () -> Int = { config.port },
    transferServices: FsapTransferServerServices? = null,
    extraRoutes: Routing.() -> Unit = {},
) {
    install(ContentNegotiation) {
        json(FsapJson.codec)
    }

    install(createFsapErrorHandlingPlugin(config.identity.deviceId, logger, clock, uuidGenerator))

    routing {
        installIdentityRoutes(
            config = config,
            logger = logger,
            clock = clock,
            uuidGenerator = uuidGenerator,
            effectivePortProvider = effectivePortProvider,
        )
        transferServices?.let { services ->
            installTransferPrepareRoute(
                localIdentity = config.identity,
                services = services,
                logger = logger,
                clock = clock,
                uuidGenerator = uuidGenerator,
            )
            installTransferContentRoute(
                localIdentity = config.identity,
                services = services,
                logger = logger,
                clock = clock,
                uuidGenerator = uuidGenerator,
            )
        }
        extraRoutes()
    }
}

private fun createFsapErrorHandlingPlugin(
    localDeviceId: String,
    logger: FsapLogger,
    clock: Clock,
    uuidGenerator: UuidGenerator,
) = createApplicationPlugin("FsapErrorHandlingPlugin") {
    on(CallFailed) { call, cause ->
        if (cause is CancellationException) throw cause
        if (call.response.isSent) return@on

        val protocolError = mapThrowableToFsapException(cause, logger, localDeviceId)
        val envelope = FsapErrorFactory.toEnvelope(
            senderId = localDeviceId,
            errorCode = protocolError.errorCode,
            message = protocolError.message,
            transferId = protocolError.transferId,
            fileId = protocolError.fileId,
            recoverable = protocolError.recoverable,
            uuidGenerator = uuidGenerator,
            clock = clock,
        )
        call.respondFsapEnvelope(
            envelope = envelope,
            status = HttpStatusCode.fromValue(protocolError.errorCode.httpStatus),
        )
    }
}

private fun mapThrowableToFsapException(
    cause: Throwable,
    logger: FsapLogger,
    localDeviceId: String,
): FsapProtocolException = when (cause) {
    is FsapProtocolException -> {
        logger.warn(
            component = "FsapHttpServer",
            message = "Requisicao rejeitada com ${cause.errorCode.code}: ${cause.message}",
            context = LogContext(deviceId = localDeviceId, transferId = cause.transferId),
        )
        cause
    }

    is SerializationException,
    is ContentTransformationException,
    is BadRequestException,
    is IllegalArgumentException,
    -> {
        logger.warn(
            component = "FsapHttpServer",
            message = "Corpo/parametro de requisicao invalido: ${cause.message}",
            context = LogContext(deviceId = localDeviceId),
        )
        FsapProtocolException(
            errorCode = FsapErrorCode.MALFORMED_MESSAGE,
            message = cause.message ?: FsapErrorCode.MALFORMED_MESSAGE.defaultMessage,
            cause = cause,
        )
    }

    else -> {
        logger.error(
            component = "FsapHttpServer",
            message = "Erro interno inesperado ao processar requisicao HTTP FSAP.",
            context = LogContext(deviceId = localDeviceId),
            throwable = cause,
        )
        FsapProtocolException(
            errorCode = FsapErrorCode.INTERNAL_ERROR,
            message = FsapErrorCode.INTERNAL_ERROR.defaultMessage,
            cause = cause,
        )
    }
}

private fun Routing.installIdentityRoutes(
    config: FsapHttpServerConfig,
    logger: FsapLogger,
    clock: Clock,
    uuidGenerator: UuidGenerator,
    effectivePortProvider: () -> Int,
) {
    get(FsapConstants.Routes.INFO) {
        call.validateOptionalFsapVersionHeader()
        val responseEnvelope = buildIdentifyEnvelope(
            identity = config.identity,
            effectivePort = effectivePortProvider(),
            clock = clock,
            uuidGenerator = uuidGenerator,
        )
        call.respondFsapEnvelope(responseEnvelope)
    }

    post(FsapConstants.Routes.IDENTIFY) {
        call.validateOptionalFsapVersionHeader()
        val requestEnvelope = call.receiveBoundedJsonEnvelope<IdentifyPayload>(
            maxJsonBodyBytes = config.maxJsonBodyBytes,
        )
        val validation = FsapMessageValidator.validateIdentify(requestEnvelope)
        if (validation is ValidationResult.Invalid) {
            throw FsapProtocolException(validation.errorCode, validation.reason)
        }

        logger.info(
            component = "FsapHttpServer",
            message = "Handshake IDENTIFY recebido de '${requestEnvelope.payload.alias}' (porta ${requestEnvelope.payload.httpPort}).",
            context = LogContext(
                deviceId = config.identity.deviceId,
                peerId = requestEnvelope.senderId,
            ),
        )

        val responseEnvelope = buildIdentifyEnvelope(
            identity = config.identity,
            effectivePort = effectivePortProvider(),
            clock = clock,
            uuidGenerator = uuidGenerator,
        )
        call.respondFsapEnvelope(responseEnvelope)
    }
}

private fun buildIdentifyEnvelope(
    identity: DeviceIdentity,
    effectivePort: Int,
    clock: Clock,
    uuidGenerator: UuidGenerator,
): FsapEnvelope<IdentifyPayload> {
    val advertisedPort = if (effectivePort > 0) effectivePort else identity.httpPort
    return FsapEnvelopeFactory.create(
        type = FsapMessageType.IDENTIFY,
        senderId = identity.deviceId,
        payload = IdentifyPayload(
            alias = identity.alias,
            deviceType = identity.deviceType,
            platform = identity.platform,
            httpPort = advertisedPort,
            capabilities = identity.capabilities,
        ),
        uuidGenerator = uuidGenerator,
        clock = clock,
    )
}

internal fun ApplicationCall.validateOptionalFsapVersionHeader() {
    val headerVersion = request.headers[FsapHeaders.VERSION] ?: return
    val result = FsapMessageValidator.validateProtocolVersion(headerVersion)
    if (result is ValidationResult.Invalid) {
        throw FsapProtocolException(result.errorCode, result.reason)
    }
}

internal suspend inline fun <reified T> ApplicationCall.receiveBoundedJsonEnvelope(
    maxJsonBodyBytes: Int = FsapConstants.MAX_JSON_BODY_BYTES,
): FsapEnvelope<T> {
    val contentLength = request.headers["Content-Length"]?.toLongOrNull()
    if (contentLength != null && contentLength > maxJsonBodyBytes) {
        throw FsapProtocolException(
            errorCode = FsapErrorCode.FILE_TOO_LARGE,
            message = "Corpo JSON ($contentLength bytes) excede o limite de $maxJsonBodyBytes bytes.",
        )
    }

    val rawJson = readUtf8Bounded(receiveChannel(), maxJsonBodyBytes)
    if (rawJson.isBlank()) {
        throw FsapProtocolException(
            errorCode = FsapErrorCode.MALFORMED_MESSAGE,
            message = "Corpo JSON da requisicao nao pode estar vazio.",
        )
    }

    return try {
        FsapJson.decodeEnvelope(rawJson)
    } catch (ex: SerializationException) {
        throw FsapProtocolException(
            errorCode = FsapErrorCode.MALFORMED_MESSAGE,
            message = "Falha ao desserializar envelope JSON FSAP: ${ex.message}",
            cause = ex,
        )
    }
}

private suspend fun readUtf8Bounded(
    channel: ByteReadChannel,
    maxBytes: Int,
): String {
    val chunks = mutableListOf<ByteArray>()
    val buffer = ByteArray(4096)
    var totalBytes = 0

    while (!channel.isClosedForRead) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read == 0) continue

        if (totalBytes + read > maxBytes) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.FILE_TOO_LARGE,
                message = "Corpo JSON excede o limite maximo de $maxBytes bytes.",
            )
        }
        chunks += buffer.copyOf(read)
        totalBytes += read
    }

    if (totalBytes == 0) return ""
    val combined = ByteArray(totalBytes)
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(combined, destinationOffset = offset)
        offset += chunk.size
    }
    return combined.decodeToString()
}

internal suspend inline fun <reified T> ApplicationCall.respondFsapEnvelope(
    envelope: FsapEnvelope<T>,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    response.header(FsapHeaders.PROTOCOL, FsapConstants.PROTOCOL_NAME)
    response.header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
    respondText(
        text = FsapJson.encodeEnvelope(envelope),
        contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
        status = status,
    )
}

/**
 * Motor HTTP/1.1 embutido (`CIO`) do no FileShare para comunicacao de controle e transferencia de arquivos.
 */
class FsapHttpServer(
    private val config: FsapHttpServerConfig,
    private val logger: FsapLogger,
    private val clock: Clock = SystemClock,
    private val uuidGenerator: UuidGenerator = PlatformUuidGenerator,
    private val transferServices: FsapTransferServerServices? = null,
    private val extraRoutes: Routing.() -> Unit = {},
) {
    private val lifecycleLock = PlatformLock()

    @Volatile
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    private var boundPort: Int = config.port

    suspend fun start(): Int {
        val createdEngine = lifecycleLock.withLock {
            check(engine == null) { "FsapHttpServer ja esta em execucao na porta $boundPort." }
            embeddedServer(
                factory = CIO,
                port = config.port,
                host = config.host,
            ) {
                configureFsapServer(
                    config = config,
                    logger = logger,
                    clock = clock,
                    uuidGenerator = uuidGenerator,
                    effectivePortProvider = { resolvedPort() },
                    transferServices = transferServices,
                    extraRoutes = extraRoutes,
                )
            }.also { engine = it }
        }

        createdEngine.start(wait = false)
        val actualPort = createdEngine.engine.resolvedConnectors().firstOrNull()?.port ?: config.port
        boundPort = actualPort
        logger.info(
            component = "FsapHttpServer",
            message = "Servidor HTTP/TCP FSAP/1.0 ativo em ${config.host}:$actualPort (alias='${config.identity.alias}').",
            context = LogContext(deviceId = config.identity.deviceId),
        )
        return actualPort
    }

    fun resolvedPort(): Int = boundPort

    fun isRunning(): Boolean = engine != null

    fun stop(
        gracePeriodMillis: Long = FsapConstants.T_SHUTDOWN_GRACE_MS,
        timeoutMillis: Long = FsapConstants.T_SHUTDOWN_GRACE_MS,
    ) {
        val activeEngine = lifecycleLock.withLock {
            val current = engine
            engine = null
            current
        } ?: return

        logger.info(
            component = "FsapHttpServer",
            message = "Encerrando servidor HTTP/TCP FSAP/1.0 (porta $boundPort)...",
            context = LogContext(deviceId = config.identity.deviceId),
        )
        activeEngine.stop(gracePeriodMillis, timeoutMillis)
        boundPort = config.port
    }
}

