package tech.mmarca.openvitals.sharing

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup

/** The sharing switch and the access log, in a preference file of their own. */
@Singleton
class DataSharingStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Off until the user turns it on: the app is offline-only, and sharing lets data leave through another app. */
    var sharingEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    fun accessLog(): List<DataSharingAppAccess> =
        DataSharingAccessLog.decode(prefs.getString(KEY_ACCESS_LOG, null))
            .values
            .sortedByDescending { it.lastAccess }

    /** Called from binder threads, so the read-modify-write is serialized. */
    @Synchronized
    fun recordAccess(packageName: String, group: DataSharingGroup?, now: Instant = Instant.now()) {
        val log = DataSharingAccessLog.decode(prefs.getString(KEY_ACCESS_LOG, null))
        val updated = DataSharingAccessLog.record(log, packageName, group, now) ?: return
        prefs.edit { putString(KEY_ACCESS_LOG, DataSharingAccessLog.encode(updated)) }
    }

    /** Removes an app from the list. Its permissions stay as they are in Android. */
    @Synchronized
    fun forgetApp(packageName: String) {
        val log = DataSharingAccessLog.decode(prefs.getString(KEY_ACCESS_LOG, null))
        if (packageName !in log) return
        prefs.edit { putString(KEY_ACCESS_LOG, DataSharingAccessLog.encode(log - packageName)) }
    }

    private companion object {
        const val PREFS_FILE = "openvitals_data_sharing"
        const val KEY_ENABLED = "sharing_enabled"
        const val KEY_ACCESS_LOG = "access_log"
    }
}
