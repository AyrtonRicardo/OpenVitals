package tech.mmarca.openvitals.features.progressphotos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.core.presentation.DateTimeFormatterProvider
import tech.mmarca.openvitals.domain.model.ProgressPhoto
import tech.mmarca.openvitals.ui.components.MetricAction
import tech.mmarca.openvitals.ui.components.MetricDetailScaffold

/**
 * Progress photos over a period. The photos are in Room and the picked folder, not Health Connect,
 * so there is no Health Connect shell around it. A photo is taken with the camera app or picked
 * from the gallery; neither needs a storage permission.
 */
@Composable
fun ProgressPhotosScreen(
    viewModel: ProgressPhotosViewModel,
    dateTimeFormatterProvider: DateTimeFormatterProvider,
    onOpenFolderSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var cameraDenied by rememberSaveable { mutableStateOf(false) }
    var openPhoto by remember { mutableStateOf<ProgressPhoto?>(null) }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.resumeCurrentPeriod() }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.addPhoto(it.toString()) }
    }
    val captureUri = remember { captureUri(context) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) viewModel.addPhoto(captureUri.toString())
    }
    // The app declares the camera permission for the medical card scanner, and a camera app then
    // refuses a capture request from it until the permission is granted.
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraLauncher.launch(captureUri) else cameraDenied = true
    }

    MetricDetailScaffold(
        isLoading = state.isLoading || state.isAdding,
        selectedRange = state.selectedRange,
        selectedDate = state.selectedDate,
        screenError = state.error,
        onRefresh = viewModel::load,
        onSelectRange = viewModel::selectRange,
        onPreviousPeriod = viewModel::previousPeriod,
        onNextPeriod = viewModel::nextPeriod,
        onSelectDate = viewModel::selectDate,
        onSelectDay = viewModel::selectDay,
        weekPeriodMode = state.weekPeriodMode,
        primaryAction = MetricAction(
            labelRes = R.string.progress_photos_add,
            icon = Icons.Outlined.AddAPhoto,
            onClick = { showAddDialog = true },
        ),
        headerItems = {
            progressPhotoQueueStatus(
                folderPicked = state.folderPicked,
                pendingCount = state.pendingCount,
                cameraDenied = cameraDenied,
                onOpenFolderSettings = onOpenFolderSettings,
                onRetry = viewModel::retryPendingSaves,
            )
        },
    ) { _ ->
        progressPhotoDays(
            display = state.display,
            dateTimeFormatterProvider = dateTimeFormatterProvider,
            thumbnailFile = viewModel::thumbnailFile,
            onOpen = { openPhoto = it },
        )
    }

    if (showAddDialog) {
        AddProgressPhotoDialog(
            canTakePhoto = hasCamera,
            onTakePhoto = {
                showAddDialog = false
                cameraDenied = false
                if (context.hasCameraPermission()) {
                    cameraLauncher.launch(captureUri)
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onPickPhoto = {
                showAddDialog = false
                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onDismiss = { showAddDialog = false },
        )
    }

    openPhoto?.let { photo ->
        ProgressPhotoDialog(
            photo = photo,
            thumbnailFile = viewModel.thumbnailFile(photo),
            dateTimeFormatterProvider = dateTimeFormatterProvider,
            onDelete = {
                openPhoto = null
                viewModel.delete(photo)
            },
            onDismiss = { openPhoto = null },
        )
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** One reused file in the cache: the camera app writes there, and the photo is kept from it at once. */
private fun captureUri(context: Context): Uri {
    val file = File(File(context.cacheDir, CaptureDirectory).apply { mkdirs() }, CaptureFileName)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private const val CaptureDirectory = "progress_photo_capture"
private const val CaptureFileName = "capture.jpg"
