package de.ballosv.whoami.core

/** Entfernt Akzente („é“ -> „e“); Buchstaben ohne Zerlegung (ß, ø …) behandelt [NameMatcher] vorher selbst. */
internal expect fun stripDiacritics(text: String): String
