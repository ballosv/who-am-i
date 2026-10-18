package de.ballosv.whoami.content

import android.content.Context

/** Merkt sich pro Person, welche Fotos schon gezeigt wurden, damit möglichst ein anderes kommt. */
class PhotoHistory(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("photo_history", Context.MODE_PRIVATE)

    fun shown(person: String): Set<String> =
        sp.getString(person, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun record(person: String, file: String) {
        val updated = (sp.getString(person, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() && it != file } ?: emptyList()) + file
        sp.edit().putString(person, updated.takeLast(MAX_PER_PERSON).joinToString(SEPARATOR)).apply()
    }

    private companion object {
        const val SEPARATOR = "\n"
        const val MAX_PER_PERSON = 30
    }
}
