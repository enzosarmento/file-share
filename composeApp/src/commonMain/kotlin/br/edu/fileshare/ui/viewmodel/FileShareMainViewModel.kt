package br.edu.fileshare.ui.viewmodel

import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.network.discovery.DiscoveredPeer
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.statemachine.LocalNodeEvent
import br.edu.fileshare.protocol.statemachine.LocalNodeState
import br.edu.fileshare.protocol.statemachine.LocalNodeStateMachine
import br.edu.fileshare.storage.FileSource
import br.edu.fileshare.storage.StoredFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MainTab(val title: String) {
    SEND("Enviar"),
    RECEIVED("Recebidos"),
    TRANSFERS("Transferencias"),
    SETTINGS("Configuracoes"),
}

/**
 * Porta multiplataforma de runtime consumida pela camada de UI sem acoplamento com APIs nativas (`TASK-DCLI-001`).
 */
interface FileShareRuntimePort {
    val localDeviceId: String
    val initialAlias: String
    val initialPort: Int
    val saveDirectoryLabel: String
    val peers: StateFlow<List<DiscoveredPeer>>
    val receivedFiles: StateFlow<List<StoredFileDescriptor>>
    val localAddresses: StateFlow<List<String>>

    suspend fun startNode(alias: String, port: Int): Result<Int>
    suspend fun stopNode()
    suspend fun pickFilesToSend(): List<FileSource>
    suspend fun sendFiles(peer: DiscoveredPeer, sources: List<FileSource>): Result<String>
    suspend fun refreshDiscovery(): Result<Unit> = Result.success(Unit)
}

data class FileShareMainUiState(
    val localDeviceId: String = "",
    val localAlias: String = "",
    val boundHttpPort: Int = FsapConstants.DEFAULT_HTTP_PORT,
    val saveDirectoryLabel: String = "",
    val localAddresses: List<String> = emptyList(),
    val nodeState: LocalNodeState = LocalNodeState.STOPPED,
    val activeTab: MainTab = MainTab.SEND,
    val selectedFiles: List<FileSource> = emptyList(),
    val peers: List<DiscoveredPeer> = emptyList(),
    val selectedPeerDeviceId: String? = null,
    val receivedFiles: List<StoredFileDescriptor> = emptyList(),
    val aliasDraft: String = "",
    val portDraft: String = FsapConstants.DEFAULT_HTTP_PORT.toString(),
    val settingsError: String? = null,
    val statusBanner: String? = null,
    val isBusy: Boolean = false,
) {
    val totalSelectedBytes: Long
        get() = selectedFiles.sumOf { it.sizeBytes.coerceAtLeast(0L) }

    val selectedPeer: DiscoveredPeer?
        get() = peers.firstOrNull { it.identity.deviceId == selectedPeerDeviceId }

    val canSendFiles: Boolean
        get() = selectedFiles.isNotEmpty() && selectedPeer != null && nodeState != LocalNodeState.FAULTED && !isBusy
}

/**
 * ViewModel principal em Unidirectional Data Flow (UDF) compartilhada entre Desktop e Android (`TASK-DCLI-001`).
 */
