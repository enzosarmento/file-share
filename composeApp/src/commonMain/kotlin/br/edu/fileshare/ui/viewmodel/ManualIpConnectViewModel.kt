package br.edu.fileshare.ui.viewmodel

import br.edu.fileshare.network.discovery.DiscoveredPeer
import br.edu.fileshare.network.discovery.ManualConnectResult
import br.edu.fileshare.protocol.FsapConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Porta desacoplada para conexao manual por IP/Porta na UI (`TASK-DCLI-003`).
 */
fun interface ManualConnectPort {
    suspend fun connectByManualIp(ipAddress: String, port: Int): ManualConnectResult
}

data class ManualIpUiState(
    val isDialogOpen: Boolean = false,
    val ipInput: String = "",
    val portInput: String = FsapConstants.DEFAULT_HTTP_PORT.toString(),
    val ipError: String? = null,
    val portError: String? = null,
    val connectionError: String? = null,
    val isConnecting: Boolean = false,
)

/**
 * ViewModel do formulario modal de conexao manual por IP (`TASK-DCLI-003`).
 */
class ManualIpConnectViewModel(
    private val connectPort: ManualConnectPort,
    private val scope: CoroutineScope,
    private val onConnected: (DiscoveredPeer) -> Unit = {},
) {
    private val _uiState = MutableStateFlow(ManualIpUiState())
    val uiState: StateFlow<ManualIpUiState> = _uiState.asStateFlow()

    fun openDialog() {
        _uiState.update {
            it.copy(
                isDialogOpen = true,
                ipError = null,
                portError = null,
                connectionError = null,
            )
        }
    }

    fun closeDialog() {
        if (_uiState.value.isConnecting) return
        _uiState.update {
            it.copy(
                isDialogOpen = false,
                ipError = null,
                portError = null,
                connectionError = null,
            )
        }
    }

    fun onIpInputChange(raw: String) {
        _uiState.update {
            it.copy(
                ipInput = raw,
                ipError = null,
                connectionError = null,
            )
        }
    }

    fun onPortInputChange(raw: String) {
        _uiState.update {
            it.copy(
                portInput = raw,
                portError = null,
                connectionError = null,
            )
        }
    }

    fun submit() {
        scope.launch { submitInternal() }
    }

    internal suspend fun submitInternal(): Boolean {
        val current = _uiState.value
        if (current.isConnecting) return false

        val cleanHost = current.ipInput.trim()
        val ipValidationError = validateHostOrIp(cleanHost)
        val parsedPort = current.portInput.trim().toIntOrNull()
        val portValidationError = if (
            parsedPort == null || parsedPort !in FsapConstants.MIN_VALID_PORT..FsapConstants.MAX_VALID_PORT
        ) {
            "Porta invalida. Informe valor entre ${FsapConstants.MIN_VALID_PORT} e ${FsapConstants.MAX_VALID_PORT}."
        } else {
            null
        }

        if (ipValidationError != null || portValidationError != null) {
            _uiState.update {
                it.copy(
                    ipError = ipValidationError,
                    portError = portValidationError,
                )
            }
            return false
        }

        val validPort = parsedPort!!
        _uiState.update { it.copy(isConnecting = true, connectionError = null) }

        return try {
            when (val result = connectPort.connectByManualIp(cleanHost, validPort)) {
                is ManualConnectResult.Connected -> {
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            isDialogOpen = false,
                            connectionError = null,
                        )
                    }
                    onConnected(result.peer)
                    true
                }

                is ManualConnectResult.Failed -> {
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            connectionError = "[${result.errorCode.code}] ${result.message}",
                        )
                    }
                    false
                }
            }
        } catch (ex: Throwable) {
            _uiState.update {
                it.copy(
                    isConnecting = false,
                    connectionError = ex.message ?: "Erro inesperado ao conectar ao dispositivo.",
                )
            }
            false
        }
    }

    companion object {
        private val HOSTNAME_LABEL_REGEX = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

        fun validateHostOrIp(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) {
                return "Informe o endereco IPv4 ou hostname do dispositivo."
            }
            if (trimmed.any { it.isWhitespace() || it == '/' || it == ':' }) {
                return "Endereco IP ou hostname contem caracteres invalidos."
            }
            val segments = trimmed.split('.')
            val looksNumericIpv4 = segments.all { seg -> seg.isNotEmpty() && seg.all { it.isDigit() } }
            if (looksNumericIpv4) {
                if (segments.size != 4) {
                    return "Endereco IPv4 incompleto. Use o formato A.B.C.D."
                }
                val invalidOctet = segments.any { seg ->
                    val n = seg.toIntOrNull()
                    n == null || n !in 0..255
                }
                if (invalidOctet) {
                    return "Octeto IPv4 fora do intervalo permitido (0..255)."
                }
                return null
            }

            if (trimmed.startsWith('.') || trimmed.endsWith('.') || trimmed.contains("..")) {
                return "Endereco de rede malformado."
            }
            if (segments.any { !HOSTNAME_LABEL_REGEX.matches(it) }) {
                return "Hostname ou endereco IP invalido."
            }
            return null
        }
    }
}

