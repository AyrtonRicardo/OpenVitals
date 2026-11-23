package tech.mmarca.openvitals.domain.model

import java.time.Instant

/**
 * A place on the body measured with a tape. Health Connect has no record type for any of them,
 * so these live in Room, stored by name. Arms and legs are measured on each side, since people
 * are rarely symmetrical. The order is the order the figure's grid and the export use.
 */
enum class CircumferenceSite {
    NECK,
    SHOULDERS,
    CHEST,
    WAIST,
    HIPS,
    RIGHT_UPPER_ARM,
    LEFT_UPPER_ARM,
    RIGHT_FOREARM,
    LEFT_FOREARM,
    RIGHT_THIGH,
    LEFT_THIGH,
    RIGHT_CALF,
    LEFT_CALF,
}

/** One tape measurement, held in centimetres. A site has at most one value per instant. */
data class CircumferenceEntry(
    val site: CircumferenceSite,
    val time: Instant,
    val centimeters: Double,
)

/** Whether [centimeters] is a value a tape around a person could read. */
fun circumferenceAccepts(centimeters: Double): Boolean =
    centimeters > 0.0 && centimeters <= MaxCircumferenceCm

private const val MaxCircumferenceCm = 300.0
