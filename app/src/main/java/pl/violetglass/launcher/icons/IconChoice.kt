package pl.violetglass.launcher.icons

/** Empty package and drawable explicitly select the system icon. No override selects the global pack. */
data class IconChoice(val pack: String = "", val drawable: String = "") {
    fun valid() = (pack.isEmpty() && drawable.isEmpty()) || (validPackage(pack) && validDrawable(drawable))
}
fun validPackage(value: String) = value.length <= 256 && value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
fun validDrawable(value: String) = value.length in 1..200 && value.matches(Regex("[a-z_][a-z0-9_]*"))
fun encodeIconChoice(appKey: String, choice: IconChoice): String {
    require(appKey.length in 1..500 && appKey.none { it.isISOControl() } && choice.valid())
    return "$appKey\t${choice.pack}\t${choice.drawable}"
}
fun decodeIconChoices(values: Set<String>): Map<String, IconChoice> = buildMap {
    values.sorted().forEach { value ->
        val parts = value.split('\t')
        if (parts.size == 3) {
            val choice = IconChoice(parts[1], parts[2])
            if (runCatching { encodeIconChoice(parts[0], choice) == value }.getOrDefault(false)) put(parts[0], choice)
        }
    }
}
