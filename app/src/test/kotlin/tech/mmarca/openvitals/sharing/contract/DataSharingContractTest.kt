package tech.mmarca.openvitals.sharing.contract

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DataSharingContractTest {

    @Test
    fun `permission names carry the application id`() {
        assertThat(DataSharingContract.permission("tech.mmarca.openvitals.debug", DataSharingGroup.SEXUAL_ACTIVITY))
            .isEqualTo("tech.mmarca.openvitals.debug.permission.READ_SEXUAL_ACTIVITY")
        assertThat(DataSharingContract.authority("tech.mmarca.openvitals")).isEqualTo("tech.mmarca.openvitals.data")
    }

    @Test
    fun `no group path is a prefix of another`() {
        // Android matches a path-permission by prefix: /activity must not cover /activity_x.
        val paths = DataSharingGroup.entries.map { "/${it.path}" }
        paths.forEach { path ->
            assertThat(paths.filter { it != path && it.startsWith(path) }).isEmpty()
        }
    }

    @Test
    fun `group paths never collide with the catalog paths`() {
        val catalog = setOf(DataSharingContract.PATH_GROUPS, DataSharingContract.PATH_METRICS)
        assertThat(DataSharingGroup.entries.map { it.path }.filter { it in catalog }).isEmpty()
    }

    @Test
    fun `metric keys are unique within a group and well formed`() {
        DataSharingGroup.entries.forEach { group ->
            val keys = DataSharingMetrics.forGroup(group).map { it.key }
            assertThat(keys).containsNoDuplicates()
        }
        DataSharingMetrics.all.forEach { metric ->
            assertThat(metric.key).matches("[a-z0-9_]{1,64}")
            assertThat(metric.tables).isNotEmpty()
        }
    }

    @Test
    fun `session metrics belong to groups with sessions`() {
        DataSharingMetrics.all.filter { DataSharingTable.SESSIONS in it.tables }.forEach { metric ->
            assertThat(metric.group.hasSessions).isTrue()
        }
    }

    @Test
    fun `every group but routes offers metrics`() {
        DataSharingGroup.entries.filter { it != DataSharingGroup.ROUTES }.forEach { group ->
            assertThat(DataSharingMetrics.forGroup(group)).isNotEmpty()
        }
        assertThat(DataSharingMetrics.forGroup(DataSharingGroup.ROUTES)).isEmpty()
    }

    @Test
    fun `free-text journal notes are never offered`() {
        assertThat(DataSharingMetrics.all.map { it.key }).doesNotContain("notes")
    }
}
