package de.ballosv.whoami.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import de.ballosv.whoami.content.MlKitFaces
import de.ballosv.whoami.net.NearbyConfig
import de.ballosv.whoami.ui.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/** Zugriff auf den App-Kontext; wird von der Application beim Start gesetzt. */
@SuppressLint("StaticFieldLeak")
object AndroidPlatform {
    lateinit var context: Context
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
        MlKitFaces.prepare(this.context)
    }
}

actual fun monotonicMillis(): Long = SystemClock.elapsedRealtime()

actual val platformName: String = "Android"

actual val hasSystemBack: Boolean = true

actual class KeyValueStore actual constructor(name: String) {
    private val sp = AndroidPlatform.context.getSharedPreferences(name, Context.MODE_PRIVATE)

    actual fun getString(key: String): String? = sp.getString(key, null)
    actual fun putString(key: String, value: String) = sp.edit().putString(key, value).apply()
    actual fun getBoolean(key: String, default: Boolean): Boolean = sp.getBoolean(key, default)
    actual fun putBoolean(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
}

actual class PlatformImage(val bitmap: Bitmap) {
    actual val width: Int get() = bitmap.width
    actual val height: Int get() = bitmap.height
}

actual object ImageOps {
    actual fun decodeSampled(data: ByteArray): PlatformImage? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        if (opts.outWidth <= 0) return null
        val sample = sampleSize(minOf(opts.outWidth, opts.outHeight))
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.let(::PlatformImage)
    }

    actual fun cropSquareJpeg(image: PlatformImage, cx: Float, cy: Float, side: Int): ByteArray {
        val source = image.bitmap
        val s = side.coerceIn(1, minOf(source.width, source.height))
        val x = (cx - s / 2f).toInt().coerceIn(0, source.width - s)
        val y = (cy - s / 2f).toInt().coerceIn(0, source.height - s)
        val square = Bitmap.createBitmap(source, x, y, s, s)
        val scaled = if (square.width > TARGET_SIZE) Bitmap.createScaledBitmap(square, TARGET_SIZE, TARGET_SIZE, true) else square
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    actual fun detectFaces(image: PlatformImage): List<FaceBox>? = MlKitFaces.detect(image.bitmap)

    actual fun toImageBitmap(jpeg: ByteArray): ImageBitmap? =
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()

    /** Eigenes Foto laden (ImageDecoder beachtet die Ausrichtung aus den EXIF-Daten). */
    fun fromUri(context: Context, uri: Uri): ByteArray? = runCatching {
        val bmp = if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val factor = sampleSize(minOf(info.size.width, info.size.height))
                if (factor > 1) decoder.setTargetSize(info.size.width / factor, info.size.height / factor)
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } ?: return null
        toSquareJpeg(PlatformImage(bmp))
    }.getOrNull()

    private fun sampleSize(smallSide: Int): Int {
        var s = 1
        while (smallSide / (s * 2) >= TARGET_SIZE) s *= 2
        return s
    }
}

actual fun vibrate(long: Boolean) {
    val context = AndroidPlatform.context
    val vibrator = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    runCatching { vibrator.vibrate(VibrationEffect.createOneShot(if (long) 250 else 40, VibrationEffect.DEFAULT_AMPLITUDE)) }
}

actual fun shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    AndroidPlatform.context.startActivity(
        Intent.createChooser(send, "Einladung teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

actual fun localIpv4(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("ap") || it.name.startsWith("swlan")) 1 else 2 }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()

/** Der Dienst liegt im App-Modul (braucht Activity und Icons), daher Aufruf über den Klassennamen. */
actual object Invites {
    private const val SERVICE = "de.ballosv.whoami.invite.InviteListenerService"
    private fun intent() = Intent().setClassName(AndroidPlatform.context, SERVICE)

    actual val supported: Boolean = true
    actual fun startListening() {
        runCatching { ContextCompat.startForegroundService(AndroidPlatform.context, intent()) }
    }

    actual fun stopListening() {
        AndroidPlatform.context.stopService(intent())
    }
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)

@Composable
actual fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
actual fun rememberSpeechInput(onResult: (String) -> Unit): (() -> Unit)? {
    val callback by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(callback)
    }
    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Sag den Namen")
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            showToast("Spracheingabe nicht verfügbar")
        }
    }
}

@Composable
actual fun rememberPhotoPicker(onPicked: (List<ByteArray>) -> Unit): (max: Int) -> Unit {
    val callback by rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()
    val handle: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val context = AndroidPlatform.context
            callback(withContext(Dispatchers.IO) { uris.mapNotNull { ImageOps.fromUri(context, it) } })
        }
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { handle(it) }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { handle(listOfNotNull(it)) }
    return { max ->
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        if (max > 1) pickMany.launch(request) else pickOne.launch(request)
    }
}

private const val MAX_PICK = 30

@Composable
actual fun rememberNetworkPermissionRequest(withNotifications: Boolean, onResult: (Boolean) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onResult)
    val context = AndroidPlatform.context
    val permissions = buildList {
        addAll(NearbyConfig.requiredPermissions())
        if (withNotifications && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Benachrichtigungen sind optional, Nearby ist Pflicht.
        callback(NearbyConfig.hasPermissions(context))
    }
    return {
        val notificationsOk = !withNotifications || Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (NearbyConfig.hasPermissions(context) && notificationsOk) callback(true) else launcher.launch(permissions)
    }
}
