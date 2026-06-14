package com.askmyscreenshots.app.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

enum class DateRangePreset(val label: String) {
    TODAY("Today"),
    LAST_7_DAYS("Last 7 days"),
    LAST_30_DAYS("Last 30 days"),
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    LAST_6_MONTHS("Last 6 months"),
    CUSTOM("Custom range"),
}

data class DateRange(
    val preset: DateRangePreset,
    val startMillis: Long,
    val endMillisExclusive: Long,
    val displayLabel: String,
)

object DateRangeCalculator {
    fun calculate(
        preset: DateRangePreset,
        clock: Clock = Clock.systemDefaultZone(),
    ): DateRange {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        return when (preset) {
            DateRangePreset.TODAY -> fromDates(
                preset = preset,
                start = today,
                endInclusive = today,
                zone = zone,
            )

            DateRangePreset.LAST_7_DAYS -> fromDates(
                preset = preset,
                start = today.minusDays(6),
                endInclusive = today,
                zone = zone,
            )

            DateRangePreset.LAST_30_DAYS -> fromDates(
                preset = preset,
                start = today.minusDays(29),
                endInclusive = today,
                zone = zone,
            )

            DateRangePreset.THIS_MONTH -> {
                val firstDay = today.withDayOfMonth(1)
                fromDates(preset, firstDay, today, zone)
            }

            DateRangePreset.LAST_MONTH -> {
                val firstDayThisMonth = today.withDayOfMonth(1)
                val firstDayLastMonth = firstDayThisMonth.minusMonths(1)
                fromDates(
                    preset = preset,
                    start = firstDayLastMonth,
                    endInclusive = firstDayThisMonth.minusDays(1),
                    zone = zone,
                )
            }

            DateRangePreset.LAST_6_MONTHS -> fromDates(
                preset = preset,
                start = today.minusMonths(6),
                endInclusive = today,
                zone = zone,
            )

            DateRangePreset.CUSTOM -> fromDates(
                preset = preset,
                start = today,
                endInclusive = today,
                zone = zone,
            )
        }
    }

    fun custom(
        start: LocalDate,
        endInclusive: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): DateRange {
        val orderedStart = minOf(start, endInclusive)
        val orderedEnd = maxOf(start, endInclusive)
        return fromDates(
            preset = DateRangePreset.CUSTOM,
            start = orderedStart,
            endInclusive = orderedEnd,
            zone = zone,
        )
    }

    fun utcPickerMillisToLocalDate(millis: Long): LocalDate {
        return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
    }

    private fun fromDates(
        preset: DateRangePreset,
        start: LocalDate,
        endInclusive: LocalDate,
        zone: ZoneId,
    ): DateRange {
        val startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillisExclusive = endInclusive
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val dayCount = ChronoUnit.DAYS.between(start, endInclusive) + 1
        val label = if (start == endInclusive) {
            "${preset.label} · ${start.toDisplayDate()}"
        } else {
            "${preset.label} · ${start.toDisplayDate()} - ${endInclusive.toDisplayDate()} · ${dayCount}d"
        }
        return DateRange(
            preset = preset,
            startMillis = startMillis,
            endMillisExclusive = endMillisExclusive,
            displayLabel = label,
        )
    }
}

fun LocalDate.toDisplayDate(): String {
    return "${monthValue.toString().padStart(2, '0')}/${dayOfMonth.toString().padStart(2, '0')}/$year"
}

fun LocalDate.toUtcPickerMillis(): Long {
    return atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
