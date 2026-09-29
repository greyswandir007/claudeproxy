package ru.wizard.web.claudeproxy.usage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Юнит-тесты расчёта границ недельного и месячного лимитов провайдера.
 * Контекст Spring не поднимается.
 */
class ProviderLimitWindowCalculatorTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    private fun millisecondsOf(date: String, time: String = "12:00"): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private fun startOfDayMilliseconds(date: String): Long =
        LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `скользящая неделя считается от текущего момента`() {
        val now = millisecondsOf("2026-09-28", "15:00")
        val withoutMode = ProviderLimitWindowCalculator.weekWindow(null, null, now, zone)
        assertEquals(now - 7 * 86_400_000L, withoutMode.first)
        assertEquals(now, withoutMode.second)
        val slidingMode = ProviderLimitWindowCalculator.weekWindow(
            ProviderLimitWindowCalculator.WEEK_MODE_SLIDING,
            "MONDAY",
            now,
            zone,
        )
        assertEquals(withoutMode, slidingMode)
    }

    @Test
    fun `фиксированная неделя начинается с понедельника`() {
        // Среда 23 сентября 2026 — неделя с понедельника 21-го.
        val now = millisecondsOf("2026-09-23", "15:00")
        val window = ProviderLimitWindowCalculator.weekWindow(
            ProviderLimitWindowCalculator.WEEK_MODE_FIXED_DAY,
            "MONDAY",
            now,
            zone,
        )
        assertEquals(startOfDayMilliseconds("2026-09-21"), window.first)
        assertEquals(startOfDayMilliseconds("2026-09-21") + 7 * 86_400_000L, window.second)
    }

    @Test
    fun `воскресенье относится к неделе с понедельника`() {
        val now = millisecondsOf("2026-09-27", "23:59")
        val window = ProviderLimitWindowCalculator.weekWindow(
            ProviderLimitWindowCalculator.WEEK_MODE_FIXED_DAY,
            "MONDAY",
            now,
            zone,
        )
        assertEquals(startOfDayMilliseconds("2026-09-21"), window.first)
    }

    @Test
    fun `ночь на понедельник открывает новую неделю`() {
        val now = millisecondsOf("2026-09-28", "00:30")
        val window = ProviderLimitWindowCalculator.weekWindow(
            ProviderLimitWindowCalculator.WEEK_MODE_FIXED_DAY,
            "MONDAY",
            now,
            zone,
        )
        assertEquals(startOfDayMilliseconds("2026-09-28"), window.first)
    }

    @Test
    fun `фиксированная неделя с другим днём старта`() {
        // Среда 23 сентября 2026, неделя начинается с воскресенья — анкер 20-е.
        val now = millisecondsOf("2026-09-23", "15:00")
        val window = ProviderLimitWindowCalculator.weekWindow(
            ProviderLimitWindowCalculator.WEEK_MODE_FIXED_DAY,
            "SUNDAY",
            now,
            zone,
        )
        assertEquals(startOfDayMilliseconds("2026-09-20"), window.first)
    }

    @Test
    fun `неизвестный день недели даёт понедельник`() {
        val now = millisecondsOf("2026-09-23", "15:00")
        val window = ProviderLimitWindowCalculator.weekWindow(
            ProviderLimitWindowCalculator.WEEK_MODE_FIXED_DAY,
            "POTDAY",
            now,
            zone,
        )
        assertEquals(startOfDayMilliseconds("2026-09-21"), window.first)
    }

    @Test
    fun `скользящий месяц без дня старта`() {
        val now = millisecondsOf("2026-09-20")
        val withoutDay = ProviderLimitWindowCalculator.monthWindow(null, now, zone)
        assertEquals(now - 30 * 86_400_000L, withoutDay.first)
        assertEquals(now, withoutDay.second)
        val outOfRangeDay = ProviderLimitWindowCalculator.monthWindow(0, now, zone)
        assertEquals(withoutDay, outOfRangeDay)
        val tooLargeDay = ProviderLimitWindowCalculator.monthWindow(32, now, zone)
        assertEquals(withoutDay, tooLargeDay)
    }

    @Test
    fun `месяц с днём старта в середине месяца`() {
        val now = millisecondsOf("2026-09-20")
        val window = ProviderLimitWindowCalculator.monthWindow(15, now, zone)
        assertEquals(startOfDayMilliseconds("2026-09-15"), window.first)
        assertEquals(startOfDayMilliseconds("2026-09-15") + 30 * 86_400_000L, window.second)
    }

    @Test
    fun `начало месяца до дня старта берёт предыдущий месяц`() {
        val now = millisecondsOf("2026-03-10")
        val window = ProviderLimitWindowCalculator.monthWindow(15, now, zone)
        assertEquals(startOfDayMilliseconds("2026-02-15"), window.first)
    }

    @Test
    fun `день 31 в феврале клампится к последнему дню`() {
        // 28 февраля уже наступило — анкер это клампнутый 28-й, а не 31 января
        val inFebruary = ProviderLimitWindowCalculator.monthWindow(
            31,
            millisecondsOf("2027-02-28", "15:00"),
            zone,
        )
        assertEquals(startOfDayMilliseconds("2027-02-28"), inFebruary.first)
        // 20 февраля — клампнутый 28-й ещё впереди, берётся 31 января
        val beforeClampedDay = ProviderLimitWindowCalculator.monthWindow(
            31,
            millisecondsOf("2027-02-20"),
            zone,
        )
        assertEquals(startOfDayMilliseconds("2027-01-31"), beforeClampedDay.first)
        val marchFirst = ProviderLimitWindowCalculator.monthWindow(
            31,
            millisecondsOf("2027-03-01"),
            zone,
        )
        assertEquals(startOfDayMilliseconds("2027-02-28"), marchFirst.first)
    }

    @Test
    fun `день 31 в 30-дневном месяце берёт предыдущий месяц`() {
        val window = ProviderLimitWindowCalculator.monthWindow(31, millisecondsOf("2026-09-28"), zone)
        assertEquals(startOfDayMilliseconds("2026-08-31"), window.first)
    }
}
