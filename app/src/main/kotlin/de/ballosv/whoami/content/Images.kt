package de.ballosv.whoami.content

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream

object Images {
    const val TARGET_SIZE = 900

    /**
     * Schneidet ein quadratisches Bild zu (bei Hochformat leicht nach oben versetzt, weil
     * Gesichter meist im oberen Bereich liegen) und komprimiert es als JPEG.
     */
    fun toSquareJpeg(source: Bitmap): ByteArray {
        val w = source.width
        val h = source.height
        val side = minOf(w, h)
        val x = (w - side) / 2
        val y = if (h > w) ((h - side) * 0.2f).toInt() else 0
        val cropped = Bitmap.createBitmap(source, x, y, side, side)
        val scaled = if (side > TARGET_SIZE) Bitmap.createScaledBitmap(cropped, TARGET_SIZE, TARGET_SIZE, true) else cropped
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    fun fromBytes(data: ByteArray): ByteArray? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        if (opts.outWidth <= 0) return null
        val sample = sampleSize(minOf(opts.outWidth, opts.outHeight))
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return toSquareJpeg(bmp)
    }

    fun fromUri(context: Context, uri: Uri): ByteArray? = runCatching {
        val bmp = if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val small = minOf(info.size.width, info.size.height)
                val factor = sampleSize(small)
                if (factor > 1) decoder.setTargetSize(info.size.width / factor, info.size.height / factor)
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } ?: return null
        toSquareJpeg(bmp)
    }.getOrNull()

    private fun sampleSize(smallSide: Int): Int {
        var s = 1
        while (smallSide / (s * 2) >= TARGET_SIZE) s *= 2
        return s
    }
}
