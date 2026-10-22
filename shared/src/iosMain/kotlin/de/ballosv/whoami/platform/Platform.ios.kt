package de.ballosv.whoami.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UIViewController
import platform.Vision.VNDetectFaceRectanglesRequest
import platform.Vision.VNFaceObservation
import platform.Vision.VNImageRequestHandler
import platform.posix.AF_INET
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.darwin.inet_ntoa
import platform.UIKit.popoverPresentationController
import platform.posix.memcpy
import platform.posix.sockaddr_in

actual fun monotonicMillis(): Long = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()

actual val platformName: String = "iOS"

actual val hasSystemBack: Boolean = false

actual class KeyValueStore actual constructor(private val name: String) {
    private val defaults = NSUserDefaults.standardUserDefaults
    private fun k(key: String) = "$name.$key"

    actual fun getString(key: String): String? = defaults.stringForKey(k(key))
    actual fun putString(key: String, value: String) = defaults.setObject(value, k(key))
    actual fun getBoolean(key: String, default: Boolean): Boolean =
        if (defaults.objectForKey(k(key)) == null) default else defaults.boolForKey(k(key))

    actual fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, k(key))
}

/** Bild mit Ausrichtung „oben“ und Maßstab 1 (Pixel = Punkte). */
actual class PlatformImage(val image: UIImage) {
    actual val width: Int get() = image.size.useContents { width }.toInt()
    actual val height: Int get() = image.size.useContents { height }.toInt()
}

@OptIn(BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}

/** Zeichnet [image] neu in der Größe [w]×[h]; beseitigt dabei auch EXIF-Drehungen. */
internal fun redraw(image: UIImage, w: Double, h: Double, offsetX: Double = 0.0, offsetY: Double = 0.0, canvasW: Double = w, canvasH: Double = h): UIImage {
    val format = UIGraphicsImageRendererFormat.preferredFormat().apply { scale = 1.0 }
    val renderer = UIGraphicsImageRenderer(size = CGSizeMake(canvasW, canvasH), format = format)
    return renderer.imageWithActions { _ -> image.drawInRect(CGRectMake(offsetX, offsetY, w, h)) }
}

actual object ImageOps {
    actual fun decodeSampled(data: ByteArray): PlatformImage? {
        val image = UIImage.imageWithData(data.toNSData()) ?: return null
        val (w, h) = image.size.useContents { width to height }
        if (w <= 0 || h <= 0) return null
        // Verkleinern, solange die kurze Seite mindestens TARGET_SIZE bleibt
        var factor = 1.0
        while (minOf(w, h) / (factor * 2) >= TARGET_SIZE) factor *= 2
        return PlatformImage(redraw(image, (w / factor).toInt().toDouble(), (h / factor).toInt().toDouble()))
    }

    actual fun cropSquareJpeg(image: PlatformImage, cx: Float, cy: Float, side: Int): ByteArray {
        val s = side.coerceIn(1, minOf(image.width, image.height))
        val x = (cx - s / 2f).toInt().coerceIn(0, image.width - s)
        val y = (cy - s / 2f).toInt().coerceIn(0, image.height - s)
        val out = minOf(s, TARGET_SIZE).toDouble()
        val k = out / s
        val cropped = redraw(
            image.image, image.width * k, image.height * k,
            offsetX = -x * k, offsetY = -y * k, canvasW = out, canvasH = out,
        )
        return UIImageJPEGRepresentation(cropped, 0.85)?.toByteArray() ?: ByteArray(0)
    }

    actual fun detectFaces(image: PlatformImage): List<FaceBox>? {
        val cg = image.image.CGImage ?: return null
        val request = VNDetectFaceRectanglesRequest()
        val handler = VNImageRequestHandler(cg, options = emptyMap<Any?, Any>())
        val ok = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            handler.performRequests(listOf(request), error.ptr)
        }
        if (!ok) return null
        val w = image.width.toDouble()
        val h = image.height.toDouble()
        return request.results.orEmpty().filterIsInstance<VNFaceObservation>().map { face ->
            // Vision: normierte Koordinaten, Ursprung unten links
            face.boundingBox.useContents {
                FaceBox(
                    left = (origin.x * w).toFloat(),
                    top = ((1 - origin.y - size.height) * h).toFloat(),
                    width = (size.width * w).toFloat(),
                    height = (size.height * h).toFloat(),
                )
            }
        }
    }

    actual fun toImageBitmap(jpeg: ByteArray): ImageBitmap? =
        runCatching { Image.makeFromEncoded(jpeg).toComposeImageBitmap() }.getOrNull()
}

actual fun vibrate(long: Boolean) {
    if (long) {
        UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
    } else {
        UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium).impactOccurred()
    }
}

/** Oberster sichtbarer View-Controller, um Dialoge (Teilen, Fotoauswahl) darüber zu zeigen. */
internal fun topViewController(): UIViewController? {
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}

actual fun shareText(text: String) {
    val top = topViewController() ?: return
    val share = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
    share.popoverPresentationController?.sourceView = top.view
    top.presentViewController(share, animated = true, completion = null)
}

actual fun localIpv4(): String? = memScoped {
    val list = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(list.ptr) != 0) return@memScoped null
    val found = LinkedHashMap<String, String>()
    var cursor = list.value
    while (cursor != null) {
        val entry = cursor.pointed
        val addr = entry.ifa_addr
        if (addr != null && addr.pointed.sa_family.toInt() == AF_INET) {
            val name = entry.ifa_name?.toKString() ?: ""
            val ip = inet_ntoa(addr.reinterpret<sockaddr_in>().pointed.sin_addr.readValue())?.toKString()
            if (ip != null && ip != "127.0.0.1") found[name] = ip
        }
        cursor = entry.ifa_next
    }
    freeifaddrs(list.value)
    // en0 = WLAN, bridge100 = eigener Hotspot
    found["en0"] ?: found.entries.firstOrNull { it.key.startsWith("bridge") }?.value
        ?: found.values.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
}

actual object Invites {
    actual val supported: Boolean = false
    actual fun startListening() = Unit
    actual fun stopListening() = Unit
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@Composable
actual fun KeepScreenOn() {
    DisposableEffect(Unit) {
        UIApplication.sharedApplication.idleTimerDisabled = true
        onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}

/** Auf dem iPhone sind alle Mehrspieler-Verbindungen WLAN; die Freigabe „Lokales Netzwerk“ fragt iOS selbst ab. */
@Composable
actual fun rememberNetworkPermissionRequest(withNotifications: Boolean, onResult: (Boolean) -> Unit): () -> Unit =
    { onResult(true) }
