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
import de.ballosv.whoami.platform.FaceBox
import java.util.concurrent.TimeUnit

/**
 * Gesichtserkennung von ML Kit (läuft offline auf dem Gerät). Das Modell steckt nicht in der APK,
 * sondern kommt über die Google-Play-Dienste ([prepare] stößt den Download beim App-Start an).
 */
object MlKitFaces {
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.05f)
                .build(),
        )
    }

    /** Lädt das Modell über die Play-Dienste herunter, falls es noch fehlt (einmalig, im Hintergrund). */
    fun prepare(context: Context) {
        val request = ModuleInstallRequest.newBuilder().addApi(detector).build()
        ModuleInstall.getClient(context.applicationContext)
            .installModules(request)
            .addOnFailureListener { Log.w("WhoAmI.Face", "Modell der Gesichtserkennung nicht verfügbar", it) }
    }

    /** Gesichter im Bild oder null, wenn das Modell (noch) nicht verfügbar ist. */
    fun detect(bitmap: Bitmap): List<FaceBox>? = try {
        Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 15, TimeUnit.SECONDS)
            .map { f -> f.boundingBox.let { FaceBox(it.left.toFloat(), it.top.toFloat(), it.width().toFloat(), it.height().toFloat()) } }
    } catch (e: Exception) {
        // Modell noch nicht geladen, keine Play-Dienste o. Ä.
        Log.d("WhoAmI.Face", "Gesichtserkennung nicht verfügbar", e)
        null
    }
}
