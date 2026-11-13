package br.edu.fileshare.ui.viewmodel

import br.edu.fileshare.network.discovery.DiscoveredPeer
import br.edu.fileshare.network.discovery.PeerOrigin
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.LocalNodeState
import br.edu.fileshare.protocol.statemachine.PeerConnectionState
import br.edu.fileshare.storage.FileSource
import br.edu.fileshare.storage.InMemoryFileSource
import br.edu.fileshare.storage.StoredFileDescriptor
import br.edu.fileshare.ui.format.ByteFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileShareMainViewModelTest {

    private class FakeRuntimePort : FileShareRuntimePort {
        override val localDeviceId: String = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        override val initialAlias: String = "Desktop-Lab"
        override val initialPort: Int = FsapConstants.DEFAULT_HTTP_PORT
        override val saveDirectoryLabel: String = "/tmp/downloads"

        val peersFlow = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
        val receivedFlow = MutableStateFlow<List<StoredFileDescriptor>>(emptyList())
        val addressesFlow = MutableStateFlow(listOf("192.168.1.42"))

        override val peers: StateFlow<List<DiscoveredPeer>> = peersFlow
        override val receivedFiles: StateFlow<List<StoredFileDescriptor>> = receivedFlow
        override val localAddresses: StateFlow<List<String>> = addressesFlow

        var failNextStart: Boolean = false
        var pickedFilesQueue: List<FileSource> = emptyList()
        var lastStartedAlias: String? = null
        var lastStartedPort: Int? = null
        var stopCalls: Int = 0
        var lastSentTransfer: Pair<DiscoveredPeer, List<FileSource>>? = null

        override suspend fun startNode(alias: String, port: Int): Result<Int> {
            lastStartedAlias = alias
            lastStartedPort = port
            return if (failNextStart) {
                failNextStart = false
                Result.failure(IllegalStateException("Porta em uso"))
            } else {
                Result.success(port)
            }
        }

        override suspend fun stopNode() {
            stopCalls++
        }

        override suspend fun pickFilesToSend(): List<FileSource> = pickedFilesQueue

        override suspend fun sendFiles(peer: DiscoveredPeer, sources: List<FileSource>): Result<String> {
            lastSentTransfer = peer to sources
            return Result.success("7c9e6679-7425-40de-944b-e07fc1f90ae7")
        }
    }

    private fun samplePeer(id: String = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", alias: String = "Celular"): DiscoveredPeer =
        DiscoveredPeer(
            identity = DeviceIdentity(
                deviceId = id,
                alias = alias,
                deviceType = DeviceType.MOBILE,
                platform = PlatformType.ANDROID,
                httpPort = 53317,
            ),
            ipAddress = "192.168.1.99",
            port = 53317,
            connectionState = PeerConnectionState.CONNECTED,
            origin = PeerOrigin.MANUAL_IP,
            lastSeenEpochMillis = 100L,
            firstSeenEpochMillis = 100L,
        )

    @Test
    fun `ciclo de vida do no local transita entre STOPPED, FAULTED, AVAILABLE e STOPPED`() = runTest {
        val runtime = FakeRuntimePort()
        runtime.failNextStart = true
        val vm = FileShareMainViewModel(runtime, backgroundScope)
        runCurrent()

        assertEquals(LocalNodeState.STOPPED, vm.uiState.value.nodeState)

        // Falha de bind leva para FAULTED e exibe banner
        vm.startNodeInternal()
        assertEquals(LocalNodeState.FAULTED, vm.uiState.value.nodeState)
        assertNotNull(vm.uiState.value.statusBanner)

        // Retry bem-sucedido leva para AVAILABLE e limpa banner
        vm.startNodeInternal()
        assertEquals(LocalNodeState.AVAILABLE, vm.uiState.value.nodeState)
        assertNull(vm.uiState.value.statusBanner)

        // Stop retorna para STOPPED
        vm.stopNodeInternal()
        assertEquals(LocalNodeState.STOPPED, vm.uiState.value.nodeState)
        assertEquals(1, runtime.stopCalls)
    }

    @Test
    fun `navegacao entre abas, selecao de arquivos e envio para par conectado funcionam em UDF`() = runTest {
        val runtime = FakeRuntimePort()
        val vm = FileShareMainViewModel(runtime, backgroundScope)
        vm.startNodeInternal()
        runCurrent()

        vm.selectTab(MainTab.RECEIVED)
        assertEquals(MainTab.RECEIVED, vm.uiState.value.activeTab)
        vm.selectTab(MainTab.SEND)

        // Seletor adiciona arquivos e evita duplicata exata nome+tamanho
        val f1 = InMemoryFileSource("a.pdf", ByteArray(100))
        val f2 = InMemoryFileSource("b.png", ByteArray(250))
        runtime.pickedFilesQueue = listOf(f1, f2, f1)
        vm.pickFilesInternal()
        assertEquals(2, vm.uiState.value.selectedFiles.size)
        assertEquals(350L, vm.uiState.value.totalSelectedBytes)

        // Aparece peer unico -> auto-selecionado
        val peer = samplePeer()
        runtime.peersFlow.value = listOf(peer)
        runCurrent()
        assertEquals(peer.identity.deviceId, vm.uiState.value.selectedPeerDeviceId)
        assertTrue(vm.uiState.value.canSendFiles)

        // Envio move para aba Transferencias e limpa fila de envio
        vm.sendSelectedFilesInternal()
        assertNotNull(runtime.lastSentTransfer)
        assertEquals(2, runtime.lastSentTransfer?.second?.size)
        assertEquals(MainTab.TRANSFERS, vm.uiState.value.activeTab)
        assertTrue(vm.uiState.value.selectedFiles.isEmpty())
    }

    @Test
    fun `validacao de alias e porta nas configuracoes impede reinicio invalido`() = runTest {
        val runtime = FakeRuntimePort()
        val vm = FileShareMainViewModel(runtime, backgroundScope)
        vm.startNodeInternal()
        runCurrent()

        vm.updateSettingsDraft("", "53317")
        vm.applySettingsAndRestartInternal()
        assertNotNull(vm.uiState.value.settingsError)

        vm.updateSettingsDraft("Meu-PC", "80")
        vm.applySettingsAndRestartInternal()
        assertNotNull(vm.uiState.value.settingsError)

        vm.updateSettingsDraft("Desktop-Sala3", "54000")
        vm.applySettingsAndRestartInternal()
        assertNull(vm.uiState.value.settingsError)
        assertEquals("Desktop-Sala3", runtime.lastStartedAlias)
        assertEquals(54000, runtime.lastStartedPort)
        assertEquals(54000, vm.uiState.value.boundHttpPort)
        assertEquals(LocalNodeState.AVAILABLE, vm.uiState.value.nodeState)
    }

    @Test
    fun `ByteFormatter formata bytes taxas e ETA corretamente`() {
        assertEquals("512 B", ByteFormatter.formatBytes(512))
        assertEquals("1.5 KB", ByteFormatter.formatBytes(1536))
        assertEquals("2.50 MB/s", ByteFormatter.formatSpeed(2_621_440L))
        assertEquals("--", ByteFormatter.formatEta(null))
        assertEquals("45s", ByteFormatter.formatEta(45))
        assertEquals("2m 05s", ByteFormatter.formatEta(125))
    }
}

