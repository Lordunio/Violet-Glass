package pl.violetglass.launcher.icons

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.LruCache
import android.util.Xml
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

data class IconPack(val packageName: String, val label: String)
data class PackIndex(val components: Map<ComponentName, String>, val drawables: List<String>)

/** Reads foreign resources only; never loads pack code or starts its activities. */
class IconPacks(private val context: Context) {
    private val pm = context.packageManager
    private val indexes = LruCache<String, PackIndex>(3)
    suspend fun installed(): List<IconPack> = withContext(Dispatchers.IO) {
        ACTIONS.flatMap { action -> pm.queryIntentActivities(Intent(action), 0) }
            .distinctBy { it.activityInfo.packageName }.map {
                IconPack(it.activityInfo.packageName, it.loadLabel(pm).toString())
            }.sortedBy { it.label.lowercase() }
    }
    fun stamp(pack: String): String = if(pack.isEmpty()) "" else runCatching {
        val info = pm.getPackageInfo(pack, 0)
        "$pack:${info.lastUpdateTime}:${info.applicationInfo?.sourceDir}:${context.resources.configuration}"
    }.getOrDefault("$pack:missing")

    suspend fun index(pack: String): PackIndex = withContext(Dispatchers.IO) {
        if(!validPackage(pack)) return@withContext PackIndex(emptyMap(), emptyList())
        val key = stamp(pack)
        indexes[key] ?: runCatching {
            val resources = pm.getResourcesForApplication(pack)
            val components = linkedMapOf<ComponentName, String>()
            val drawables = linkedSetOf<String>()
            for(name in listOf("appfilter", "drawable")) {
                val id = resources.getIdentifier(name, "xml", pack)
                if(id != 0) resources.getXml(id).use { parse(it, components, drawables) }
                else if(name == "appfilter") runCatching {
                    resources.assets.open("appfilter.xml").use { input ->
                        // Bound text before parsing; external entities and declarations are rejected.
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while(true) {
                            val count = input.read(buffer)
                            if(count < 0) break
                            require(output.size() + count <= 2 * 1024 * 1024)
                            output.write(buffer, 0, count)
                        }
                        val bytes = output.toByteArray()
                        val text = bytes.toString(Charsets.UTF_8)
                        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true))
                        val parser = Xml.newPullParser()
                        parser.setInput(text.reader())
                        parse(parser, components, drawables)
                    }
                }
            }
            PackIndex(components, drawables.sorted()).also { indexes.put(key, it) }
        }.getOrDefault(PackIndex(emptyMap(), emptyList()))
    }
    private fun parse(parser: XmlPullParser, components: MutableMap<ComponentName, String>, drawables: MutableSet<String>) {
        var events = 0
        while(parser.eventType != XmlPullParser.END_DOCUMENT) {
            require(++events <= 200000 && drawables.size <= 30000)
            require(parser.eventType != XmlPullParser.DOCDECL)
            if(parser.eventType == XmlPullParser.START_TAG && parser.name == "item") {
                val drawable = parser.getAttributeValue(null, "drawable").orEmpty()
                if(validDrawable(drawable)) {
                    drawables.add(drawable)
                    val component = parser.getAttributeValue(null, "component").orEmpty()
                        .removePrefix("ComponentInfo{").removeSuffix("}")
                    ComponentName.unflattenFromString(component)?.let { components[it] = drawable }
                }
            }
            parser.next()
        }
    }
    suspend fun bitmap(pack: String, drawable: String): Bitmap? = withContext(Dispatchers.IO) {
        if(!IconChoice(pack, drawable).valid() || pack.isEmpty()) return@withContext null
        runCatching {
            val resources = pm.getResourcesForApplication(pack)
            val id = resources.getIdentifier(drawable, "drawable", pack)
            if(id == 0) null else ResourcesCompat.getDrawable(resources, id, null)?.toBitmap(144, 144)
        }.getOrNull()
    }
    companion object {
        val ACTIONS = listOf("com.novalauncher.THEME", "org.adw.launcher.THEMES", "com.anddoes.launcher.THEME")
    }
}
