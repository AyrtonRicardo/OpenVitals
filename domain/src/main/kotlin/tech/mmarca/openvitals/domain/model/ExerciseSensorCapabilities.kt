package tech.mmarca.openvitals.domain.model

import tech.mmarca.healthcalculations.model.cyclingExercises
import tech.mmarca.healthcalculations.model.stepBasedExercises

/**
 * The sensors a kind of exercise reads: heart rate always, the bike sensors for
 * cycling, a foot pod for strides. A strength session next to a powered-on bike
 * once recorded its speed and cadence.
 */
fun sensorCapabilitiesForExercise(exerciseType: Int): Set<BleSensorCapability> = buildSet {
    add(BleSensorCapability.HEART_RATE)
    if (exerciseType in cyclingExercises) {
        add(BleSensorCapability.CYCLING_CADENCE)
        add(BleSensorCapability.CYCLING_POWER)
        add(BleSensorCapability.CYCLING_SPEED_DISTANCE)
    }
    if (exerciseType in stepBasedExercises) {
        add(BleSensorCapability.RUNNING_SPEED_CADENCE)
    }
}
