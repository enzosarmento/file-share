package br.edu.fileshare.desktop.server

import br.edu.fileshare.network.server.ApprovalRequestContext
import br.edu.fileshare.network.server.TransferApprovalDecision
import br.edu.fileshare.network.server.TransferApprovalHandler
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.model.RejectReasonCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream
import java.util.Locale

/**
 * Handler interativo de aprovacao em terminal para o Servidor Desktop Headless (`TASK-DCLI-004`).
 *
 * Nao possui dependencias graficas e serializa prompts concorrentes por meio de [promptMutex].
 */
class ConsoleTransferApprovalHandler(
    private val out: PrintStream = System.out,
    private val timeoutMs: Long = FsapConstants.T_APPROVAL_TIMEOUT_MS,
    private val lineProvider: suspend (ApprovalRequestContext) -> String? = defaultStdinLineProvider(),
) : TransferApprovalHandler {

    private val promptMutex = Mutex()

    override suspend fun requestApproval(
        context: ApprovalRequestContext,
    ): TransferApprovalDecision = promptMutex.withLock {
        val request = context.request
        out.println()
        out.println("=== Solicitacao de transferencia FSAP/1.0 ===")
        out.println("  Transfer ID : ${context.transferId}")
        out.println("  Remetente   : ${request.senderAlias} (${request.senderDeviceType}, id=${context.senderId})")
        out.println("  Total       : ${formatHumanBytes(request.totalBytes)} (${request.files.size} arquivo(s))")
        request.files.forEachIndexed { idx, file ->
            out.println("    ${idx + 1}. ${file.fileName} (${formatHumanBytes(file.sizeBytes)})")
        }
        out.print("Aceitar recebimento? [s/N] (expira em ${timeoutMs / 1000}s): ")
        out.flush()

        val rawAnswer = withTimeoutOrNull(timeoutMs) {
            lineProvider(context)
        }

        if (rawAnswer == null) {
            out.println("\n[Timeout/EOF] Solicitacao ${context.transferId} sem resposta no terminal.")
            return@withLock TransferApprovalDecision.Rejected(
                reasonCode = RejectReasonCode.USER_DECLINED,
                reasonMessage = "Solicitacao nao aprovada dentro do prazo no terminal.",
            )
        }

        return@withLock when (parseApprovalChoice(rawAnswer)) {
            true -> TransferApprovalDecision.Approved(request.files.map { it.fileId })
            false -> TransferApprovalDecision.Rejected(
                reasonCode = RejectReasonCode.USER_DECLINED,
                reasonMessage = "Recebimento recusado pelo operador do servidor.",
            )
        }
    }

    companion object {
        fun parseApprovalChoice(input: String?): Boolean {
            val normalized = input?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return normalized in setOf("s", "sim", "y", "yes", "1")
        }

        fun formatHumanBytes(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
            val gb = mb / 1024.0
            return String.format(Locale.US, "%.2f GB", gb)
        }

        fun defaultStdinLineProvider(
            reader: BufferedReader = BufferedReader(InputStreamReader(System.`in`)),
        ): suspend (ApprovalRequestContext) -> String? = {
            withContext(Dispatchers.IO) {
                while (isActive) {
                    if (reader.ready()) {
                        return@withContext reader.readLine()
                    }
                    delay(50L)
                }
                null
            }
        }
    }
}
