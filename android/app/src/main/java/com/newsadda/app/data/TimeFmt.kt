package com.newsadda.app.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** All times are shown in Indian Standard Time, whatever the phone's zone. */
object TimeFmt {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val MONTHS = listOf("जन", "फ़र", "मार्च", "अप्रै", "मई", "जून", "जुल", "अग", "सित", "अक्टू", "नव", "दिस")

    /** "13:05", "कल 21:10", "12 अक्टू 18:00" (IST). */
    fun short(instant: Instant?, now: Instant = Instant.now()): String {
        if (instant == null) return "—"
        val z = instant.atZone(IST)
        val today: LocalDate = now.atZone(IST).toLocalDate()
        val d = z.toLocalDate()
        return when (d) {
            today -> HM.format(z)
            today.minusDays(1) -> "कल " + HM.format(z)
            else -> "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]} " + HM.format(z)
        }
    }

    /** Full stamp for detail views: "13 अक्टू 2026, 13:05 IST". */
    fun full(instant: Instant?): String {
        if (instant == null) return "उपलब्ध नहीं"
        val z = instant.atZone(IST)
        return "${z.dayOfMonth} ${MONTHS[z.monthValue - 1]} ${z.year}, ${HM.format(z)} IST"
    }

    /** "अभी", "5 मिनट पहले", "2 घंटे पहले", "3 दिन पहले". */
    fun ago(instant: Instant?, now: Instant = Instant.now()): String {
        if (instant == null) return "—"
        val d = Duration.between(instant, now)
        if (d.isNegative) return "अभी"
        val m = d.toMinutes()
        return when {
            m < 1 -> "अभी"
            m < 60 -> "$m मिनट पहले"
            m < 24 * 60 -> "${m / 60} घंटे पहले"
            else -> "${m / (24 * 60)} दिन पहले"
        }
    }
}
