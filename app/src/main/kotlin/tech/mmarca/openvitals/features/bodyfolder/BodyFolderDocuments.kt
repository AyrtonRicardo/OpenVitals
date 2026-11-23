package tech.mmarca.openvitals.features.bodyfolder

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import tech.mmarca.openvitals.domain.model.BodyFatEntry
import tech.mmarca.openvitals.domain.model.BodyWaterMassEntry
import tech.mmarca.openvitals.domain.model.BoneMassEntry
import tech.mmarca.openvitals.domain.model.CircumferenceEntry
import tech.mmarca.openvitals.domain.model.HeightEntry
import tech.mmarca.openvitals.domain.model.LeanBodyMassEntry
import tech.mmarca.openvitals.domain.model.ProgressPhoto
import tech.mmarca.openvitals.domain.model.ProgressPhotoFolderName
import tech.mmarca.openvitals.domain.model.WeightEntry

/** Everything `measurements.json` holds: the body records from Health Connect and the tape measurements. */
data class BodyMeasurementsSnapshot(
    val weight: List<WeightEntry> = emptyList(),
    val height: List<HeightEntry> = emptyList(),
    val bodyFat: List<BodyFatEntry> = emptyList(),
    val leanMass: List<LeanBodyMassEntry> = emptyList(),
    val boneMass: List<BoneMassEntry> = emptyList(),
    val bodyWater: List<BodyWaterMassEntry> = emptyList(),
    val circumferences: List<CircumferenceEntry> = emptyList(),
) {
    val entryCount: Int
        get() = weight.size + height.size + bodyFat.size + leanMass.size + boneMass.size + bodyWater.size +
            circumferences.size
}

/**
 * The two files the web dashboard reads from the picked folder. Their shape is a contract with
 * `tools/body-dashboard`: a change here bumps [FormatVersion] and changes the dashboard with it.
 * Times are UTC instants; `date` is the phone's local day, so a photo meets the day it was taken.
 * Masses are kilograms, lengths centimetres, body fat a percentage, rounded to two decimals.
 */
object BodyFolderDocuments {
    const val MeasurementsFileName = "measurements.json"
    const val PhotosFileName = "photos.json"
    const val JsonMimeType = "application/json"
    const val FormatVersion = 1

    private val json = Json { prettyPrint = true }

    fun measurements(snapshot: BodyMeasurementsSnapshot, exportedAt: Instant): String =
        json.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                header(format = "openvitals-body-measurements", exportedAt = exportedAt)
                putJsonObject("units") {
                    put("mass", "kg")
                    put("length", "cm")
                    put("bodyFat", "percent")
                }
                put("weight", series(snapshot.weight, { it.time }) { put("kg", rounded(it.weightKg)) })
                put("height", series(snapshot.height, { it.time }) { put("cm", rounded(it.heightCm)) })
                put("bodyFat", series(snapshot.bodyFat, { it.time }) { put("percent", rounded(it.percent)) })
                put("leanMass", series(snapshot.leanMass, { it.time }) { put("kg", rounded(it.massKg)) })
                put("boneMass", series(snapshot.boneMass, { it.time }) { put("kg", rounded(it.massKg)) })
                put("bodyWater", series(snapshot.bodyWater, { it.time }) { put("kg", rounded(it.massKg)) })
                put(
                    "circumferences",
                    series(snapshot.circumferences, { it.time }) {
                        put("site", it.site.name.lowercase())
                        put("cm", rounded(it.centimeters))
                    },
                )
            },
        )

    /** Only the photos already in the folder: a pending one has no file there to open. */
    fun photos(photos: List<ProgressPhoto>, zone: ZoneId, exportedAt: Instant): String =
        json.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                header(format = "openvitals-progress-photos", exportedAt = exportedAt)
                put(
                    "photos",
                    series(photos.filter { it.saved }, { it.takenAt }) {
                        put("date", it.takenAt.atZone(zone).toLocalDate().toString())
                        put("file", "$ProgressPhotoFolderName/${it.fileName}")
                    },
                )
            },
        )

    private fun JsonObjectBuilder.header(format: String, exportedAt: Instant) {
        put("format", format)
        put("version", FormatVersion)
        put("exportedAt", exportedAt.toString())
    }

    private fun <T> series(
        entries: List<T>,
        time: (T) -> Instant,
        fields: JsonObjectBuilder.(T) -> Unit,
    ): JsonArray = buildJsonArray {
        entries.sortedBy(time).forEach { entry ->
            add(
                buildJsonObject {
                    put("time", time(entry).toString())
                    fields(entry)
                },
            )
        }
    }

    private fun rounded(value: Double): Double = (value * 100).roundToLong() / 100.0
}
