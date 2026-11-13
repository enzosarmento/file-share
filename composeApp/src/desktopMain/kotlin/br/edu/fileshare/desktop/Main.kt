package br.edu.fileshare.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import br.edu.fileshare.ProjectInfo
import br.edu.fileshare.desktop.runtime.DesktopAppRuntime
import br.edu.fileshare.ui.FileShareApp
import br.edu.fileshare.ui.components.ManualIpConnectButton
import br.edu.fileshare.ui.components.ManualIpConnectDialogHost
import br.edu.fileshare.ui.viewmodel.FileShareMainViewModel
import br.edu.fileshare.ui.viewmodel.ManualIpConnectViewModel

/**
 * Ponto de entrada da aplicacao grafica Compose Desktop (`TASK-DCLI-001`..`TASK-DCLI-006`).
 */
fun main() = application {
    val runtime = remember { DesktopAppRuntime() }
    val uiScope = rememberCoroutineScope()
    val mainViewModel = remember(runtime, uiScope) {
        FileShareMainViewModel(
            runtime = runtime,
            scope = uiScope,
            logger = runtime.logger,
        )
    }
    val manualIpViewModel = remember(runtime, uiScope, mainViewModel) {
        ManualIpConnectViewModel(
            connectPort = { ip, port -> runtime.connectByManualIp(ip, port) },
            scope = uiScope,
            onConnected = { peer -> mainViewModel.selectPeer(peer.identity.deviceId) },
        )
    }

    LaunchedEffect(mainViewModel) {
        mainViewModel.startNode()
    }

    DisposableEffect(runtime) {
        onDispose {
            runtime.close()
        }
    }

    val windowState = rememberWindowState(width = 1040.dp, height = 720.dp)
    Window(
        onCloseRequest = {
            runtime.close()
            exitApplication()
        },
        state = windowState,
        title = "${ProjectInfo.PROJECT_NAME} - ${ProjectInfo.PROTOCOL_NAME}/${ProjectInfo.PROTOCOL_VERSION}",
    ) {
        FileShareApp(
            viewModel = mainViewModel,
            manualConnectTrigger = {
                ManualIpConnectButton(onClick = manualIpViewModel::openDialog)
            },
            overlayDialogs = {
                ManualIpConnectDialogHost(viewModel = manualIpViewModel)
            },
        )
    }
}
