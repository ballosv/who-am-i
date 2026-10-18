package de.ballosv.whoami.content

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Prüft mit der Gesichtserkennung von ML Kit (läuft offline auf dem Gerät), ob ein Foto ein
 * brauchbares Porträt ist, und schneidet es quadratisch um das Gesicht zu.
 */
object FaceCropper {
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.05f)
                .build(),
        )
    }

    /** Mindestbreite des Gesichts relativ zur Bildbreite – kleinere Gesichter sind zu weit weg. */
    private const val MIN_FACE_RATIO = 0.08f

    /** Weitere Gesichter dürfen höchstens so groß sein (relativ zum Hauptgesicht), sonst ist es ein Gruppenfoto. */
    private const val MAX_SECOND_FACE = 0.5f

    /** Ausschnitt = Gesichtsgröße × Faktor, damit Haare, Kinn und etwas Umgebung sichtbar bleiben. */
    private const val CROP_FACTOR = 2.4f
    private const val MIN_CROP_PX = 320

    /**
     * Liefert ein gesichtszentriertes, quadratisches JPEG oder null, wenn das Foto nicht genau ein
     * deutlich erkennbares Gesicht zeigt (Gruppenfoto, von hinten, zu weit weg, kein Foto).
     */
    fun portrait(bitmap: Bitmap): ByteArray? {
        val faces = runCatching {
            Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 15, TimeUnit.SECONDS)
        }.getOrNull() ?: return null
        if (faces.isEmpty()) return null
        val sorted = faces.map { it.boundingBox }.sortedByDescending { it.width() * it.height() }
        val main = sorted.first()
        if (main.width() < bitmap.width * MIN_FACE_RATIO) return null
        if (sorted.size > 1 && sorted[1].width() > main.width() * MAX_SECOND_FACE) return null

        val faceSize = max(main.width(), main.height())
        val shortSide = min(bitmap.width, bitmap.height)
        val side = max((faceSize * CROP_FACTOR).toInt(), min(MIN_CROP_PX, shortSide))
        // Leicht nach unten versetzt, damit Kinn und Hals nicht abgeschnitten werden.
        val cx = main.exactCenterX()
        val cy = main.exactCenterY() + faceSize * 0.1f
        return Images.cropSquareJpeg(bitmap, cx, cy, side)
    }
}
