package tech.mmarca.openvitals.features.bodyfolder

import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tech.mmarca.healthcalculations.model.DatePeriod
import tech.mmarca.openvitals.data.repository.contract.BodyCircumferenceRepository
import tech.mmarca.openvitals.data.repository.contract.BodyFolderRepository
import tech.mmarca.openvitals.data.repository.contract.BodyRepository
import tech.mmarca.openvitals.domain.model.CircumferenceSite

/**
 * Writes `measurements.json` to the picked folder: every body record Health Connect lets the app
 * read, and every tape measurement. A body type without a read grant comes back empty from
 * [BodyRepository], so the file then simply has no entries for it.
 */
class BodyMeasurementsExporter(
    private val body: BodyRepository,
    private val circumferences: BodyCircumferenceRepository,
    private val folder: BodyFolderRepository,
    private val today: () -> LocalDate,
    private val now: () -> Instant,
) {
    @Inject
    constructor(
        body: BodyRepository,
        circumferences: BodyCircumferenceRepository,
        folder: BodyFolderRepository,
    ) : this(body, circumferences, folder, LocalDate::now, Instant::now)

    /** Returns how many entries the file holds. */
    suspend fun export(): Int {
        val snapshot = snapshot()
        val document = BodyFolderDocuments.measurements(snapshot, now())
        folder.write(
            directory = null,
            name = BodyFolderDocuments.MeasurementsFileName,
            mimeType = BodyFolderDocuments.JsonMimeType,
            bytes = document.toByteArray(Charsets.UTF_8),
        )
        return snapshot.entryCount
    }

    private suspend fun snapshot(): BodyMeasurementsSnapshot = coroutineScope {
        val end = today()
        val start = end.minusYears(HistoryYears)
        val weight = async { body.loadWeightEntries(start, end) }
        val height = async { body.loadHeightEntries(start, end) }
        val bodyFat = async { body.loadBodyFatEntries(start, end) }
        val leanMass = async { body.loadLeanBodyMassEntries(start, end) }
        val boneMass = async { body.loadBoneMassEntries(start, end) }
        val bodyWater = async { body.loadBodyWaterMassEntries(start, end) }
        val tape = CircumferenceSite.entries
            .map { site -> async { circumferences.entries(site, DatePeriod(start, end)) } }
            .awaitAll()
            .flatten()
        BodyMeasurementsSnapshot(
            weight = weight.await(),
            height = height.await(),
            bodyFat = bodyFat.await(),
            leanMass = leanMass.await(),
            boneMass = boneMass.await(),
            bodyWater = bodyWater.await(),
            circumferences = tape,
        )
    }

    private companion object {
        /** Far enough back for any history a phone holds; Health Connect returns what it has. */
        const val HistoryYears = 30L
    }
}