class FileShareMainViewModel(
    private val runtime: FileShareRuntimePort,
    private val scope: CoroutineScope,
    logger: FsapLogger? = null,
) {
    private val nodeFsm = LocalNodeStateMachine(
        nodeId = runtime.localDeviceId,
        logger = logger,
    )

    private val _uiState = MutableStateFlow(
        FileShareMainUiState(
            localDeviceId = runtime.localDeviceId,
            localAlias = runtime.initialAlias,
            boundHttpPort = runtime.initialPort,
            saveDirectoryLabel = runtime.saveDirectoryLabel,
            localAddresses = runtime.localAddresses.value,
            nodeState = nodeFsm.currentState(),
            aliasDraft = runtime.initialAlias,
            portDraft = runtime.initialPort.toString(),
            peers = runtime.peers.value,
            receivedFiles = runtime.receivedFiles.value,
        ),
    )
    val uiState: StateFlow<FileShareMainUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            runtime.peers.collectLatest { discovered ->
                _uiState.update { current ->
                    val preservedSelection = current.selectedPeerDeviceId
                        ?.takeIf { selected -> discovered.any { it.identity.deviceId == selected } }
                        ?: discovered.singleOrNull()?.identity?.deviceId
                    current.copy(
                        peers = discovered,
                        selectedPeerDeviceId = preservedSelection,
                    )
                }
            }
        }

        scope.launch {
            runtime.receivedFiles.collectLatest { files ->
                _uiState.update { it.copy(receivedFiles = files) }
            }
        }

        scope.launch {
            runtime.localAddresses.collectLatest { ips ->
                _uiState.update { it.copy(localAddresses = ips) }
            }
        }
    }

    fun startNode() {
        scope.launch { startNodeInternal() }
    }

    internal suspend fun startNodeInternal() {
        val currentState = nodeFsm.currentState()
        val alias = _uiState.value.localAlias
        val port = _uiState.value.boundHttpPort

        val startedEvent = when (currentState) {
            LocalNodeState.STOPPED -> LocalNodeEvent.CMD_START_NODE
            LocalNodeState.FAULTED -> LocalNodeEvent.CMD_RETRY_START
            LocalNodeState.AVAILABLE,
            LocalNodeState.DISCOVERING,
            -> return
            LocalNodeState.INITIALIZING,
            LocalNodeState.SHUTTING_DOWN,
            -> return
        }

        nodeFsm.transition(startedEvent)
        _uiState.update {
            it.copy(
                nodeState = nodeFsm.currentState(),
                statusBanner = null,
            )
        }

        val result = runtime.startNode(alias = alias, port = port)
        result.fold(
            onSuccess = { boundPort ->
                nodeFsm.transition(LocalNodeEvent.EVT_PORTS_BOUND)
                nodeFsm.transition(LocalNodeEvent.EVT_INITIAL_ANNOUNCE_SENT)
                _uiState.update {
                    it.copy(
                        nodeState = nodeFsm.currentState(),
                        boundHttpPort = boundPort,
                        portDraft = boundPort.toString(),
                        statusBanner = null,
                    )
                }
            },
            onFailure = { error ->
                nodeFsm.transition(LocalNodeEvent.EVT_BIND_FAILED)
                _uiState.update {
                    it.copy(
                        nodeState = nodeFsm.currentState(),
                        statusBanner = "Falha ao iniciar servico de rede: ${error.message ?: "erro desconhecido"}",
                    )
                }
            },
        )
    }

    fun stopNode() {
        scope.launch { stopNodeInternal() }
    }

    internal suspend fun stopNodeInternal() {
        val currentState = nodeFsm.currentState()
        if (currentState == LocalNodeState.STOPPED || currentState == LocalNodeState.SHUTTING_DOWN) {
            return
        }
        nodeFsm.transition(LocalNodeEvent.CMD_STOP_NODE)
        _uiState.update { it.copy(nodeState = nodeFsm.currentState()) }

        runCatching { runtime.stopNode() }

        nodeFsm.transition(LocalNodeEvent.EVT_CLEANUP_COMPLETED)
        _uiState.update { it.copy(nodeState = nodeFsm.currentState()) }
    }

    fun selectTab(tab: MainTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun pickFiles() {
        scope.launch { pickFilesInternal() }
    }

    internal suspend fun pickFilesInternal() {
        _uiState.update { it.copy(isBusy = true, statusBanner = null) }
        try {
            val picked = runtime.pickFilesToSend()
            if (picked.isNotEmpty()) {
                _uiState.update { state ->
                    val merged = (state.selectedFiles + picked).distinctBy { "${it.fileName}#${it.sizeBytes}" }
                    state.copy(selectedFiles = merged)
                }
            }
        } catch (ex: Throwable) {
            _uiState.update {
                it.copy(statusBanner = "Erro ao selecionar arquivos: ${ex.message ?: "falha de I/O"}")
            }
        } finally {
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    fun addSources(sources: List<FileSource>) {
        if (sources.isEmpty()) return
        _uiState.update { state ->
            val merged = (state.selectedFiles + sources).distinctBy { "${it.fileName}#${it.sizeBytes}" }
            state.copy(selectedFiles = merged)
        }
    }

    fun removeSelectedFileAt(index: Int) {
        _uiState.update { state ->
            if (index !in state.selectedFiles.indices) return@update state
            val updated = state.selectedFiles.toMutableList().apply { removeAt(index) }
            state.copy(selectedFiles = updated)
        }
    }

    fun clearSelectedFiles() {
        _uiState.update { it.copy(selectedFiles = emptyList()) }
    }

    fun selectPeer(deviceId: String?) {
        _uiState.update { it.copy(selectedPeerDeviceId = deviceId) }
    }

    fun sendSelectedFiles() {
        scope.launch { sendSelectedFilesInternal() }
    }

    internal suspend fun sendSelectedFilesInternal() {
        val snapshot = _uiState.value
        val targetPeer = snapshot.selectedPeer
        if (targetPeer == null) {
            _uiState.update { it.copy(statusBanner = "Selecione ou conecte um dispositivo destinatario.") }
            return
        }
        if (snapshot.selectedFiles.isEmpty()) {
            _uiState.update { it.copy(statusBanner = "Selecione ao menos um arquivo antes de enviar.") }
            return
        }

        _uiState.update { it.copy(isBusy = true, statusBanner = null) }
        try {
            val result = runtime.sendFiles(targetPeer, snapshot.selectedFiles)
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            selectedFiles = emptyList(),
                            activeTab = MainTab.TRANSFERS,
                            statusBanner = "Envio iniciado para ${targetPeer.identity.alias}.",
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(statusBanner = "Falha ao iniciar envio: ${error.message ?: "erro desconhecido"}")
                    }
                },
            )
        } finally {
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    fun refreshPeers() {
        scope.launch {
            if (nodeFsm.currentState() == LocalNodeState.AVAILABLE) {
                nodeFsm.transition(LocalNodeEvent.CMD_REFRESH_DISCOVERY)
                _uiState.update { it.copy(nodeState = nodeFsm.currentState()) }
            }
            runtime.refreshDiscovery()
            if (nodeFsm.currentState() == LocalNodeState.DISCOVERING) {
                nodeFsm.transition(LocalNodeEvent.EVT_INITIAL_ANNOUNCE_SENT)
                _uiState.update { it.copy(nodeState = nodeFsm.currentState()) }
            }
        }
    }

    fun updateSettingsDraft(alias: String, portText: String) {
        _uiState.update {
            it.copy(
                aliasDraft = alias,
                portDraft = portText,
                settingsError = null,
            )
        }
    }

    fun applySettingsAndRestart() {
        scope.launch { applySettingsAndRestartInternal() }
    }

    internal suspend fun applySettingsAndRestartInternal() {
        val cleanAlias = _uiState.value.aliasDraft.trim()
        if (cleanAlias.isEmpty() || cleanAlias.length > FsapConstants.MAX_ALIAS_LENGTH) {
            _uiState.update {
                it.copy(settingsError = "Alias deve ter entre 1 e ${FsapConstants.MAX_ALIAS_LENGTH} caracteres.")
            }
            return
        }

        val parsedPort = _uiState.value.portDraft.trim().toIntOrNull()
        if (parsedPort == null || parsedPort !in FsapConstants.MIN_VALID_PORT..FsapConstants.MAX_VALID_PORT) {
            _uiState.update {
                it.copy(settingsError = "Porta HTTP deve estar entre ${FsapConstants.MIN_VALID_PORT} e ${FsapConstants.MAX_VALID_PORT}.")
            }
            return
        }

        _uiState.update {
            it.copy(
                localAlias = cleanAlias,
                boundHttpPort = parsedPort,
                aliasDraft = cleanAlias,
                portDraft = parsedPort.toString(),
                settingsError = null,
            )
        }

        stopNodeInternal()
        startNodeInternal()
        if (nodeFsm.currentState() == LocalNodeState.AVAILABLE) {
            _uiState.update { it.copy(statusBanner = "Configuracoes aplicadas e servidor reiniciado.") }
        }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(statusBanner = null) }
    }
}

