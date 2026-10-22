package de.ballosv.whoami.core

import java.text.Normalizer

internal actual fun stripDiacritics(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
