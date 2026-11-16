package tech.mmarca.openvitals.data.repository

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import tech.mmarca.healthcalculations.insights.BodyEnergyTimelineAlgorithmVersion
import javax.inject.Singleton
import tech.mmarca.healthcalculations.insights.BodyEnergyBucketState
import tech.mmarca.healthcalculations.insights.BodyEnergyCalibrationMode
import tech.mmarca.healthcalculations.insights.BodyEnergyConfidence
import tech.mmarca.healthcalculations.insights.BodyEnergyInputSummary
import tech.mmarca.healthcalculations.insights.BodyEnergyPrimaryInfluence
import tech.mmarca.healthcalculations.insights.BodyEnergySeedSource
import tech.mmarca.healthcalculations.insights.BodyEnergyTimeline
import tech.mmarca.healthcalculations.insights.BodyEnergyTimelinePoint
import tech.mmarca.healthcalculations.insights.bodyEnergyReasonCodeForText
import tech.mmarca.openvitals.data.local.bodyenergy.BodyEnergyBucketEntity
import tech.mmarca.openvitals.data.local.bodyenergy.BodyEnergyBucketRetentionDays
import tech.mmarca.openvitals.data.local.bodyenergy.BodyEnergyChainCursorKey
import tech.mmarca.openvitals.data.local.bodyenergy.BodyEnergyDayEntity
import tech.mmarca.openvitals.data.local.bodyenergy.BodyEnergyTimelineDao

/**
 * How long a past day stays eligible for recomputation. Watches sync late
 * and Health Connect back-fills. Seven days, because a Garmin watch is
 * often synced once a week.
 */
const val BodyEnergyChainSettlingDays = 7L

/**
 * Room-backed storage for the Body Energy chain: day summaries and the
 * 5-minute buckets behind them. Maps rows to [BodyEnergyTimeline] so the
 * repository stays about the chain.
 */
