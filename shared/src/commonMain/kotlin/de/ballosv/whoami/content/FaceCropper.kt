package de.ballosv.whoami.content

import de.ballosv.whoami.platform.ImageOps
import de.ballosv.whoami.platform.PlatformImage
import kotlin.math.max
import kotlin.math.min

/**
 * Prüft mit der Gesichtserkennung des Geräts (ML Kit auf Android, Vision auf iOS), ob ein Foto
 * ein brauchbares Porträt ist, und schneidet es quadratisch um das Gesicht zu. Ist die Erkennung
 * nicht verfügbar, meldet [portrait] [Result.Unavailable] und das Foto wird ohne sie zugeschnitten.
 */
object FaceCropper {
    sealed interface Result {
        class Portrait(val jpeg: ByteArray) : Result
        data object NoSingleFace : Result
        data object Unavailable : Result
    }

    /** Mindestbreite des Gesichts relativ zur Bildbreite – kleinere Gesichter sind zu weit weg. */
    private const val MIN_FACE_RATIO = 0.08f

    /** Weitere Gesichter dürfen höchstens so groß sein (relativ zum Hauptgesicht), sonst ist es ein Gruppenfoto. */
    private const val MAX_SECOND_FACE = 0.5f

    /** Ausschnitt = Gesichtsgröße × Faktor, damit Haare, Kinn und etwas Umgebung sichtbar bleiben. */
    private const val CROP_FACTOR = 2.4f
    private const val MIN_CROP_PX = 320

    fun portrait(image: PlatformImage): Result {
        val faces = ImageOps.detectFaces(image) ?: return Result.Unavailable
        if (faces.isEmpty()) return Result.NoSingleFace
        val sorted = faces.sortedByDescending { it.width * it.height }
        val main = sorted.first()
        if (main.width < image.width * MIN_FACE_RATIO) return Result.NoSingleFace
        if (sorted.size > 1 && sorted[1].width > main.width * MAX_SECOND_FACE) return Result.NoSingleFace

        val faceSize = max(main.width, main.height)
        val shortSide = min(image.width, image.height)
        val side = max((faceSize * CROP_FACTOR).toInt(), min(MIN_CROP_PX, shortSide))
        // Leicht nach unten versetzt, damit Kinn und Hals nicht abgeschnitten werden.
        return Result.Portrait(ImageOps.cropSquareJpeg(image, main.centerX, main.centerY + faceSize * 0.1f, side))
    }
}
