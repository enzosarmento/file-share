package br.edu.fileshare.core.id

import java.util.UUID

actual object PlatformUuidGenerator : UuidGenerator {
    actual override fun nextUuidV4(): String = UUID.randomUUID().toString()
}

