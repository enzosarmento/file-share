package br.edu.fileshare.protocol.model

/**
 * Cabecalhos HTTP normativos e tipos MIME do protocolo FSAP/1.0.
 */
object FsapHeaders {
    const val VERSION: String = "X-FSAP-Version"
    const val PROTOCOL: String = "X-FSAP-Protocol"
    const val SENDER_ID: String = "X-FSAP-Sender-Id"
    const val TRANSFER_TOKEN: String = "X-FSAP-Transfer-Token"
    const val FILE_SHA256: String = "X-FSAP-File-SHA256"

    const val CONTENT_TYPE_JSON: String = "application/json; charset=utf-8"
    const val CONTENT_TYPE_OCTET_STREAM: String = "application/octet-stream"
}

