package tech.mmarca.openvitals.sharing.contract

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertThrows
import org.junit.Test

class DataSharingRequestParserTest {

    @Test
    fun `the catalog paths need no group`() {
        assertThat(parse("groups")).isEqualTo(DataSharingRequest.Groups)
        assertThat(parse("metrics")).isEqualTo(DataSharingRequest.Metrics)
        assertThat(DataSharingRequest.Groups.group).isNull()
    }

    @Test
    fun `daily takes a day range and an optional metric list`() {
        val request = parse("sleep/daily", "start" to "2026-09-01", "end" to "2026-09-30", "metric" to "sleep_score, waso")

        assertThat(request).isEqualTo(
            DataSharingRequest.Daily(
                group = DataSharingGroup.SLEEP,
                start = LocalDate.of(2026, 9, 1),
                end = LocalDate.of(2026, 9, 30),
                metrics = setOf("sleep_score", "waso"),
            ),
        )
    }

    @Test
    fun `every group but routes has a daily table`() {
        DataSharingGroup.entries.filter { it != DataSharingGroup.ROUTES }.forEach { group ->
            val request = parse("${group.path}/daily", "start" to "2026-09-01", "end" to "2026-09-01")
            assertThat(request.group).isEqualTo(group)
        }
    }

    @Test
    fun `samples take epoch milliseconds`() {
        val request = parse("heart/samples", "start" to "1000", "end" to "2000")

        assertThat(request).isEqualTo(
            DataSharingRequest.Samples(
                group = DataSharingGroup.HEART,
                start = Instant.ofEpochMilli(1000),
                end = Instant.ofEpochMilli(2000),
                metrics = emptySet(),
            ),
        )
    }

    @Test
    fun `sessions, one session and splits`() {
        assertThat(parse("workouts/sessions", "start" to "2026-09-01", "end" to "2026-09-02"))
            .isEqualTo(DataSharingRequest.Sessions(DataSharingGroup.WORKOUTS, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2)))
        assertThat(parse("sleep/sessions/abc-123"))
            .isEqualTo(DataSharingRequest.Session(DataSharingGroup.SLEEP, "abc-123"))
        assertThat(parse("workouts/sessions/abc-123/splits"))
            .isEqualTo(DataSharingRequest.Splits("abc-123"))
    }

    @Test
    fun `a route needs the routes group, not workouts`() {
        val request = parse("routes/abc-123")

        assertThat(request).isEqualTo(DataSharingRequest.Route("abc-123"))
        assertThat(request.group).isEqualTo(DataSharingGroup.ROUTES)
    }

    @Test
    fun `the group comes from the first segment only`() {
        // A path-permission matches by prefix, so the parser must never read a group from deeper down.
        assertFails("activity/../sleep/daily", "start" to "2026-09-01", "end" to "2026-09-01")
        assertFails("activity/sleep/daily", "start" to "2026-09-01", "end" to "2026-09-01")
    }

    @Test
    fun `unknown paths fail`() {
        assertFails("")
        assertFails("passwords")
        assertFails("groups/extra")
        assertFails("heart/sessions", "start" to "2026-09-01", "end" to "2026-09-01")
        assertFails("sleep/sessions/abc/splits")
        assertFails("routes")
        assertFails("routes/abc/extra")
    }

    @Test
    fun `day ranges are checked`() {
        assertFails("sleep/daily", "start" to "2026-09-01")
        assertFails("sleep/daily", "start" to "2026-02-30", "end" to "2026-03-01")
        assertFails("sleep/daily", "start" to "2026-09-02", "end" to "2026-09-01")
        // 366 days is the most; 367 fails.
        parse("sleep/daily", "start" to "2025-01-01", "end" to "2026-01-01")
        assertFails("sleep/daily", "start" to "2025-01-01", "end" to "2026-01-02")
    }

    @Test
    fun `sample ranges are checked`() {
        val hour = 3_600_000L
        parse("heart/samples", "start" to "0", "end" to "${48 * hour}")
        assertFails("heart/samples", "start" to "0", "end" to "${48 * hour + 1}")
        assertFails("heart/samples", "start" to "1000", "end" to "1000")
        assertFails("heart/samples", "start" to "yesterday", "end" to "1000")
    }

    @Test
    fun `a metric the table does not offer fails`() {
        assertFails("sleep/daily", "start" to "2026-09-01", "end" to "2026-09-01", "metric" to "heart_rate")
        // sleep_stage is a sample, not a daily value.
        assertFails("sleep/daily", "start" to "2026-09-01", "end" to "2026-09-01", "metric" to "sleep_stage")
        assertFails("sleep/daily", "start" to "2026-09-01", "end" to "2026-09-01", "metric" to "Sleep_Score")
    }

    private fun parse(path: String, vararg parameters: Pair<String, String>): DataSharingRequest {
        val map = parameters.toMap()
        return DataSharingRequestParser.parse(path.split('/').filter { it.isNotEmpty() }) { map[it] }
    }

    private fun assertFails(path: String, vararg parameters: Pair<String, String>) {
        assertThrows(IllegalArgumentException::class.java) { parse(path, *parameters) }
    }
}
