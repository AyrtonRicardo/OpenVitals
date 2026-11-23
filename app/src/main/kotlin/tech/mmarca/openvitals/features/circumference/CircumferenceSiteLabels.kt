package tech.mmarca.openvitals.features.circumference

import androidx.annotation.StringRes
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.domain.model.CircumferenceSite

/** The part's name, side included, as the figure's tiles, the entry form and the dashboard card show it. */
@StringRes
fun CircumferenceSite.titleRes(): Int = when (this) {
    CircumferenceSite.NECK -> R.string.circumference_site_neck
    CircumferenceSite.SHOULDERS -> R.string.circumference_site_shoulders
    CircumferenceSite.CHEST -> R.string.circumference_site_chest
    CircumferenceSite.WAIST -> R.string.circumference_site_waist
    CircumferenceSite.HIPS -> R.string.circumference_site_hips
    CircumferenceSite.RIGHT_UPPER_ARM -> R.string.circumference_site_right_upper_arm
    CircumferenceSite.LEFT_UPPER_ARM -> R.string.circumference_site_left_upper_arm
    CircumferenceSite.RIGHT_FOREARM -> R.string.circumference_site_right_forearm
    CircumferenceSite.LEFT_FOREARM -> R.string.circumference_site_left_forearm
    CircumferenceSite.RIGHT_THIGH -> R.string.circumference_site_right_thigh
    CircumferenceSite.LEFT_THIGH -> R.string.circumference_site_left_thigh
    CircumferenceSite.RIGHT_CALF -> R.string.circumference_site_right_calf
    CircumferenceSite.LEFT_CALF -> R.string.circumference_site_left_calf
}
