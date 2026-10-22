package de.ballosv.whoami.core

import platform.Foundation.NSDiacriticInsensitiveSearch
import platform.Foundation.NSString
import platform.Foundation.stringByFoldingWithOptions

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun stripDiacritics(text: String): String =
    (text as NSString).stringByFoldingWithOptions(NSDiacriticInsensitiveSearch, locale = null)
