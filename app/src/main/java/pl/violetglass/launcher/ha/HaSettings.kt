package pl.violetglass.launcher.ha

import java.net.URI

enum class HaConnection { LOCAL, REMOTE }
data class HaSettings(
    val enabled: Boolean = false,
    val localUrl: String = "",
    val remoteUrl: String = "",
    val dashboardPath: String = "/",
    val connection: HaConnection = HaConnection.LOCAL,
)
object HaUrls {
    fun external(value: String): Boolean = safeResource(value) || runCatching {
        val uri = URI(value)
        uri.scheme in setOf("geo", "google.navigation") && !uri.rawSchemeSpecificPart.isNullOrBlank() && !value.any { it.isISOControl() }
    }.getOrDefault(false)
    fun privateHost(host: String): Boolean {
        val h = host.lowercase().removeSurrounding("[", "]")
        if (h == "localhost" || h == "::1" || h.endsWith(".local") || h.startsWith("fd") && h.contains(':') || h.startsWith("fe80:") || h.startsWith("fc") && h.contains(':')) return true
        val octets = h.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 || octets[0] == 127 || octets[0] == 192 && octets[1] == 168 || octets[0] == 172 && octets[1] in 16..31
    }
    fun endpoint(value: String, remote: Boolean): URI? = runCatching {
        val uri = URI(value.trim())
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0)
        require(uri.scheme == "https" || !remote && privateHost(uri.host))
        require(!value.contains('\\') && !value.any { it.isISOControl() })
        uri
    }.getOrNull()
    fun path(value: String): String? = runCatching {
        val p = value.trim().ifBlank { "/" }
        val uri = URI(p)
        require(p.startsWith('/') && !p.startsWith("//") && uri.scheme == null && uri.rawAuthority == null && uri.rawQuery == null && uri.rawFragment == null)
        require(!p.contains('\\') && !p.any { it.isISOControl() } && uri.normalize().rawPath == uri.rawPath)
        p
    }.getOrNull()
    fun target(settings: HaSettings): String? {
        val remote = settings.connection == HaConnection.REMOTE
        val base = endpoint(if (remote) settings.remoteUrl else settings.localUrl, remote) ?: return null
        val path = path(settings.dashboardPath) ?: return null
        return base.toASCIIString().trimEnd('/') + path
    }
    fun origin(value: String): String? = runCatching { val u = URI(value); "${u.scheme.lowercase()}://${u.host.lowercase()}:${if(u.port != -1) u.port else if(u.scheme == "https") 443 else 80}" }.getOrNull()
    fun trustedOrigins(settings: HaSettings): Set<String> = listOfNotNull(endpoint(settings.localUrl, false)?.toString(), endpoint(settings.remoteUrl, true)?.toString()).mapNotNull(::origin).toSet()
    fun safeResource(value: String): Boolean = runCatching {
        val u = URI(value)
        u.rawUserInfo == null && !u.host.isNullOrBlank() && (u.scheme == "https" || u.scheme == "http" && privateHost(u.host))
    }.getOrDefault(false)
}