@Singleton
open class BodyEnergyTimelineStore @Inject constructor(
    private val dao: BodyEnergyTimelineDao,
) {

    /** The stored timeline for [date], or null when absent or stored under another [signature]. */
    open suspend fun load(date: LocalDate, signature: String): BodyEnergyTimeline? {
        val day = dao.day(date.toEpochDay()) ?: return null
        if (day.signature != signature) return null
        return day.toTimeline(dao.bucketsForDay(date.toEpochDay()))
    }

    /** Persists [timeline], replacing whatever was stored for its date. */
    open suspend fun save(timeline: BodyEnergyTimeline) {
        // An unsigned timeline cannot be validated on read.
        if (timeline.signature.isBlank()) return
        val epochDay = timeline.date.toEpochDay()
        dao.upsertDay(
            timeline.toDayEntity(epochDay),
            timeline.points.map { it.toBucketEntity(epochDay) },
        )
    }

    /** The chain facts for `[start, end]`, oldest first. One query, no bucket decoding. */
    open suspend fun storedDaysBetween(start: LocalDate, end: LocalDate): List<BodyEnergyStoredDay> =
        dao.daysBetween(start.toEpochDay(), end.toEpochDay()).map { row ->
            BodyEnergyStoredDay(
                date = LocalDate.ofEpochDay(row.epochDay),
                signature = row.signature,
                startScore = row.startScore,
                endScore = row.endScore,
                generatedAt = Instant.ofEpochMilli(row.generatedAtMillis),
            )
        }

    /**
     * The days of `[start, end]` as last computed, for the data sharing API. A day computed by
     * an older algorithm is left out: its numbers are not the ones the app shows any more.
     */
    open suspend fun sharedDaysBetween(start: LocalDate, end: LocalDate): List<BodyEnergySharedDay> =
        dao.daysBetween(start.toEpochDay(), end.toEpochDay())
            .filter { it.algorithmVersion == BodyEnergyTimelineAlgorithmVersion }
            .map { row ->
                BodyEnergySharedDay(
                    date = LocalDate.ofEpochDay(row.epochDay),
                    startScore = row.startScore.toDouble(),
                    endScore = row.endScore.toDouble(),
                    charged = row.charged.toDouble(),
                    drained = row.drained.toDouble(),
                )
            }

    /** The 5-minute scores of those days that fall in `[start, end)`, oldest first. */
    open suspend fun sharedScoresBetween(start: Instant, end: Instant, zone: ZoneId): List<Pair<Instant, Double>> =
        sharedDaysBetween(start.atZone(zone).toLocalDate(), end.atZone(zone).toLocalDate())
            .flatMap { day -> dao.bucketsForDay(day.date.toEpochDay()) }
            .filter { it.timeMillis >= start.toEpochMilli() && it.timeMillis < end.toEpochMilli() }
            .map { Instant.ofEpochMilli(it.timeMillis) to it.score.toDouble() }

    /** Whether a stored day still has buckets. Signature-independent on purpose. */
    open suspend fun hasStoredPoints(date: LocalDate): Boolean =
        dao.countBucketsForDay(date.toEpochDay()) > 0

    /** Forward ripple: drop `[from, to]`, whose seeds no longer hold. */
    open suspend fun invalidateForward(from: LocalDate, to: LocalDate) {
        dao.deleteDays(from.toEpochDay(), to.toEpochDay())
    }

    /** Drops the whole chain and its cursor — the calibration/algorithm reset. */
    open suspend fun purgeAll() {
        dao.purgeAll()
    }

    /** Drops buckets older than [BodyEnergyBucketRetentionDays], keeping the summaries. */
    open suspend fun applyRetention(today: LocalDate) {
        dao.purgeBucketsBefore(today.minusDays(BodyEnergyBucketRetentionDays).toEpochDay())
    }

    /** The global signature the stored chain was built under, or null. */
    open suspend fun storedGlobalSignature(): String? =
        dao.cursor(BodyEnergyChainCursorKey)?.changesToken

    open suspend fun writeGlobalSignature(signature: String) {
        dao.writeChainCursor(globalSignature = signature, lastPassMillis = null)
    }

    open suspend fun lastPassAt(): Instant? =
        dao.cursor(BodyEnergyChainCursorKey)?.lastFullSyncMillis?.let(Instant::ofEpochMilli)

    open suspend fun writeLastPassAt(at: Instant) {
        dao.writeChainCursor(globalSignature = null, lastPassMillis = at.toEpochMilli())
    }
}

/** A stored day as another app is given it. */
data class BodyEnergySharedDay(
    val date: LocalDate,
    val startScore: Double,
    val endScore: Double,
    val charged: Double,
    val drained: Double,
)

/** A stored day's chain facts. Not a timeline: the walk-back must not pay a bucket read per day. */
data class BodyEnergyStoredDay(
    val date: LocalDate,
    val signature: String,
    val startScore: Int,
    val endScore: Int,
    val generatedAt: Instant,
)

private fun BodyEnergyDayEntity.toTimeline(
    buckets: List<BodyEnergyBucketEntity>,
): BodyEnergyTimeline =
    BodyEnergyTimeline(
        date = LocalDate.ofEpochDay(epochDay),
        startScore = startScore,
        currentScore = endScore,
        charged = charged,
        drained = drained,
        points = buckets.map { it.toPoint() },
        confidence = confidence.toEnumOrNull<BodyEnergyConfidence>() ?: BodyEnergyConfidence.NO_DATA,
        confidenceReason = confidenceReason,
        // Rows from before reason codes carry only the sentence; unrecognised stays LEGACY.
        confidenceReasonCode = bodyEnergyReasonCodeForText(confidenceReason),
        inputSummary = BodyEnergyInputSummary(
            algorithmVersion = algorithmVersion,
            bucketMinutes = bucketMinutes,
            heartRateSampleCount = heartRateSampleCount,
            hrvSampleCount = hrvSampleCount,
            sleepSessionCount = sleepSessionCount,
            workoutCount = workoutCount,
            respiratorySampleCount = respiratorySampleCount,
            hasRestingHeartRate = hasRestingHeartRate,
            hasBaselineRestingHeartRate = hasBaselineRestingHeartRate,
            hasObservedMaxHeartRate = hasObservedMaxHeartRate,
            hasHrvBaseline = hasHrvBaseline,
            hasRespiratoryBaseline = hasRespiratoryBaseline,
            previousEndScore = previousEndScore,
            carryOverFloorApplied = carryOverFloorApplied,
            seedSource = seedSource.toEnumOrNull<BodyEnergySeedSource>() ?: BodyEnergySeedSource.NEUTRAL,
            calibrationMode = calibrationMode.toEnumOrNull<BodyEnergyCalibrationMode>() ?: BodyEnergyCalibrationMode.AUTOMATIC,
        ),
        generatedAt = Instant.ofEpochMilli(generatedAtMillis),
        signature = signature,
    )

