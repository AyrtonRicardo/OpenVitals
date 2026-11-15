package tech.mmarca.openvitals.domain.query

import tech.mmarca.healthcalculations.cycle.CycleStatistics
import tech.mmarca.healthcalculations.model.BasalBodyTemperatureEntry
import tech.mmarca.healthcalculations.model.CycleData
import tech.mmarca.healthcalculations.model.CycleJournalEntry
import tech.mmarca.healthcalculations.model.SkinTemperatureEntry

data class CyclePeriodData(
    val data: CycleData,
    val missingPermissions: Set<String>,
    val statistics: CycleStatistics? = null,
    /** The journal rows inside the loaded period. */
    val journalEntries: List<CycleJournalEntry> = emptyList(),
    /** Every journal row, for today's card, the tips and the symptom patterns. The table is small. */
    val allJournalEntries: List<CycleJournalEntry> = emptyList(),
    /** Morning temperatures since the current cycle started, for the thermal chart. */
    val currentCycleTemperatures: List<BasalBodyTemperatureEntry> = emptyList(),
    /** Night skin temperature since the current cycle started. It feeds the retrospective shift line only, never an estimate. */
    val currentCycleSkinTemperature: List<SkinTemperatureEntry> = emptyList(),
)
