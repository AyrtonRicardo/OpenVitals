package tech.mmarca.openvitals.features.manualentry.circumference

import tech.mmarca.openvitals.domain.model.circumferenceAccepts
import tech.mmarca.openvitals.domain.preferences.UnitSystem

private const val CentimetersPerInch = 2.54

/**
 * The centimetres a typed value stands for, in the height setting's unit, or null when it
 * is not a number or not a value a tape could read. A comma works as the decimal separator.
 */
internal fun circumferenceCentimetersOrNull(input: String, unitSystem: UnitSystem): Double? {
    val value = input.trim().replace(',', '.').toDoubleOrNull() ?: return null
    val centimeters = if (unitSystem == UnitSystem.IMPERIAL) value * CentimetersPerInch else value
    return centimeters.takeIf(::circumferenceAccepts)
}

internal fun circumferenceInputUnitLabel(unitSystem: UnitSystem): String =
    if (unitSystem == UnitSystem.IMPERIAL) "in" else "cm"
