package tech.mmarca.openvitals.sharing

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import tech.mmarca.openvitals.OpenVitalsApp
import tech.mmarca.openvitals.sharing.contract.DataSharingContract
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.sharing.contract.DataSharingRequestParser
import tech.mmarca.openvitals.sharing.contract.DataSharingStatus

/**
 * The read-only data sharing API. Android checks each group's path-permission
 * first; [query] checks again, because Android lets through a path that no
 * path-permission names. [openFile] serves a workout's FIT file. Everything else refuses.
 */
class DataSharingProvider : ContentProvider() {

    /** Hilt is not ready here: the Flutter preference migration runs later, in the Application. */
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require(selection == null) { "selection is not supported. Filter with the metric parameter." }
        require(sortOrder == null) { "sortOrder is not supported. Rows come sorted by time." }
        val context = requireNotNull(context)
        val request = DataSharingRequestParser.parse(uri.pathSegments, uri::getQueryParameter)
        request.group?.let { group -> enforceGranted(context, group) }
        // Hilt must not build its graph before the app's own start-up has run.
        if (!OpenVitalsApp.awaitStarted(StartupTimeoutMillis)) {
            return DataSharingAnswers.empty(request, DataSharingStatus.PARTIAL).project(projection).toCursor()
        }

        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, DataSharingEntryPoint::class.java)
        callingPackage?.let { entryPoint.dataSharingStore().recordAccess(it, request.group) }

        val answer = blocking(
            busy = { DataSharingAnswers.empty(request, DataSharingStatus.RATE_LIMITED) },
            timedOut = { DataSharingAnswers.empty(request, DataSharingStatus.PARTIAL) },
        ) {
            entryPoint.dataSharingQueries().answer(
                request = request,
                permissionFor = { DataSharingContract.permission(context.packageName, it) },
                isGranted = { isGranted(context, it) },
            )
        }

        return answer.project(projection).toCursor()
    }

    /**
     * A recorded workout's FIT file. Read-only. The caller needs the workouts group, and the
     * routes group as well to get the route. A refusal is a [FileNotFoundException] whose message
     * starts with the status and a colon, such as `sharing_off: `; it names no workout.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") readOnly()
        val context = requireNotNull(context)
        val request = DataSharingRequestParser.parseFile(uri.pathSegments, uri::getQueryParameter)
        request.groups.forEach { group -> enforceGranted(context, group) }
        if (!OpenVitalsApp.awaitStarted(StartupTimeoutMillis)) refuse(DataSharingStatus.PARTIAL)

        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, DataSharingEntryPoint::class.java)
        callingPackage?.let { caller -> request.groups.forEach { entryPoint.dataSharingStore().recordAccess(caller, it) } }

        val answer = blocking<WorkoutFitAnswer>(
            busy = { WorkoutFitAnswer.Refused(DataSharingStatus.RATE_LIMITED) },
            timedOut = { WorkoutFitAnswer.Refused(DataSharingStatus.PARTIAL) },
        ) { entryPoint.dataSharingQueries().workoutFit(request) }
        return when (answer) {
            is WorkoutFitAnswer.File -> openPipeHelper(uri, DataSharingContract.FIT_MIME_TYPE, null, answer.bytes) { output, _, _, _, bytes ->
                // The reader may close early; that is its choice, not a failure here.
                runCatching { FileOutputStream(output.fileDescriptor).use { it.write(bytes) } }
            }
            is WorkoutFitAnswer.Refused -> refuse(answer.status)
        }
    }

    override fun getType(uri: Uri): String? =
        runCatching { DataSharingRequestParser.parseFile(uri.pathSegments, uri::getQueryParameter) }
            .map { DataSharingContract.FIT_MIME_TYPE }
            .getOrNull()

    override fun insert(uri: Uri, values: ContentValues?): Uri? = readOnly()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        readOnly()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = readOnly()

    private fun isGranted(context: Context, group: DataSharingGroup): Boolean =
        context.checkCallingPermission(DataSharingContract.permission(context.packageName, group)) ==
            PackageManager.PERMISSION_GRANTED

    private fun enforceGranted(context: Context, group: DataSharingGroup) {
        if (!isGranted(context, group)) {
            throw SecurityException(
                "Reading /${group.path} needs ${DataSharingContract.permission(context.packageName, group)}.",
            )
        }
    }

    /**
     * Runs [block] to its end, or answers [timedOut] after [QueryTimeoutMillis],
     * or [busy] when [MaxWaitingCallers] calls are already waiting. A provider
     * call arrives on a binder thread and must answer before it returns, so this
     * is the one place that blocks: the timeout bounds it, and the cap keeps an
     * app that asks in a loop from holding every binder thread of the process.
     */
    private fun <T : Any> blocking(busy: () -> T, timedOut: () -> T, block: suspend () -> T): T {
        if (waiting.incrementAndGet() > MaxWaitingCallers) {
            waiting.decrementAndGet()
            return busy()
        }
        return try {
            runBlocking { withTimeoutOrNull(QueryTimeoutMillis) { block() } } ?: timedOut()
        } finally {
            waiting.decrementAndGet()
        }
    }

    private fun refuse(status: DataSharingStatus): Nothing =
        throw FileNotFoundException("${status.value}: ${RefusalText[status] ?: "the file could not be written"}")

    private fun readOnly(): Nothing = throw UnsupportedOperationException("The OpenVitals data API is read-only.")

    private fun DataSharingAnswer.toCursor(): Cursor {
        val cursor = MatrixCursor(columns, rows.size)
        rows.forEach(cursor::addRow)
        cursor.extras = Bundle().apply {
            putString(DataSharingContract.EXTRA_STATUS, status.value)
            historyStart?.let { putString(DataSharingContract.EXTRA_HISTORY_START, it.toString()) }
        }
        return cursor
    }

    private companion object {
        /** Calls waiting for an answer at once, over every calling app. */
        val waiting = AtomicInteger()
        const val MaxWaitingCallers = 4

        /** A year of daily values takes a few seconds; a stuck read must not hold a binder thread for long. */
        const val QueryTimeoutMillis = 20_000L
        const val StartupTimeoutMillis = 5_000L

        /** What follows the status in a refused file's message. Words only: no id, no value. */
        val RefusalText = mapOf(
            DataSharingStatus.SHARING_OFF to "sharing is off in OpenVitals",
            DataSharingStatus.BACKGROUND_READ_MISSING to "OpenVitals may not read Health Connect in the background",
            DataSharingStatus.HEALTH_CONNECT_PAUSED to "Health Connect access is paused in OpenVitals",
            DataSharingStatus.HEALTH_CONNECT_UNAVAILABLE to "Health Connect is not available",
            DataSharingStatus.RATE_LIMITED to "Health Connect asked OpenVitals to slow down",
            DataSharingStatus.NOT_FOUND to "no recorded workout has this id",
            DataSharingStatus.PARTIAL to "a read failed, so the file would be incomplete",
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DataSharingEntryPoint {
    fun dataSharingStore(): DataSharingStore
    fun dataSharingQueries(): DataSharingQueries
}
