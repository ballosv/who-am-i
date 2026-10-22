package de.ballosv.whoami

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Einladungslinks (whoami://join?code=ABCD), die die App öffnen; die Oberfläche reagiert darauf. */
object DeepLinks {
    private val _joinCode = MutableStateFlow<String?>(null)
    val joinCode: StateFlow<String?> = _joinCode

    fun handle(url: String) {
        if (!url.startsWith("whoami://join")) return
        _joinCode.value = url.substringAfter("code=", "").substringBefore('&').ifBlank { null } ?: ""
    }

    fun consume() {
        _joinCode.value = null
    }
}
