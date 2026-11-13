package br.edu.fileshare.storage

import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException

/**
 * Resultado da validacao de segurança de nome de arquivo.
 */
sealed interface FileNameValidationResult {
    data class Safe(val fileName: String) : FileNameValidationResult
    data class Unsafe(val reason: String) : FileNameValidationResult
}

/**
 * Sanitizador e validador normativo de nomes de arquivo conforme `docs/security.md` (Secao 2).
 * Impede Path Traversal, caracteres de controle, dispositivos reservados do Windows
 * e sobrescrita acidental de arquivos existentes.
 */
object FileNameSanitizer {

    const val MAX_FILENAME_UTF8_BYTES: Int = 255

    private val FORBIDDEN_CHARS = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
    private val WINDOWS_RESERVED_NAMES = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) {
            add("COM$i")
            add("LPT$i")
        }
    }

    fun validate(fileName: String): FileNameValidationResult {
        if (fileName.isBlank()) {
            return FileNameValidationResult.Unsafe("O nome do arquivo nao pode ser vazio.")
        }
        val utf8Size = fileName.encodeToByteArray().size
        if (utf8Size !in 1..MAX_FILENAME_UTF8_BYTES) {
            return FileNameValidationResult.Unsafe(
                "O nome de arquivo excede o limite de $MAX_FILENAME_UTF8_BYTES bytes UTF-8 ($utf8Size)."
            )
        }
        if (fileName == "." || fileName == ".." || fileName.contains("..")) {
            return FileNameValidationResult.Unsafe(
                "Sequencia de navegacao relativa proibida no nome do arquivo."
            )
        }
        if (fileName.endsWith('.') || fileName.endsWith(' ')) {
            return FileNameValidationResult.Unsafe(
                "Nome de arquivo nao pode terminar com ponto ou espaco."
            )
        }
        for (ch in fileName) {
            val code = ch.code
            if (code in 0x00..0x1F || code == 0x7F) {
                return FileNameValidationResult.Unsafe(
                    "Nome de arquivo contem caractere de controle proibido (0x${code.toString(16)})."
                )
            }
            if (ch in FORBIDDEN_CHARS) {
                return FileNameValidationResult.Unsafe(
                    "Nome de arquivo contem caractere proibido ('$ch')."
                )
            }
        }
        val baseStem = fileName.substringBefore('.').trim().uppercase()
        if (baseStem in WINDOWS_RESERVED_NAMES) {
            return FileNameValidationResult.Unsafe(
                "Nome reservado de dispositivo do sistema operacional nao permitido: '$baseStem'."
            )
        }
        return FileNameValidationResult.Safe(fileName)
    }

    /**
     * Valida e retorna o nome seguro ou lanca [FsapProtocolException] com `FSAP-ERR-400-INVALID_FILENAME`.
     */
    fun requireValid(fileName: String): String = when (val result = validate(fileName)) {
        is FileNameValidationResult.Safe -> result.fileName
        is FileNameValidationResult.Unsafe -> throw FsapProtocolException(
            errorCode = FsapErrorCode.INVALID_FILENAME,
            message = "Nome de arquivo invalido ('$fileName'): ${result.reason}",
        )
    }

    /**
     * Resolve colisoes conforme politica de nao-sobrescrita (`documento.pdf` -> `documento (1).pdf`).
     */
    fun resolveNonOverwritingName(
        requestedName: String,
        exists: (String) -> Boolean,
    ): String {
        val validName = requireValid(requestedName)
        if (!exists(validName)) return validName

        val lastDot = validName.lastIndexOf('.')
        val (stem, ext) = if (lastDot > 0) {
            validName.substring(0, lastDot) to validName.substring(lastDot)
        } else {
            validName to ""
        }

        var counter = 1
        while (true) {
            val suffix = " ($counter)$ext"
            val maxStemBytes = (MAX_FILENAME_UTF8_BYTES - suffix.encodeToByteArray().size).coerceAtLeast(1)
            val truncatedStem = truncateUtf8(stem, maxStemBytes)
            val candidate = "$truncatedStem$suffix"
            if (!exists(candidate)) {
                return candidate
            }
            counter += 1
        }
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.encodeToByteArray().size <= maxBytes) return value
        val sb = StringBuilder()
        var currentBytes = 0
        for (ch in value) {
            val charBytes = ch.toString().encodeToByteArray().size
            if (currentBytes + charBytes > maxBytes) break
            sb.append(ch)
            currentBytes += charBytes
        }
        return sb.toString()
    }
}

