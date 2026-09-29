package ru.wizard.web.claudeproxy.usage

import java.time.DayOfWeek
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Расчёт границ недельного и месячного лимитов провайдера.
 *
 * Неделя: скользящие 7 суток (по умолчанию) либо календарная неделя
 * от фиксированного дня начала — сброс в этот день недели. Месяц:
 * скользящие 30 суток, пока не задан день платёжного периода; с днём —
 * ровно 30 суток от последнего наступившего этого дня (в коротких
 * месяцах день ограничивается последним днём месяца).
 */
object ProviderLimitWindowCalculator {

    /** Режим недельного лимита: скользящее окно в 7 суток. */
    const val WEEK_MODE_SLIDING = "SLIDING"

    /** Режим недельного лимита: календарная неделя от фиксированного дня начала. */
    const val WEEK_MODE_FIXED_DAY = "FIXED_DAY"

    /** Дни недели для настройки дня начала недели. */
    val WEEK_DAYS: Map<String, DayOfWeek> = mapOf(
        "MONDAY" to DayOfWeek.MONDAY,
        "TUESDAY" to DayOfWeek.TUESDAY,
        "WEDNESDAY" to DayOfWeek.WEDNESDAY,
        "THURSDAY" to DayOfWeek.THURSDAY,
        "FRIDAY" to DayOfWeek.FRIDAY,
        "SATURDAY" to DayOfWeek.SATURDAY,
        "SUNDAY" to DayOfWeek.SUNDAY,
    )

    private const val DAY_MILLISECONDS = 86_400_000L

    /**
     * Границы недельного окна [from, to) в эпоховых миллисекундах:
     * скользящие 7 суток либо календарная неделя от дня начала
     * (неизвестный режим или день — скользящее окно / понедельник).
     */
    fun weekWindow(
        weekMode: String?,
        weekStartDay: String?,
        nowMilliseconds: Long,
        zone: ZoneId,
    ): Pair<Long, Long> {
        if (weekMode != WEEK_MODE_FIXED_DAY) {
            return nowMilliseconds - 7 * DAY_MILLISECONDS to nowMilliseconds
        }
        val day = WEEK_DAYS[weekStartDay] ?: DayOfWeek.MONDAY
        val today = Instant.ofEpochMilli(nowMilliseconds).atZone(zone).toLocalDate()
        val anchor = today.with(TemporalAdjusters.previousOrSame(day))
        val from = anchor.atStartOfDay(zone).toInstant().toEpochMilli()
        return from to from + 7 * DAY_MILLISECONDS
    }

    /**
     * Границы месячного окна [from, to) в эпоховых миллисекундах:
     * без дня платёжного периода (или вне 1..31) — скользящие 30 суток,
     * иначе ровно 30 суток от последнего наступившего дня периода.
     */
    fun monthWindow(
        monthStartDay: Int?,
        nowMilliseconds: Long,
        zone: ZoneId,
    ): Pair<Long, Long> {
        if (monthStartDay == null || monthStartDay !in 1..31) {
            return nowMilliseconds - 30 * DAY_MILLISECONDS to nowMilliseconds
        }
        val today = Instant.ofEpochMilli(nowMilliseconds).atZone(zone).toLocalDate()
        var month = YearMonth.from(today)
        var anchor = month.atDay(minOf(monthStartDay, month.lengthOfMonth())).atStartOfDay(zone)
        if (anchor.toInstant().toEpochMilli() > nowMilliseconds) {
            month = month.minusMonths(1)
            anchor = month.atDay(minOf(monthStartDay, month.lengthOfMonth())).atStartOfDay(zone)
        }
        val from = anchor.toInstant().toEpochMilli()
        return from to from + 30 * DAY_MILLISECONDS
    }
}
