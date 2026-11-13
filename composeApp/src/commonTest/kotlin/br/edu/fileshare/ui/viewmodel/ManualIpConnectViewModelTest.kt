package br.edu.fileshare.ui.viewmodel

import br.edu.fileshare.network.discovery.DiscoveredPeer
import br.edu.fileshare.network.discovery.ManualConnectResult
import br.edu.fileshare.network.discovery.PeerOrigin
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.PeerConnectionState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManualIpConnectViewModelTest {

    private fun samplePeer(): DiscoveredPeer = DiscoveredPeer(
        identity = DeviceIdentity(
            deviceId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            alias = "Android Sala 3",
            deviceType = DeviceType.MOBILE,
            platform = PlatformType.ANDROID,
            httpPort = 53317,
        ),
        ipAddress = "192.168.1.50",
        port = 53317,
        connectionState = PeerConnectionState.CONNECTED,
        origin = PeerOrigin.MANUAL_IP,
        lastSeenEpochMillis = 100L,
        firstSeenEpochMillis = 100L,
    )

    @Test
    fun `validacao de IPv4 hostname e porta impede envio invalido sem chamar rede`() = runTest {
        var calls = 0
        val vm = ManualIpConnectViewModel(
            connectPort = ManualConnectPort { _, _ ->
                calls++
                ManualConnectResult.Failed(FsapErrorCode.DEVICE_NOT_FOUND, "nao deveria rodar")
            },
            scope = backgroundScope,
        )

        vm.openDialog()
        assertTrue(vm.uiState.value.isDialogOpen)
        assertEquals(FsapConstants.DEFAULT_HTTP_PORT.toString(), vm.uiState.value.portInput)

        // Campo vazio
        vm.onIpInputChange("   ")
        assertFalse(vm.submitInternal())
        assertNotNull(vm.uiState.value.ipError)
        assertNull(vm.uiState.value.portError)
        assertEquals(0, calls)

        // IPv4 incompleto e octeto > 255
        for (invalidIp in listOf("192.168.1", "256.10.0.1", "192.168.1.50/24", "192.168..1")) {
            vm.onIpInputChange(invalidIp)
            assertFalse(vm.submitInternal(), "Esperava erro para '$invalidIp'")
            assertNotNull(vm.uiState.value.ipError)
        }

        // Portas fora da faixa 1024..65535 ou nao-numericas
        vm.onIpInputChange("192.168.1.50")
        for (invalidPort in listOf("80", "0", "65536", "abc")) {
            vm.onPortInputChange(invalidPort)
            assertFalse(vm.submitInternal(), "Esperava erro para porta '$invalidPort'")
            assertNull(vm.uiState.value.ipError)
            assertNotNull(vm.uiState.value.portError)
        }
        assertEquals(0, calls)
    }

    @Test
    fun `conexao bem-sucedida fecha dialogo e seleciona par e falha mantem dialogo aberto com erro`() = runTest {
        val peer = samplePeer()
        var nextResult: ManualConnectResult = ManualConnectResult.Failed(
            errorCode = FsapErrorCode.DEVICE_NOT_FOUND,
            message = "Timeout na porta 53317",
        )
        var connectedPeer: DiscoveredPeer? = null

        val vm = ManualIpConnectViewModel(
            connectPort = ManualConnectPort { ip, port ->
                assertEquals("192.168.1.50", ip)
                assertEquals(53317, port)
                nextResult
            },
            scope = backgroundScope,
            onConnected = { connectedPeer = it },
        )

        vm.openDialog()
        vm.onIpInputChange("192.168.1.50")
        vm.onPortInputChange("53317")

        // Falha: dialog segue aberto e exibe erro
        assertFalse(vm.submitInternal())
        assertTrue(vm.uiState.value.isDialogOpen)
        assertFalse(vm.uiState.value.isConnecting)
        assertNotNull(vm.uiState.value.connectionError)
        assertTrue(vm.uiState.value.connectionError!!.contains(FsapErrorCode.DEVICE_NOT_FOUND.code))
        assertNull(connectedPeer)

        // Sucesso: dialog fecha e notifica par conectado
        nextResult = ManualConnectResult.Connected(peer)
        assertTrue(vm.submitInternal())
        assertFalse(vm.uiState.value.isDialogOpen)
        assertNull(vm.uiState.value.connectionError)
        assertEquals(peer, connectedPeer)
    }
}
