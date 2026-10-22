package de.ballosv.whoami.content

import androidx.compose.ui.graphics.ImageBitmap
import de.ballosv.whoami.platform.ImageOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Bilder der aktuellen Spielrunde, im Speicher gehalten. Die Maps werden nur als Ganzes
 * ersetzt, damit gleichzeitige Zugriffe aus Lade- und UI-Threads sicher sind.
 */
class ImageStore {
    private val data = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    private val bitmaps = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids

    fun put(id: String, bytes: ByteArray) {
        data.update { it + (id to bytes) }
        bitmaps.update { it - id }
        _ids.update { it + id }
    }

    fun bytes(id: String): ByteArray? = data.value[id]

    fun has(id: String): Boolean = data.value.containsKey(id)

    fun bitmap(id: String): ImageBitmap? {
        bitmaps.value[id]?.let { return it }
        val bytes = data.value[id] ?: return null
        return ImageOps.toImageBitmap(bytes)?.also { bmp -> bitmaps.update { it + (id to bmp) } }
    }

    fun clear() {
        data.value = emptyMap()
        bitmaps.value = emptyMap()
        _ids.value = emptySet()
    }
}
