package br.edu.fileshare.desktop.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * Seletor nativo de arquivos exclusivo da interface Desktop (`TASK-DCLI-004`).
 *
 * Isolado do pacote `desktop.server` e de `DesktopNioFileStorage` para preservar a separacao
 * estrita entre GUI (`Swing`/`AWT`) e servico de rede headless.
 */
object DesktopFilePicker {

    /**
     * Abre o dialogo multi-selecao na EDT do Swing e retorna [DesktopNioFileSource] validos.
     */
    suspend fun pickMultipleFiles(
        dialogTitle: String = "Selecionar arquivos para enviar",
        initialDirectory: File? = defaultInitialDirectory(),
    ): List<DesktopNioFileSource> = withContext(Dispatchers.Main) {
        runCatching {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        }

        val chooser = JFileChooser(initialDirectory).apply {
            this.dialogTitle = dialogTitle
            isMultiSelectionEnabled = true
            fileSelectionMode = JFileChooser.FILES_ONLY
            isFileHidingEnabled = false
        }

        val result = chooser.showOpenDialog(null)
        if (result != JFileChooser.APPROVE_OPTION) {
            return@withContext emptyList()
        }

        chooser.selectedFiles
            .orEmpty()
            .mapNotNull { file ->
                val path = file.toPath().toAbsolutePath().normalize()
                if (Files.isRegularFile(path) && Files.isReadable(path)) {
                    DesktopNioFileSource(path)
                } else {
                    null
                }
            }
    }

    private fun defaultInitialDirectory(): File? {
        val userHome = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: return null
        val homeDir = File(userHome)
        return homeDir.takeIf { it.isDirectory }
    }
}

