package br.edu.fileshare.ui.format

import kotlin.math.roundToLong

/**
 * Formatadores puros multiplataforma para tamanhos em bytes, taxas e duracoes (`commonMain`).
 */
object ByteFormatter {

    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        if (safe < 1024L) return "$safe B"
        val kb = safe / 1024.0
        if (kb < 1024.0) return "${formatOneDecimal(kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024.0) return "${formatTwoDecimals(mb)} MB"
        val gb = mb / 1024.0
        return "${formatTwoDecimals(gb)} GB"
    }

    fun formatSpeed(bytesPerSecond: Long): String {
        val safe = bytesPerSecond.coerceAtLeast(0L)
        if (safe == 0L) return "0 B/s"
        return "${formatBytes(safe)}/s"
    }

    fun formatEta(seconds: Long?): String {
        if (seconds == null) return "--"
        if (seconds <= 0L) return "< 1s"
        val mins = seconds / 60L
        val remSecs = seconds % 60L
        return when {
            mins == 0L -> "${remSecs}s"
            mins < 60L -> "${mins}m ${remSecs.toString().padStart(2, '0')}s"
            else -> {
                val hours = mins / 60L
                val remMins = mins % 60L
                "${hours}h ${remMins.toString().padStart(2, '0')}m"
            }
        }
    }

    private fun formatOneDecimal(value: Double): String {
        val tenths = (value * 10.0).roundToLong()
        val whole = tenths / 10L
        val frac = tenths % 10L
        return "$whole.$frac"
    }

    private fun formatTwoDecimals(value: Double): String {
        val hundredths = (value * 100.0).roundToLong()
        val whole = hundredths / 100L
        val frac = (hundredths % 100L).toString().padStart(2, '0')
        return "$whole.$frac"
    }
}

