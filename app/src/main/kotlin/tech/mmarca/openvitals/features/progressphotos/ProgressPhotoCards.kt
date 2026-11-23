package tech.mmarca.openvitals.features.progressphotos

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.core.presentation.DateTimeFormatterProvider
import tech.mmarca.openvitals.domain.model.ProgressPhoto
import tech.mmarca.openvitals.ui.components.EmptyState
import tech.mmarca.openvitals.ui.components.OpenVitalsCard
import tech.mmarca.openvitals.ui.components.OpenVitalsTonalButton
import tech.mmarca.openvitals.ui.components.SectionHeader
import tech.mmarca.openvitals.ui.theme.LayoutMetrics
import tech.mmarca.openvitals.ui.theme.Spacing

private const val PhotosPerRow = 3

/** Where the queue stands: no folder yet, photos waiting for it, or a refused camera. Nothing when all is saved. */
internal fun LazyListScope.progressPhotoQueueStatus(
    folderPicked: Boolean,
    pendingCount: Int,
    cameraDenied: Boolean,
    onOpenFolderSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    if (cameraDenied) {
        item(key = "progress_photos_camera_denied") {
            StatusCard(message = stringResource(R.string.progress_photos_camera_denied))
        }
    }
    if (!folderPicked) {
        item(key = "progress_photos_folder_missing") {
            StatusCard(
                message = stringResource(R.string.progress_photos_folder_missing),
                actionLabel = stringResource(R.string.body_folder_pick),
                onAction = onOpenFolderSettings,
            )
        }
    } else if (pendingCount > 0) {
        item(key = "progress_photos_pending") {
            StatusCard(
                message = stringResource(R.string.progress_photos_pending, pendingCount),
                actionLabel = stringResource(R.string.progress_photos_retry),
                onAction = onRetry,
            )
        }
    }
}

internal fun LazyListScope.progressPhotoDays(
    display: ProgressPhotosDisplayState,
    dateTimeFormatterProvider: DateTimeFormatterProvider,
    thumbnailFile: (ProgressPhoto) -> File,
    onOpen: (ProgressPhoto) -> Unit,
) {
    if (display.isEmpty) {
        item(key = "progress_photos_empty") {
            EmptyState(message = stringResource(R.string.progress_photos_empty), icon = Icons.Outlined.PhotoCamera)
        }
        return
    }
    display.daysNewestFirst.forEach { day ->
        item(key = "progress_photos_day_${day.date}") {
            SectionHeader(
                text = dateTimeFormatterProvider.mediumDate().format(day.date),
                modifier = Modifier.padding(horizontal = LayoutMetrics.screenGutter, vertical = Spacing.sm),
            )
        }
        items(day.photos.chunked(PhotosPerRow), key = { row -> "progress_photos_row_${row.first().id}" }) { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LayoutMetrics.screenGutter, vertical = Spacing.xs),
            ) {
                row.forEach { photo ->
                    ProgressPhotoTile(
                        photo = photo,
                        file = thumbnailFile(photo),
                        onClick = { onOpen(photo) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(PhotosPerRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ProgressPhotoTile(
    photo: ProgressPhoto,
    file: File,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(ThumbnailAspectRatio)
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(onClick = onClick),
    ) {
        Thumbnail(file = file, contentDescription = stringResource(R.string.progress_photo_description))
        if (!photo.saved) {
            Icon(
                imageVector = Icons.Outlined.CloudUpload,
                contentDescription = stringResource(R.string.progress_photos_waiting),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.xs),
            )
        }
    }
}

/** Decodes the thumbnail off the main thread, once per file. */
@Composable
private fun Thumbnail(file: File, contentDescription: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StatusCard(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    OpenVitalsCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LayoutMetrics.screenGutter, vertical = Spacing.xs),
    ) {
        Column(
            modifier = Modifier.padding(LayoutMetrics.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                OpenVitalsTonalButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
internal fun AddProgressPhotoDialog(
    canTakePhoto: Boolean,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.progress_photos_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (canTakePhoto) {
                    OpenVitalsTonalButton(onClick = onTakePhoto, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                        Text(stringResource(R.string.progress_photos_take), modifier = Modifier.padding(start = Spacing.sm))
                    }
                }
                OpenVitalsTonalButton(onClick = onPickPhoto, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Text(stringResource(R.string.progress_photos_pick), modifier = Modifier.padding(start = Spacing.sm))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The photo larger, when it was taken, whether it is in the folder yet, and a delete. */
@Composable
internal fun ProgressPhotoDialog(
    photo: ProgressPhoto,
    thumbnailFile: File,
    dateTimeFormatterProvider: DateTimeFormatterProvider,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(dateTimeFormatterProvider.mediumDateTime().format(photo.takenAt.atZone(ZoneId.systemDefault())))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Thumbnail(
                    file = thumbnailFile,
                    contentDescription = stringResource(R.string.progress_photo_description),
                    modifier = Modifier.clip(MaterialTheme.shapes.extraSmall),
                )
                Text(
                    text = stringResource(
                        if (photo.saved) R.string.progress_photos_saved else R.string.progress_photos_waiting,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.progress_photos_delete_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) } },
    )
}

private const val ThumbnailAspectRatio = 3f / 4f
