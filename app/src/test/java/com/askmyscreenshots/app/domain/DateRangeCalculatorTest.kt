package com.askmyscreenshots.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DateRangeCalculatorTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val clock = Clock.fixed(Instant.parse("2026-06-08T10:00:00Z"), zone)

    @Test
    fun todayUsesLocalDayBoundaries() {
        val range = DateRangeCalculator.calculate(DateRangePreset.TODAY, clock)

        assertEquals(millis("2026-06-08"), range.startMillis)
        assertEquals(millis("2026-06-09"), range.endMillisExclusive)
    }

    @Test
    fun lastSevenDaysIncludesTodayAndPreviousSixDays() {
        val range = DateRangeCalculator.calculate(DateRangePreset.LAST_7_DAYS, clock)

        assertEquals(millis("2026-06-02"), range.startMillis)
        assertEquals(millis("2026-06-09"), range.endMillisExclusive)
    }

    @Test
    fun lastMonthUsesTheCompletePreviousCalendarMonth() {
        val range = DateRangeCalculator.calculate(DateRangePreset.LAST_MONTH, clock)

        assertEquals(millis("2026-05-01"), range.startMillis)
        assertEquals(millis("2026-06-01"), range.endMillisExclusive)
    }

    @Test
    fun customRangeOrdersDatesWhenSelectedBackwards() {
        val range = DateRangeCalculator.custom(
            start = LocalDate.parse("2026-06-08"),
            endInclusive = LocalDate.parse("2026-06-03"),
            zone = zone,
        )

        assertEquals(millis("2026-06-03"), range.startMillis)
        assertEquals(millis("2026-06-09"), range.endMillisExclusive)
    }

    private fun millis(date: String): Long {
        return LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

