package de.ballosv.whoami.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Nachrichten zwischen Host und Mitspielern. */
@Serializable
sealed class Message {
    // Mitspieler -> Host
    @Serializable @SerialName("hello")
    data class Hello(val playerId: String, val name: String, val appVersion: Int) : Message()

    @Serializable @SerialName("name")
    data class SetName(val name: String) : Message()

    @Serializable @SerialName("ready")
    data class Ready(val ready: Boolean) : Message()

    @Serializable @SerialName("ping")
    data class Ping(val sentLocal: Long) : Message()

    /** [hostTime]: Zeitpunkt des Drückens, schon in Host-Zeit umgerechnet. */
    @Serializable @SerialName("buzz")
    data class Buzz(val hostTime: Long) : Message()

    @Serializable @SerialName("answer")
    data class Answer(val text: String) : Message()

    /** Battle-Modus: ein eigenes Bild samt Lösung. Die Bilddaten folgen als [ImageChunk]s. */
    @Serializable @SerialName("battle_image")
    data class BattleImage(val imageId: String, val firstName: String, val lastName: String) : Message()

    @Serializable @SerialName("battle_clear")
    data object BattleClear : Message()

    @Serializable @SerialName("images_ready")
    data class ImagesReady(val imageIds: List<String>) : Message()

    // Host -> Mitspieler
    @Serializable @SerialName("welcome")
    data class Welcome(val hostName: String) : Message()

    @Serializable @SerialName("pong")
    data class Pong(val sentLocal: Long, val hostTime: Long) : Message()

    @Serializable @SerialName("state")
    data class State(val snapshot: GameSnapshot) : Message()

    @Serializable @SerialName("bye")
    data class Bye(val reason: String) : Message()

    // beide Richtungen
    @Serializable @SerialName("chunk")
    data class ImageChunk(val imageId: String, val index: Int, val total: Int, val base64: String) : Message()

    companion object {
        const val PROTOCOL_VERSION = 1

        /** Rohdaten pro Bild-Chunk; Base64 + JSON bleiben so unter dem 32-KB-Limit von Nearby. */
        const val CHUNK_BYTES = 16 * 1024

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "t"
        }

        fun encode(message: Message): String = json.encodeToString(serializer(), message)
        fun decode(text: String): Message = json.decodeFromString(serializer(), text)
    }
}

/** Setzt in Chunks übertragene Bilder wieder zusammen. */
class ImageAssembler {
    private val parts = HashMap<String, Array<ByteArray?>>()

    /** Liefert die vollständigen Bilddaten, sobald der letzte Chunk eingetroffen ist. */
    @Synchronized
    fun add(chunk: Message.ImageChunk): ByteArray? {
        if (chunk.total <= 0 || chunk.index !in 0 until chunk.total) return null
        val arr = parts.getOrPut(chunk.imageId) { arrayOfNulls(chunk.total) }
        if (arr.size != chunk.total) return null
        arr[chunk.index] = java.util.Base64.getDecoder().decode(chunk.base64)
        if (arr.any { it == null }) return null
        parts.remove(chunk.imageId)
        val out = java.io.ByteArrayOutputStream()
        arr.forEach { out.write(it!!) }
        return out.toByteArray()
    }

    companion object {
        fun split(imageId: String, data: ByteArray): List<Message.ImageChunk> {
            val total = maxOf(1, (data.size + Message.CHUNK_BYTES - 1) / Message.CHUNK_BYTES)
            val encoder = java.util.Base64.getEncoder()
            return (0 until total).map { i ->
                val from = i * Message.CHUNK_BYTES
                val to = minOf(data.size, from + Message.CHUNK_BYTES)
                Message.ImageChunk(imageId, i, total, encoder.encodeToString(data.copyOfRange(from, to)))
            }
        }
    }
}
