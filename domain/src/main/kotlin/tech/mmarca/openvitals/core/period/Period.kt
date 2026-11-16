package tech.mmarca.openvitals.core.period

enum class TimeRange(val label: String, val days: Int) {
    DAY("Day", 1),
    WEEK("Week", 7),
    MONTH("Month", 30),
    YEAR("Year", 365),
}

enum class WeekPeriodMode {
    MONDAY_TO_SUNDAY,
    LAST_7_DAYS,
}

fun WeekPeriodMode.usesRollingDates(): Boolean = this == WeekPeriodMode.LAST_7_DAYS
