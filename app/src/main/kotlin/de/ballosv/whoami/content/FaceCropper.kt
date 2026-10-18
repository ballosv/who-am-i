package de.ballosv.whoami.content

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
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
 *
 * Das Erkennungsmodell steckt nicht in der APK, sondern kommt über die Google-Play-Dienste
 * ([prepare] stößt den Download beim App-Start an). Bis es da ist, meldet [portrait]
 * [Result.Unavailable] und die Fotos werden ohne Gesichtserkennung zugeschnitten.
 */
object FaceCropper {
    sealed interface Result {
        class Portrait(val jpeg: ByteArray) : Result
        data object NoSingleFace : Result
        data object Unavailable : Result
    }

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

    /** Lädt das Modell über die Play-Dienste herunter, falls es noch fehlt (einmalig, im Hintergrund). */
    fun prepare(context: Context) {
        val request = ModuleInstallRequest.newBuilder().addApi(detector).build()
        ModuleInstall.getClient(context.applicationContext)
            .installModules(request)
            .addOnFailureListener { Log.w("WhoAmI.Face", "Modell der Gesichtserkennung nicht verfügbar", it) }
    }

    fun portrait(bitmap: Bitmap): Result {
        val faces = try {
            Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Modell noch nicht geladen, keine Play-Dienste o. Ä.
            Log.d("WhoAmI.Face", "Gesichtserkennung nicht verfügbar", e)
            return Result.Unavailable
        }
        if (faces.isEmpty()) return Result.NoSingleFace
        val sorted = faces.map { it.boundingBox }.sortedByDescending { it.width() * it.height() }
        val main = sorted.first()
        if (main.width() < bitmap.width * MIN_FACE_RATIO) return Result.NoSingleFace
        if (sorted.size > 1 && sorted[1].width() > main.width() * MAX_SECOND_FACE) return Result.NoSingleFace

        val faceSize = max(main.width(), main.height())
        val shortSide = min(bitmap.width, bitmap.height)
        val side = max((faceSize * CROP_FACTOR).toInt(), min(MIN_CROP_PX, shortSide))
        // Leicht nach unten versetzt, damit Kinn und Hals nicht abgeschnitten werden.
        val cx = main.exactCenterX()
        val cy = main.exactCenterY() + faceSize * 0.1f
        return Result.Portrait(Images.cropSquareJpeg(bitmap, cx, cy, side))
    }
}
