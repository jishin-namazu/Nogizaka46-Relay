package com.nogirelay.app.translation

@Suppress("UNUSED_PARAMETER")
fun normalizeTranslationText(source: String?, translated: String?): String? {
    return translated?.takeIf { it.isNotBlank() }
}
