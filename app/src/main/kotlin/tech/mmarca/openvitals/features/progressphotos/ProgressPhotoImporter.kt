package tech.mmarca.openvitals.features.progressphotos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext
import tech.mmarca.openvitals.core.performance.DefaultDispatcherProvider
import tech.mmarca.openvitals.core.performance.DispatcherProvider

/** A photo ready to keep: the full image and its thumbnail, both JPEG. */
class EncodedProgressPhoto(val image: ByteArray, val thumbnail: ByteArray)

/** Turns a picked or captured image into what the repository keeps. A seam for the ViewModel's tests. */
interface ProgressPhotoImporter {
    suspend fun encode(uri: String): EncodedProgressPhoto
}

/**
 * Decodes the image upright, scales it down and writes it again as JPEG. Writing it again drops
 * the camera's metadata, its location among it, before the photo leaves the phone, and turns a
 * HEIC photo into one any browser on the dashboard can show. From Android 9 the platform decoder
 * applies the camera's orientation itself; on Android 8 a photo keeps the orientation it was
 * stored in.
 */
class ContentProgressPhotoImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ProgressPhotoImporter {
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider

    override suspend fun encode(uri: String): EncodedProgressPhoto = withContext(dispatchers.io) {
        val source = Uri.parse(uri)
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeUpright(source) else decodeAsStored(source)
        val image = decoded.scaledToEdge(MaxImageEdge)
        val thumbnail = image.scaledToEdge(MaxThumbnailEdge)
        try {
            EncodedProgressPhoto(image = image.jpeg(ImageQuality), thumbnail = thumbnail.jpeg(ThumbnailQuality))
        } finally {
            listOf(decoded, image, thumbnail).distinct().forEach(Bitmap::recycle)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeUpright(uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            // A software bitmap, so it can be compressed again.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (width, height) = scaledSize(info.size.width, info.size.height, MaxImageEdge)
            decoder.setTargetSize(width, height)
        }

    private fun decodeAsStored(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image")
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(max(bounds.outWidth, bounds.outHeight), MaxImageEdge)
        }
        return open(uri).use { BitmapFactory.decodeStream(it, null, options) } ?: throw IOException("Not an image")
    }

    private fun open(uri: Uri) = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open image")

    private companion object {
        const val MaxImageEdge = 2560
        const val MaxThumbnailEdge = 720
        const val ImageQuality = 90
        const val ThumbnailQuality = 80
    }
}

/** The largest power of two that keeps the long edge at or above [target], for a cheap first decode. */
internal fun sampleSize(longEdge: Int, target: Int): Int {
    var size = 1
    while (longEdge / (size * 2) >= target) size *= 2
    return size
}

/** The size that fits [maxEdge] on the long edge, keeping the shape; never larger than the source. */
internal fun scaledSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    val longEdge = max(width, height)
    if (longEdge <= maxEdge) return width to height
    val scale = maxEdge.toDouble() / longEdge
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

private fun Bitmap.scaledToEdge(maxEdge: Int): Bitmap {
    val (w, h) = scaledSize(width, height, maxEdge)
    return if (w == width && h == height) this else Bitmap.createScaledBitmap(this, w, h, true)
}

private fun Bitmap.jpeg(quality: Int): ByteArray =
    ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }
