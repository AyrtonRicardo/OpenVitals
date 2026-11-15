package tech.mmarca.openvitals.sharing

import java.time.Duration
import java.time.Instant
import org.json.JSONObject
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup

/** What one app has asked for. [groups] holds the last read of each group. */
data class DataSharingAppAccess(
    val packageName: String,
    val firstAccess: Instant,
    val lastAccess: Instant,
    val groups: Map<DataSharingGroup, Instant>,
)

/** The record of which apps asked for what, as shown in Settings. Pure, so the JVM tests reach it. */
object DataSharingAccessLog {
    /** Repeat queries within this window do not rewrite the file. */
    val WriteInterval: Duration = Duration.ofMinutes(1)

    /** The oldest apps drop out past this. */
    const val MAX_APPS = 50

    /**
     * The log with [packageName]'s visit added, or null when nothing worth writing changed.
     * A null [group] is a catalog query: it lists the app without a group.
     */
    fun record(
        log: Map<String, DataSharingAppAccess>,
        packageName: String,
        group: DataSharingGroup?,
        now: Instant,
    ): Map<String, DataSharingAppAccess>? {
        val existing = log[packageName]
        val lastForTarget = if (group == null) existing?.lastAccess else existing?.groups?.get(group)
        if (lastForTarget != null && Duration.between(lastForTarget, now) < WriteInterval) return null

        val updated = DataSharingAppAccess(
            packageName = packageName,
            firstAccess = existing?.firstAccess ?: now,
            lastAccess = now,
            groups = if (group == null) existing?.groups.orEmpty() else existing?.groups.orEmpty() + (group to now),
        )
        return (log + (packageName to updated))
            .values
            .sortedByDescending { it.lastAccess }
            .take(MAX_APPS)
            .associateBy { it.packageName }
    }

    fun encode(log: Map<String, DataSharingAppAccess>): String {
        val root = JSONObject()
        log.values.forEach { access ->
            val groups = JSONObject()
            access.groups.forEach { (group, at) -> groups.put(group.name, at.toEpochMilli()) }
            root.put(
                access.packageName,
                JSONObject()
                    .put(KEY_FIRST, access.firstAccess.toEpochMilli())
                    .put(KEY_LAST, access.lastAccess.toEpochMilli())
                    .put(KEY_GROUPS, groups),
            )
        }
        return root.toString()
    }

    /** A damaged file reads as an empty log; unknown group names are dropped. */
    fun decode(json: String?): Map<String, DataSharingAppAccess> {
        if (json.isNullOrBlank()) return emptyMap()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        return root.keys().asSequence().mapNotNull { packageName ->
            val entry = root.optJSONObject(packageName) ?: return@mapNotNull null
            val groupsJson = entry.optJSONObject(KEY_GROUPS)
            val groups = groupsJson?.keys()?.asSequence()?.mapNotNull { name ->
                val group = DataSharingGroup.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
                group to Instant.ofEpochMilli(groupsJson.getLong(name))
            }?.toMap().orEmpty()
            DataSharingAppAccess(
                packageName = packageName,
                firstAccess = Instant.ofEpochMilli(entry.optLong(KEY_FIRST)),
                lastAccess = Instant.ofEpochMilli(entry.optLong(KEY_LAST)),
                groups = groups,
            )
        }.associateBy { it.packageName }
    }

    private const val KEY_FIRST = "first"
    private const val KEY_LAST = "last"
    private const val KEY_GROUPS = "groups"
}
