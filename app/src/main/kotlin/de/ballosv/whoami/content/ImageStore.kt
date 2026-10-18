package de.ballosv.whoami.content

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** Bilder der aktuellen Spielrunde, im Speicher gehalten. */
class ImageStore {
    private val data = ConcurrentHashMap<String, ByteArray>()
    private val bitmaps = ConcurrentHashMap<String, Bitmap>()
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids

    fun put(id: String, bytes: ByteArray) {
        data[id] = bytes
        bitmaps.remove(id)
        _ids.update { it + id }
    }

    fun bytes(id: String): ByteArray? = data[id]

    fun has(id: String): Boolean = data.containsKey(id)

    fun bitmap(id: String): Bitmap? {
        bitmaps[id]?.let { return it }
        val bytes = data[id] ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { bitmaps[id] = it }
    }

    fun clear() {
        data.clear()
        bitmaps.clear()
        _ids.value = emptySet()
    }
}
