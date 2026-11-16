package tech.mmarca.openvitals.sharing

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup

class DataSharingAccessLogTest {
    private val start = Instant.parse("2026-10-02T08:00:00Z")

    @Test
    fun `a first visit lists the app and the group`() {
        val log = DataSharingAccessLog.record(emptyMap(), "com.example", DataSharingGroup.SLEEP, start)!!

        val entry = log.getValue("com.example")
        assertThat(entry.firstAccess).isEqualTo(start)
        assertThat(entry.lastAccess).isEqualTo(start)
        assertThat(entry.groups).containsExactly(DataSharingGroup.SLEEP, start)
    }

    @Test
    fun `a catalog visit lists the app without a group`() {
        val log = DataSharingAccessLog.record(emptyMap(), "com.example", null, start)!!

        assertThat(log.getValue("com.example").groups).isEmpty()
    }

    @Test
    fun `a repeat within a minute writes nothing`() {
        val log = DataSharingAccessLog.record(emptyMap(), "com.example", DataSharingGroup.SLEEP, start)!!

        assertThat(DataSharingAccessLog.record(log, "com.example", DataSharingGroup.SLEEP, start.plusSeconds(59))).isNull()
        // Another group is news, even within the minute.
        assertThat(DataSharingAccessLog.record(log, "com.example", DataSharingGroup.HEART, start.plusSeconds(1))).isNotNull()
    }

    @Test
    fun `a later visit keeps the first time and earlier groups`() {
        val first = DataSharingAccessLog.record(emptyMap(), "com.example", DataSharingGroup.SLEEP, start)!!
        val later = start.plusSeconds(3_600)

        val entry = DataSharingAccessLog.record(first, "com.example", DataSharingGroup.HEART, later)!!.getValue("com.example")

        assertThat(entry.firstAccess).isEqualTo(start)
        assertThat(entry.lastAccess).isEqualTo(later)
        assertThat(entry.groups).containsExactly(DataSharingGroup.SLEEP, start, DataSharingGroup.HEART, later)
    }

    @Test
    fun `the oldest apps drop out past the cap`() {
        var log = emptyMap<String, DataSharingAppAccess>()
        repeat(DataSharingAccessLog.MAX_APPS + 1) { index ->
            log = DataSharingAccessLog.record(log, "app$index", null, start.plusSeconds(index.toLong()))!!
        }

        assertThat(log).hasSize(DataSharingAccessLog.MAX_APPS)
        assertThat(log).doesNotContainKey("app0")
    }

    @Test
    fun `encode and decode round-trip`() {
        val log = DataSharingAccessLog.record(emptyMap(), "com.example", DataSharingGroup.CYCLE, start)!!

        assertThat(DataSharingAccessLog.decode(DataSharingAccessLog.encode(log))).isEqualTo(log)
    }

    @Test
    fun `a damaged file or an unknown group reads safely`() {
        assertThat(DataSharingAccessLog.decode("not json")).isEmpty()
        assertThat(DataSharingAccessLog.decode(null)).isEmpty()

        val decoded = DataSharingAccessLog.decode(
            """{"com.example":{"first":1,"last":2,"groups":{"SLEEP":2,"REMOVED_GROUP":2}}}""",
        )

        assertThat(decoded.getValue("com.example").groups.keys).containsExactly(DataSharingGroup.SLEEP)
    }
}
