package cn.musicpilgrimage.app.ui

/** Display-only punctuation; canonical titles, evidence and editable fields stay unchanged. */
internal fun formatWorkTitle(title: String): String {
    val value = title.trim()
    return if (value.isEmpty() || value.startsWith("《") && value.endsWith("》")) value else "《$value》"
}
