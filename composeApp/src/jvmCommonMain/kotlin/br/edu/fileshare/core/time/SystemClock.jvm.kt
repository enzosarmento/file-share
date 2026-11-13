package br.edu.fileshare.core.time

import java.time.Instant
import java.time.temporal.ChronoUnit

actual object SystemClock : Clock {
    actual override fun epochMillis(): Long = System.currentTimeMillis()

    actual override fun nowIsoUtc(): String = Instant.now()
        .truncatedTo(ChronoUnit.SECONDS)
        .toString()
}

