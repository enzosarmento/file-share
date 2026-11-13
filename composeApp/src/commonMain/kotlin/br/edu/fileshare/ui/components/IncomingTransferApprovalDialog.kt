package br.edu.fileshare.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.edu.fileshare.ui.format.ByteFormatter
import br.edu.fileshare.ui.viewmodel.IncomingApprovalViewModel
import br.edu.fileshare.ui.viewmodel.IncomingPromptUiModel

/**
 * Host reativo que apresenta o modal de aprovacao explicita quando houver solicitacao pendente (`TASK-DCLI-005`).
 */
@Composable
fun IncomingTransferApprovalDialogHost(
    viewModel: IncomingApprovalViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val prompt = state.activePrompt ?: return

    IncomingTransferApprovalDialog(
        prompt = prompt,
        pendingCount = state.pendingCount,
        isSubmitting = state.isSubmitting,
        onAccept = viewModel::acceptActive,
        onDecline = viewModel::declineActive,
    )
}

@Composable
fun IncomingTransferApprovalDialog(
    prompt: IncomingPromptUiModel,
    pendingCount: Int,
    isSubmitting: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* Confirmacao explicita obrigatoria */ },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Receber arquivos?")
                if (pendingCount > 1) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = "1 de $pendingCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = prompt.senderAlias,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Tipo de dispositivo: ${prompt.senderDeviceType}  |  Total: ${ByteFormatter.formatBytes(prompt.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "${prompt.files.size} arquivo(s) no lote:",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = "Expira em ${prompt.remainingSeconds}s",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (prompt.remainingSeconds <= 10L) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                HorizontalDivider()

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                ) {
                    items(prompt.files, key = { it.fileId }) { file ->
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = file.fileName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                            )
                            Text(
                                text = ByteFormatter.formatBytes(file.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline, enabled = !isSubmitting) {
                Text("Recusar")
            }
        },
        confirmButton = {
            Button(onClick = onAccept, enabled = !isSubmitting) {
                Text("Aceitar")
            }
        },
    )
}

