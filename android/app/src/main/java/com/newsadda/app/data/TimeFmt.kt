package com.newsadda.app.data

import com.newsadda.app.ui.Str
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** All times are shown in Indian Standard Time, whatever the phone's zone. */
object TimeFmt {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    /** "13:05", "Yesterday 21:10" / "कल 21:10", "12 Oct 18:00" (IST). */
    fun short(instant: Instant?, now: Instant = Instant.now(), s: Str = Str(false)): String {
        if (instant == null) return "—"
        val z = instant.atZone(IST)
        val today: LocalDate = now.atZone(IST).toLocalDate()
        val d = z.toLocalDate()
        return when (d) {
            today -> HM.format(z)
            today.minusDays(1) -> s.yesterday + " " + HM.format(z)
            else -> "${d.dayOfMonth} ${s.months[d.monthValue - 1]} " + HM.format(z)
        }
    }

    /** Full stamp for detail views: "13 Oct 2026, 13:05 IST". */
    fun full(instant: Instant?, s: Str = Str(false)): String {
        if (instant == null) return s.notAvailable
        val z = instant.atZone(IST)
        return "${z.dayOfMonth} ${s.months[z.monthValue - 1]} ${z.year}, ${HM.format(z)} IST"
    }

    /** "just now", "5 min ago", "2 h ago", "3 d ago" (or Hindi). */
    fun ago(instant: Instant?, now: Instant = Instant.now(), s: Str = Str(false)): String {
        if (instant == null) return "—"
        val d = Duration.between(instant, now)
        if (d.isNegative) return s.justNow
        val m = d.toMinutes()
        return when {
            m < 1 -> s.justNow
            m < 60 -> s.minAgo(m)
            m < 24 * 60 -> s.hoursAgo(m / 60)
            else -> s.daysAgo(m / (24 * 60))
        }
    }
}
