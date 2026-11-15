package tech.mmarca.openvitals.features.activity

import tech.mmarca.healthcalculations.model.ExerciseData
import tech.mmarca.healthcalculations.model.movingDurationMs
import tech.mmarca.openvitals.core.presentation.DisplayValue
import tech.mmarca.openvitals.core.presentation.UnitFormatter

internal fun ExerciseData.averageSpeed(unitFormatter: UnitFormatter): DisplayValue? {
    val distanceMeters = totalDistanceMeters?.takeIf { it > 0.0 } ?: return null
    val movingDurationMs = movingDurationMs().takeIf { it > 0L } ?: return null
    return unitFormatter.averageSpeed(distanceMeters, movingDurationMs)
}

internal fun ExerciseData.averagePace(unitFormatter: UnitFormatter): DisplayValue? {
    val distanceMeters = totalDistanceMeters?.takeIf { it > 0.0 } ?: return null
    val movingDurationMs = movingDurationMs().takeIf { it > 0L } ?: return null
    return unitFormatter.averagePace(distanceMeters, movingDurationMs)
}
