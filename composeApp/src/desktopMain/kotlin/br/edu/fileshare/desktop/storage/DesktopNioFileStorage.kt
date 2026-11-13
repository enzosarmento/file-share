package br.edu.fileshare.desktop.storage

import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.storage.FileNameSanitizer
import br.edu.fileshare.storage.FileReadSession
import br.edu.fileshare.storage.FileSink
import br.edu.fileshare.storage.FileSource
import br.edu.fileshare.storage.StagedFileSession
import br.edu.fileshare.storage.StoredFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Leitor de arquivo local em streaming via `java.nio.file` (`TASK-DCLI-004`).
 *
 * Nao carrega o arquivo inteiro em memoria RAM e nao importa bibliotecas graficas (`AWT`/`Swing`/`Compose`).
 */
class DesktopNioFileSource(
    val path: Path,
    fileNameOverride: String? = null,
    mimeTypeOverride: String? = null,
) : FileSource {

    private val normalizedPath: Path = path.toAbsolutePath().normalize()

    init {
        require(Files.exists(normalizedPath) && Files.isRegularFile(normalizedPath)) {
            "Arquivo de origem inexistente ou invalido: $normalizedPath"
        }
        require(Files.isReadable(normalizedPath)) {
            "Arquivo de origem sem permissao de leitura: $normalizedPath"
        }
    }

    override val fileName: String = FileNameSanitizer.requireValid(
        fileNameOverride ?: normalizedPath.fileName?.toString().orEmpty(),
    )

    override val sizeBytes: Long = Files.size(normalizedPath)

    override val mimeType: String = mimeTypeOverride?.takeIf { it.isNotBlank() }
        ?: runCatching { Files.probeContentType(normalizedPath) }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: DEFAULT_MIME_TYPE

    override suspend fun openReadSession(): FileReadSession = withContext(Dispatchers.IO) {
        val channel = FileChannel.open(normalizedPath, StandardOpenOption.READ)
        NioFileReadSession(channel)
    }

    private class NioFileReadSession(
        private val channel: FileChannel,
    ) : FileReadSession {
        override suspend fun readChunk(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
            if (buffer.isEmpty()) return@withContext 0
            val byteBuffer = ByteBuffer.wrap(buffer)
            channel.read(byteBuffer)
        }

        override suspend fun close() {
            withContext(Dispatchers.IO) {
                if (channel.isOpen) {
                    channel.close()
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
    }
}

/**
 * Gravador seguro de arquivos em disco via `java.nio.file` (`TASK-DCLI-004`).
 *
 * - Grava inicialmente em `<targetDir>/.fsap-staging/<transferId>_<fileId>.part`
 * - Sanitiza nomes contra Path Traversal e garante contencao no diretorio alvo canonico
 * - Promove atomicamente para o arquivo final sem sobrescrita (`documento (1).pdf`, ...)
 */
class DesktopNioFileSink(
    targetDirectory: Path,
) : FileSink {

    val targetDirectory: Path = targetDirectory.toAbsolutePath().normalize()
    val stagingDirectory: Path = this.targetDirectory.resolve(STAGING_DIR_NAME).normalize()

    private val commitMutex = Mutex()
    private val canonicalTargetDir: Path by lazy {
        ensureDirectoriesExist()
        this.targetDirectory.toRealPath()
    }

    override suspend fun availableStorageBytes(): Long = withContext(Dispatchers.IO) {
        ensureDirectoriesExist()
        Files.getFileStore(this@DesktopNioFileSink.targetDirectory).usableSpace
    }

    override suspend fun openPartSession(
        transferId: String,
        fileId: String,
        requestedFileName: String,
        expectedSizeBytes: Long,
    ): StagedFileSession = withContext(Dispatchers.IO) {
        val sanitizedName = FileNameSanitizer.requireValid(requestedFileName)
        val safeTransferToken = sanitizeIdentifier(transferId, "transferId")
        val safeFileToken = sanitizeIdentifier(fileId, "fileId")

        ensureDirectoriesExist()
        val usable = Files.getFileStore(targetDirectory).usableSpace
        if (expectedSizeBytes > 0L && usable < expectedSizeBytes) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.INSUFFICIENT_STORAGE,
                message = "Espaco insuficiente em disco ($usable B livre, $expectedSizeBytes B necessarios).",
                transferId = transferId,
                fileId = fileId,
            )
        }

        val partId = "${safeTransferToken}_${safeFileToken}$PART_EXTENSION"
        val partPath = resolveContainedStagingPath(partId)
        val channel = FileChannel.open(
            partPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        NioStagedFileSession(
            transferId = transferId,
            fileId = fileId,
            sanitizedFileName = sanitizedName,
            partIdentifier = partId,
            partPath = partPath,
            channel = channel,
        )
    }

    override suspend fun cleanupTransferParts(transferId: String): Int = withContext(Dispatchers.IO) {
        if (!Files.isDirectory(stagingDirectory)) return@withContext 0
        val safeTransferToken = sanitizeIdentifier(transferId, "transferId")
        val prefix = "${safeTransferToken}_"
        var deletedCount = 0
        Files.newDirectoryStream(stagingDirectory).use { stream ->
            for (entry in stream) {
                val name = entry.fileName?.toString().orEmpty()
                if (name.startsWith(prefix) && name.endsWith(PART_EXTENSION)) {
                    if (Files.deleteIfExists(entry)) {
                        deletedCount++
                    }
                }
            }
        }
        deletedCount
    }

    private fun ensureDirectoriesExist() {
        Files.createDirectories(targetDirectory)
        Files.createDirectories(stagingDirectory)
    }

    private fun resolveContainedStagingPath(partFileName: String): Path {
        val canonicalStaging = stagingDirectory.toRealPath()
        val candidate = canonicalStaging.resolve(partFileName).normalize()
        if (candidate.parent != canonicalStaging || !candidate.startsWith(canonicalStaging)) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.INVALID_FILENAME,
                message = "Caminho de staging fora do diretorio autorizado.",
            )
        }
        return candidate
    }

    private fun resolveContainedTargetPath(finalName: String): Path {
        val sanitized = FileNameSanitizer.requireValid(finalName)
        val candidate = try {
            canonicalTargetDir.resolve(sanitized).normalize()
        } catch (ex: InvalidPathException) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.INVALID_FILENAME,
                message = "Nome de arquivo invalido para o sistema de arquivos.",
                cause = ex,
            )
        }
        if (candidate.parent != canonicalTargetDir || !candidate.startsWith(canonicalTargetDir)) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.INVALID_FILENAME,
                message = "Tentativa de escape do diretorio de destino detectada.",
            )
        }
        return candidate
    }

    private inner class NioStagedFileSession(
        override val transferId: String,
        override val fileId: String,
        override val sanitizedFileName: String,
        override val partIdentifier: String,
        private val partPath: Path,
        private val channel: FileChannel,
    ) : StagedFileSession {

        private var bytesWritten: Long = 0L
        private var finished: Boolean = false

        override suspend fun writeChunk(buffer: ByteArray, offset: Int, length: Int) {
            check(!finished) { "Sessao de gravacao ja finalizada para $transferId/$fileId." }
            if (length == 0) return
            withContext(Dispatchers.IO) {
                val slice = ByteBuffer.wrap(buffer, offset, length)
                while (slice.hasRemaining()) {
                    channel.write(slice)
                }
                bytesWritten += length
            }
        }

        override suspend fun flushAndSync() {
            withContext(Dispatchers.IO) {
                if (channel.isOpen) {
                    channel.force(true)
                    channel.close()
                }
            }
        }

        override suspend fun commitPart(
            verifiedSha256: String,
            totalBytesWritten: Long,
        ): StoredFileDescriptor = commitMutex.withLock {
            check(!finished) { "Sessao ja finalizada para $transferId/$fileId." }
            withContext(Dispatchers.IO) {
                if (channel.isOpen) {
                    channel.force(true)
                    channel.close()
                }
                val resolvedName = FileNameSanitizer.resolveNonOverwritingName(sanitizedFileName) { candidate ->
                    val candidatePath = resolveContainedTargetPath(candidate)
                    Files.exists(candidatePath)
                }
                val finalPath = resolveContainedTargetPath(resolvedName)
                moveAtomicallyWithoutOverwrite(partPath, finalPath)
                finished = true
                StoredFileDescriptor(
                    finalName = resolvedName,
                    locationUri = finalPath.toUri().toString(),
                    sizeBytes = totalBytesWritten,
                    sha256 = verifiedSha256,
                )
            }
        }

        override suspend fun discardPart() {
            if (finished) return
            finished = true
            withContext(Dispatchers.IO) {
                runCatching {
                    if (channel.isOpen) {
                        channel.close()
                    }
                }
                runCatching {
                    Files.deleteIfExists(partPath)
                }
            }
        }
    }

    private fun moveAtomicallyWithoutOverwrite(source: Path, target: Path) {
        try {
            if (Files.exists(target)) {
                throw FileAlreadyExistsException(target.toString())
            }
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            if (Files.exists(target)) {
                throw FileAlreadyExistsException(target.toString())
            }
            Files.move(source, target)
        } catch (io: IOException) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.INTERNAL_ERROR,
                message = "Falha ao promover arquivo temporario para destino final: ${io.message}",
                cause = io,
            )
        }
    }

    private companion object {
        const val STAGING_DIR_NAME = ".fsap-staging"
        const val PART_EXTENSION = ".part"

        fun sanitizeIdentifier(raw: String, field: String): String {
            val cleaned = buildString(raw.length) {
                for (ch in raw) {
                    if (ch.isLetterOrDigit() || ch == '-' || ch == '_') {
                        append(ch)
                    } else {
                        append('_')
                    }
                }
            }
            require(cleaned.isNotBlank()) { "Identificador $field invalido para staging." }
            return cleaned
        }
    }
}
