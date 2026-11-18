package pl.violetglass.launcher.apps

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

object AppSearch {
    fun normalize(text: String): String = Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT).replace('ł', 'l')

    fun matches(label: String, packageName: String, query: String): Boolean {
        val words = normalize(query).split("\\s+".toRegex()).filter(String::isNotEmpty)
        val haystack = normalize("$label $packageName")
        return words.all(haystack::contains)
    }

    fun sort(apps: List<LauncherApp>): List<LauncherApp> {
        val collator = Collator.getInstance(Locale.getDefault())
        return apps.sortedWith { a, b ->
            collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.key.compareTo(b.key)
        }
    }
}
