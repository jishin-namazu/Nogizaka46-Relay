package com.nogirelay.app.translation

/** 返回译文，且不改变其受源码管理的格式。 */
@Suppress("UNUSED_PARAMETER")
fun normalizeTranslationText(source: String?, translated: String?): String? {
    return translated?.takeIf { it.isNotBlank() }
}
