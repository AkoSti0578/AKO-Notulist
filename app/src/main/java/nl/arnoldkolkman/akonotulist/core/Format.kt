package nl.arnoldkolkman.akonotulist.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formatting helpers. Pure Kotlin so they can be unit tested. */
object Format {
    private val dutch = Locale.forLanguageTag("nl-NL")

    /** 3723000 -> "1:02:03", 63000 -> "1:03". */
    fun duration(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0) / 1000)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Timestamp for transcripts: always mm:ss or h:mm:ss, e.g. "00:42" / "1:02:03". */
    fun timestamp(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0) / 1000)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun dateTime(epochMs: Long): String = SimpleDateFormat("EEE d MMM yyyy, HH:mm", dutch).format(Date(epochMs))

    fun defaultTitle(epochMs: Long): String = "Opname " + SimpleDateFormat("d MMM HH:mm", dutch).format(Date(epochMs))

    fun usd(amount: Double): String = when {
        amount < 0.01 -> "< $0,01"
        else -> "$" + String.format(dutch, "%.2f", amount)
    }

    /** Cost estimate: audio duration (minutes, not rounded up) times the per-minute rate. */
    fun estimateCostUsd(durationMs: Long, ratePerMinute: Double): Double = durationMs / 60_000.0 * ratePerMinute
}
