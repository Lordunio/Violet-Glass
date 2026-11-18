package pl.violetglass.launcher.settings

internal const val MAX_DRAWER_CATEGORIES = 20
internal const val MAX_CATEGORY_NAME = 40
internal const val MAX_CATEGORY_APPS = 200

internal fun encodeDrawerCategories(categories: Map<String, Set<String>>): Set<String> = buildSet {
    val seen = mutableSetOf<String>()
    for((name, apps) in categories.toSortedMap(String.CASE_INSENSITIVE_ORDER)) {
        if(seen.size >= MAX_DRAWER_CATEGORIES) break
        if(name.isBlank() || name != name.trim() || name.length > MAX_CATEGORY_NAME ||
            name.any(Char::isISOControl) || !seen.add(name.lowercase())) continue
        add(name)
        apps.asSequence().filter { it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl) }
            .sorted().take(MAX_CATEGORY_APPS).forEach { add("$name\t$it") }
    }
}

internal fun decodeDrawerCategories(entries: Set<String>): Map<String, Set<String>> {
    val categories = entries.asSequence().filter { '\t' !in it }
        .associateWith { mutableSetOf<String>() }
    for(entry in entries) {
        val name = entry.substringBefore('\t')
        if(name != entry) categories[name]?.add(entry.substringAfter('\t'))
    }
    val normalized = linkedMapOf<String, MutableSet<String>>()
    for(entry in encodeDrawerCategories(categories)) {
        val name = entry.substringBefore('\t')
        val apps = normalized.getOrPut(name) { mutableSetOf() }
        if(name != entry) apps.add(entry.substringAfter('\t'))
    }
    return normalized
}
