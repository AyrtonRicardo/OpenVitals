package tech.mmarca.openvitals.features.progressphotos

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Starts a save of the waiting photos. A seam so the screens can be tested without WorkManager. */
interface ProgressPhotoSaveScheduling {
    fun enqueue()
}

/**
 * The save queue. One unique job, replaced by each new request, so a photo added while an earlier
 * pass backs off is tried at once. No network constraint: the app holds no network permission, and
 * a document provider app reaches its share itself. A failed pass backs off and tries again.
 */
@Singleton
class ProgressPhotoSaveScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ProgressPhotoSaveScheduling {
    override fun enqueue() {
        val request = OneTimeWorkRequestBuilder<ProgressPhotoSaveWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FirstRetryMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ProgressPhotoSaveWorker.UniqueWorkName, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val FirstRetryMinutes = 1L
    }
}

/** A background job, not a foreground service: it moves a few files and holds no notification. */
class ProgressPhotoSaveWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val saver = EntryPointAccessors
            .fromApplication(applicationContext, ProgressPhotoSaveWorkerEntryPoint::class.java)
            .progressPhotoSaver()
        val outcome = saver.savePending()
        return when {
            // Picking a folder starts the queue again, so there is nothing to wait for.
            outcome.folderMissing -> Result.success()
            outcome.remaining > 0 && runAttemptCount < MaxAttempts -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val UniqueWorkName = "progress_photo_save"

        /** With the doubling backoff, about two days of tries; opening the photos screen starts it again. */
        const val MaxAttempts = 12
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProgressPhotoSaveWorkerEntryPoint {
    fun progressPhotoSaver(): ProgressPhotoSaver
}