private fun BodyEnergyBucketEntity.toPoint(): BodyEnergyTimelinePoint =
    BodyEnergyTimelinePoint(
        time = Instant.ofEpochMilli(timeMillis),
        score = score,
        delta = delta,
        state = state.toEnumOrNull<BodyEnergyBucketState>() ?: BodyEnergyBucketState.UNMEASURABLE,
        confidence = confidence.toEnumOrNull<BodyEnergyConfidence>() ?: BodyEnergyConfidence.NO_DATA,
        charge = charge,
        intensityDrain = intensityDrain,
        activityEnergyDrain = activityEnergyDrain,
        basalDrain = basalDrain,
        stressDrain = stressDrain,
        recoveryDebtDrain = recoveryDebtDrain,
        primaryInfluence = primaryInfluence.toEnumOrNull<BodyEnergyPrimaryInfluence>() ?: BodyEnergyPrimaryInfluence.STEADY,
    )

private fun BodyEnergyTimeline.toDayEntity(epochDay: Long): BodyEnergyDayEntity =
    BodyEnergyDayEntity(
        epochDay = epochDay,
        signature = signature,
        startScore = startScore,
        endScore = currentScore,
        charged = charged,
        drained = drained,
        confidence = confidence.name,
        confidenceReason = confidenceReason,
        generatedAtMillis = generatedAt.toEpochMilli(),
        algorithmVersion = inputSummary.algorithmVersion,
        bucketMinutes = inputSummary.bucketMinutes,
        heartRateSampleCount = inputSummary.heartRateSampleCount,
        hrvSampleCount = inputSummary.hrvSampleCount,
        sleepSessionCount = inputSummary.sleepSessionCount,
        workoutCount = inputSummary.workoutCount,
        respiratorySampleCount = inputSummary.respiratorySampleCount,
        hasRestingHeartRate = inputSummary.hasRestingHeartRate,
        hasBaselineRestingHeartRate = inputSummary.hasBaselineRestingHeartRate,
        hasObservedMaxHeartRate = inputSummary.hasObservedMaxHeartRate,
        hasHrvBaseline = inputSummary.hasHrvBaseline,
        hasRespiratoryBaseline = inputSummary.hasRespiratoryBaseline,
        previousEndScore = inputSummary.previousEndScore,
        carryOverFloorApplied = inputSummary.carryOverFloorApplied,
        seedSource = inputSummary.seedSource.name,
        calibrationMode = inputSummary.calibrationMode.name,
    )

private fun BodyEnergyTimelinePoint.toBucketEntity(epochDay: Long): BodyEnergyBucketEntity =
    BodyEnergyBucketEntity(
        epochDay = epochDay,
        timeMillis = time.toEpochMilli(),
        score = score,
        delta = delta,
        state = state.name,
        confidence = confidence.name,
        charge = charge,
        intensityDrain = intensityDrain,
        activityEnergyDrain = activityEnergyDrain,
        basalDrain = basalDrain,
        stressDrain = stressDrain,
        recoveryDebtDrain = recoveryDebtDrain,
        primaryInfluence = primaryInfluence.name,
    )

private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
    runCatching { enumValueOf<T>(this) }.getOrNull()
