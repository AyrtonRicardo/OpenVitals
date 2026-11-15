package tech.mmarca.openvitals.sharing.source

import java.time.LocalDate
import tech.mmarca.healthcalculations.model.CycleJournalEntry
import tech.mmarca.openvitals.sharing.contract.DataSharingOrigin

/**
 * The journal's own fields, one row each. Levels stay 1-5; lists become sorted,
 * comma-separated names. Free-text notes are never shared.
 */
internal fun journalDailyRows(entries: List<CycleJournalEntry>): List<DailyRow> = entries.flatMap { entry ->
    fun level(metric: String, value: Int?) = value?.let { DailyRow(entry.date, metric, it.toDouble(), origin = Own) }
    fun text(metric: String, value: String?) = value?.takeIf { it.isNotEmpty() }?.let {
        DailyRow(entry.date, metric, text = it, origin = Own)
    }
    listOfNotNull(
        level("mood", entry.moodLevel),
        level("energy", entry.energyLevel),
        level("pain", entry.painLevel),
        text("symptoms", entry.symptoms.map { it.id }.sorted().joinToString(",")),
        text("journal_cervical_sensation", entry.cervicalSensation?.name?.lowercase()),
        text("hcg_test", entry.hcgTest?.name?.lowercase()),
        text("bbt_disturbances", entry.bbtDisturbances.map { it.name.lowercase() }.sorted().joinToString(",")),
    )
}

internal fun pillDailyRows(takenDays: Set<LocalDate>): List<DailyRow> =
    takenDays.map { DailyRow(it, "pill_taken", 1.0, origin = Own) }

private val Own = DataSharingOrigin.OPENVITALS
