package tech.mmarca.openvitals.domain.model

import androidx.health.connect.client.records.ExerciseSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseSensorCapabilitiesTest {

    @Test
    fun `a workout reads only the sensors that fit it`() {
        // A strength session next to a powered-on bike once recorded its speed and cadence.
        assertEquals(
            setOf(BleSensorCapability.HEART_RATE),
            sensorCapabilitiesForExercise(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING),
        )
        assertEquals(
            BleSensorCapability.entries.toSet() - BleSensorCapability.RUNNING_SPEED_CADENCE,
            sensorCapabilitiesForExercise(ExerciseSessionRecord.EXERCISE_TYPE_BIKING),
        )
        assertEquals(
            setOf(BleSensorCapability.HEART_RATE, BleSensorCapability.RUNNING_SPEED_CADENCE),
            sensorCapabilitiesForExercise(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING),
        )
    }
}
