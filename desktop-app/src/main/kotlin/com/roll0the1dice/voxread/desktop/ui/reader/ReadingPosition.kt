package com.roll0the1dice.voxread.desktop.ui.reader

internal fun resolveSavedChapterIndex(hrefs: List<String>, savedHref: String, savedIndex: Int): Int {
    if (hrefs.isEmpty()) return 0
    fun clean(href: String) = href.trim().substringBefore('#').removePrefix("/")

    val target = clean(savedHref)
    if (target.isNotBlank()) {
        val paths = hrefs.map(::clean)
        paths.indexOf(target).takeIf { it >= 0 }?.let { return it }
        // A filename alone is safe only when it identifies a single spine item.
        val matches = paths.indices.filter {
            paths[it].substringAfterLast('/') == target.substringAfterLast('/')
        }
        matches.singleOrNull()?.let { return it }
    }
    return savedIndex.coerceIn(0, hrefs.lastIndex)
}
