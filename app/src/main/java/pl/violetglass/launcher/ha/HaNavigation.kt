package pl.violetglass.launcher.ha

import android.content.Intent
import java.net.URI

/** Never forward components, flags, extras or URI grants supplied by a page. */
internal fun resolveHaExternalTarget(value: String): String? {
    if(HaUrls.external(value)) return value
    if(!value.startsWith("intent:")) return null
    return runCatching {
        val intent = Intent.parseUri(value, Intent.URI_INTENT_SCHEME)
        if(intent.`package` != null && intent.`package` != "com.google.android.apps.maps") return null
        fun mapTarget(url: String?): String? {
            if(url == null || !HaUrls.external(url)) return null
            val uri = URI(url)
            return url.takeIf {
                uri.scheme in setOf("geo", "google.navigation") || uri.scheme == "https" &&
                    (uri.host in setOf("maps.google.com", "maps.app.goo.gl") || uri.host == "www.google.com" && uri.path.startsWith("/maps"))
            }
        }
        mapTarget(intent.dataString) ?: mapTarget(intent.getStringExtra("browser_fallback_url"))
    }.getOrNull()
}
