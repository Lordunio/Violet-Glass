package pl.violetglass.launcher.debug

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.net.toUri

/** Debug-only reproduction of Google's onUnbind closing its shared cached endpoint.
 * Never included in local/release APKs. No provider methods, user data or accounts are accessed.
 */
class OverlayUnbindProbeActivity : Activity() {
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            release()
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 700)
        }
        override fun onServiceDisconnected(name: ComponentName) { finish() }
        override fun onNullBinding(name: ComponentName) { release(); finish() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = intent.getIntExtra("protocolVersion", 8)
        if(version !in 8..9) { finish(); return }
        val request = Intent("com.android.launcher3.WINDOW_OVERLAY")
            .setPackage("com.google.android.googlequicksearchbox")
            .setData("app://$packageName:${Process.myUid()}?v=$version&cv=14&probe=${java.util.UUID.randomUUID()}".toUri())
        bound = bindService(request, connection, Context.BIND_AUTO_CREATE)
        if(!bound) finish()
    }
    private fun release() { if(bound) { unbindService(connection); bound = false } }
    override fun onDestroy() { release(); super.onDestroy() }
}
