package de.ballosv.whoami.content

import de.ballosv.whoami.platform.KeyValueStore

/** Merkt sich pro Person, welche Fotos schon gezeigt wurden, damit möglichst ein anderes kommt. */
class PhotoHistory {
    private val sp = KeyValueStore("photo_history")

    fun shown(person: String): Set<String> =
        sp.getString(person)?.split(SEPARATOR)?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun record(person: String, file: String) {
        val updated = (sp.getString(person)?.split(SEPARATOR)?.filter { it.isNotEmpty() && it != file } ?: emptyList()) + file
        sp.putString(person, updated.takeLast(MAX_PER_PERSON).joinToString(SEPARATOR))
    }

    private companion object {
        const val SEPARATOR = "\n"
        const val MAX_PER_PERSON = 30
    }
}
