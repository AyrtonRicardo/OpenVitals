package tech.mmarca.openvitals.data.local.circumference

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One tape measurement: a site (a `CircumferenceSite` name), the instant it was taken and its
 * length in centimeters. A second measurement of the same site at the same instant replaces it.
 */
@Entity(tableName = "body_circumferences", primaryKeys = ["site", "measured_at_millis"])
data class BodyCircumferenceEntity(
    val site: String,
    @ColumnInfo(name = "measured_at_millis") val measuredAtMillis: Long,
    val centimeters: Double,
)
