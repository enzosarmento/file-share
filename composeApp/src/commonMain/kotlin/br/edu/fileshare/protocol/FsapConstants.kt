package br.edu.fileshare.protocol

/**
 * Constantes normativas de transporte, limites e temporizadores do protocolo FSAP/1.0
 * conforme especificado em `docs/protocol-fsap.md`.
 */
object FsapConstants {
    const val PROTOCOL_NAME: String = "FSAP"
    const val PROTOCOL_VERSION: String = "1.0"
    const val PROTOCOL_MAJOR: Int = 1
    const val PROTOCOL_MINOR: Int = 0

    // Rede e portas padrao
    const val MULTICAST_GROUP_IPV4: String = "239.255.70.83"
    const val UDP_DISCOVERY_PORT: Int = 53318
    const val DEFAULT_HTTP_PORT: Int = 53317
    const val MULTICAST_IP_TTL: Int = 1
    const val MIN_VALID_PORT: Int = 1024
    const val MAX_VALID_PORT: Int = 65535

    // Limites de payload, chunks e arquivos
    const val MAX_UDP_DATAGRAM_BYTES: Int = 1024
    const val MAX_JSON_BODY_BYTES: Int = 262_144 // 256 KB
    const val CHUNK_SIZE_BYTES: Int = 65_536 // 64 KB
    const val MAX_FILE_SIZE_BYTES: Long = 68_719_476_736L // 64 GiB
    const val MAX_FILES_PER_BATCH: Int = 100
    const val MAX_CONCURRENT_RECEIVE_SESSIONS: Int = 8
    const val MAX_ALIAS_LENGTH: Int = 64

    // Temporizadores normativos (milissegundos)
    const val T_ANNOUNCE_INTERVAL_MS: Long = 5_000L
    const val T_DISCOVER_JITTER_MIN_MS: Long = 50L
    const val T_DISCOVER_JITTER_MAX_MS: Long = 300L
    const val T_PEER_STALE_MS: Long = 12_000L
    const val T_PEER_TTL_MS: Long = 18_000L
    const val T_PEER_SWEEP_INTERVAL_MS: Long = 3_000L
    const val T_TCP_CONNECT_TIMEOUT_MS: Long = 5_000L
    const val T_APPROVAL_TIMEOUT_MS: Long = 60_000L
    const val T_TOKEN_IDLE_TTL_MS: Long = 30_000L
    const val T_STREAM_READ_TIMEOUT_MS: Long = 15_000L
    const val T_SHUTDOWN_GRACE_MS: Long = 2_000L
    const val T_PROGRESS_THROTTLE_MS: Long = 100L

    // Rotas HTTP FSAP v1
    object Routes {
        const val BASE_V1: String = "/fsap/v1"
        const val INFO: String = "$BASE_V1/info"
        const val IDENTIFY: String = "$BASE_V1/identify"
        const val TRANSFERS_PREPARE: String = "$BASE_V1/transfers/prepare"
        fun transferContent(transferId: String, fileId: String): String =
            "$BASE_V1/transfers/$transferId/files/$fileId/content"
        fun transferStatus(transferId: String): String =
            "$BASE_V1/transfers/$transferId/status"
        fun transferCancel(transferId: String): String =
            "$BASE_V1/transfers/$transferId/cancel"
        fun transferComplete(transferId: String): String =
            "$BASE_V1/transfers/$transferId/complete"
    }
}

