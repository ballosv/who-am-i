package de.ballosv.whoami.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Alles, was Android und iOS unterschiedlich umsetzen. Die Spiellogik und die Oberfläche
 * sind gemeinsam; hier liegen nur die Schnittstellen zu den Gerätefunktionen.
 */

/** Monotone Uhr in Millisekunden (unabhängig von Zeitumstellungen). */
expect fun monotonicMillis(): Long

/** „Android“ oder „iOS“. */
expect val platformName: String

/** Android hat eine Zurück-Taste bzw. -Geste; auf dem iPhone braucht es dafür sichtbare Schaltflächen. */
expect val hasSystemBack: Boolean

/** Dauerhafter Schlüssel-Wert-Speicher (SharedPreferences bzw. NSUserDefaults). */
expect class KeyValueStore(name: String) {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

/** Ein dekodiertes Bild der Plattform. */
expect class PlatformImage {
    val width: Int
    val height: Int
}

/** Gesichtsrahmen in Pixeln, Ursprung oben links. */
data class FaceBox(val left: Float, val top: Float, val width: Float, val height: Float) {
    val centerX: Float get() = left + width / 2
    val centerY: Float get() = top + height / 2
}

expect object ImageOps {
    /** Dekodiert so verkleinert, dass die kurze Seite noch mindestens [TARGET_SIZE] Pixel hat. */
    fun decodeSampled(data: ByteArray): PlatformImage?

    /** Quadratischer Ausschnitt um ([cx], [cy]) mit Kantenlänge [side], als JPEG (höchstens [TARGET_SIZE] px). */
    fun cropSquareJpeg(image: PlatformImage, cx: Float, cy: Float, side: Int): ByteArray

    /** Gesichter im Bild; null, wenn die Gesichtserkennung (noch) nicht verfügbar ist. */
    fun detectFaces(image: PlatformImage): List<FaceBox>?

    fun toImageBitmap(jpeg: ByteArray): ImageBitmap?
}

const val TARGET_SIZE = 900

/** Quadratischer Ausschnitt; bei Hochformat leicht nach oben versetzt, weil Gesichter meist oben liegen. */
fun ImageOps.toSquareJpeg(image: PlatformImage): ByteArray {
    val side = minOf(image.width, image.height)
    val y = if (image.height > image.width) (image.height - side) * 0.2f else 0f
    return cropSquareJpeg(image, image.width / 2f, y + side / 2f, side)
}

/** Fertiges quadratisches JPEG aus beliebigen Bilddaten (z. B. eigenes Foto im Battle-Modus). */
fun ImageOps.squareJpegFrom(data: ByteArray): ByteArray? = decodeSampled(data)?.let { toSquareJpeg(it) }

expect fun vibrate(long: Boolean)

expect fun shareText(text: String)

/** IPv4-Adresse im WLAN (bzw. Hotspot), für TV-Ansicht und Mehrspieler im lokalen Netz. */
expect fun localIpv4(): String?

/** Einladungen im Hintergrund empfangen (nur Android). */
expect object Invites {
    val supported: Boolean
    fun startListening()
    fun stopListening()
}

fun joinUri(code: String): String = "whoami://join?code=$code"

@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** Display bleibt an, solange das Composable sichtbar ist. */
@Composable
expect fun KeepScreenOn()

/** Spracheingabe; liefert eine Startfunktion oder null, wenn keine verfügbar ist. */
@Composable
expect fun rememberSpeechInput(onResult: (String) -> Unit): (() -> Unit)?

/** Fotoauswahl (höchstens `max` Bilder); liefert die gewählten Bilder schon als quadratische JPEGs. */
@Composable
expect fun rememberPhotoPicker(onPicked: (List<ByteArray>) -> Unit): (max: Int) -> Unit

/**
 * Fragt die für den Mehrspieler-Modus (und optional Benachrichtigungen) nötigen Berechtigungen an
 * und ruft danach [onResult] auf. Liefert eine Funktion, die den Vorgang startet.
 */
@Composable
expect fun rememberNetworkPermissionRequest(withNotifications: Boolean = false, onResult: (Boolean) -> Unit): () -> Unit
