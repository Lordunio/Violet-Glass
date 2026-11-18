package pl.violetglass.launcher.discover

import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.violetglass.launcher.settings.DiscoverProvider
import java.security.MessageDigest
import androidx.core.net.toUri

/** Experimental interoperability with the installed provider, without root, reflection or auth injection.
 * Protocol names/transaction positions checked against launcherclient and AIDLBridge interfaces.
 * This is not a public Google SDK. The provider decides whether to accept this client.
 */
class DiscoverOverlay(private val activity: Activity) : DiscoverConnection {
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(DiscoverStatus.CONNECTING)
    override val status = mutable.asStateFlow()
    private var bound = false
    private var running = false
    private var foreground = false
    private var generation = 0
    private var attempts = 0
    private var awaitingStatus = false
    private var connection: ServiceConnection? = null
    private var activeOverlayCallback: IBinder? = null
    private var remote: IBinder? = null
    private var attachment = 0
    private var progress = 0f
    private var lastStatus: Int? = null
    private var provider = DiscoverProvider.DIRECT
    private val nativeMotion = NativeOverlayMotion()
    private val nativeGesture get() = nativeMotion.dragging
    private var lastCommandAt = 0L
    private var nativeListener: (Float) -> Unit = {}
    private val edgeGesture = DiscoverEdgeGesture(activity)
    private var navigateLeft: (() -> Unit)? = null
    private var dragLeft: ((Float, Boolean) -> Unit)? = null
    private var draggingLeft = false
    private var covered = false
    private var layerListener: (() -> Unit)? = null
    override fun windowLayer(listener: (() -> Unit)?) { layerListener = listener }
    override fun coveredByHa(value: Boolean) { if(covered != value) {
        covered = value
        lastCommandAt = SystemClock.uptimeMillis()
        nativeMotion.cancel()
        if(value) { scrollSession.reset(); scroll(1f) }
        updateEdgeGesture()
    } }
    private val scrollSession = OverlayScrollSession()
    override fun leftDrag(listener: ((Float, Boolean) -> Unit)?) { dragLeft = listener; updateEdgeGesture() }
    override fun leftNavigation(listener: (() -> Unit)?) { navigateLeft = listener; updateEdgeGesture() }
    private fun updateEdgeGesture() {
        val listener = navigateLeft
        if(running && foreground && ((progress == 1f && !covered) || draggingLeft) && mutable.value == DiscoverStatus.READY && listener != null) edgeGesture.show(onDrag = dragLeft?.let { callback -> { fraction, released ->
            draggingLeft = !released
            nativeMotion.cancel()
            lastCommandAt = SystemClock.uptimeMillis()
            callback(fraction, released)
            if(released) updateEdgeGesture()
        } }) {
            lastCommandAt = SystemClock.uptimeMillis(); edgeGesture.hide(); listener()
        } else edgeGesture.hide()
    }
    override fun nativeScroll(listener: (Float) -> Unit) { nativeListener = listener }
    private val timeout = Runnable {
        if(running && foreground && (awaitingStatus || mutable.value != DiscoverStatus.READY)) {
            if(attempts < 2) connect(provider, retry = true) else fail("no_content_callback")
        }
    }
    private fun fail(reason: String, status: DiscoverStatus = DiscoverStatus.UNAVAILABLE) {
        handler.removeCallbacks(timeout)
        val recover = running && foreground && attempts < 2 && status == DiscoverStatus.UNAVAILABLE &&
            reason in setOf("bridge_disconnected", "service_disconnected", "binding_died", "remote_failed")
        mutable.value = if(recover) DiscoverStatus.CONNECTING else status
        if(recover) {
            val epoch = generation
            handler.postDelayed({ if(running && foreground && generation == epoch) connect(provider, retry = true) }, 300)
        }
        Log.i("VioletDiscover", "${provider.name}: $reason")
    }
    private fun overlayCallback(epoch: Int) = object : Binder() {
        init { attachInterface(null, CALLBACK) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if(code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
            if(epoch != generation) return true
            data.enforceInterface(CALLBACK)
            when(code) {
                1 -> { val value = data.readFloat().coerceIn(0f, 1f); handler.post {
                    if(running && foreground && epoch == generation && mutable.value == DiscoverStatus.READY) {
                        val previousNative = nativeMotion.previous
                        if (pl.violetglass.launcher.BuildConfig.DEBUG) Log.d("VioletMotion", "native=$value previous=$previousNative command=$progress age=${SystemClock.uptimeMillis() - lastCommandAt} tracking=$nativeGesture covered=$covered left=$draggingLeft")
                        nativeMotion.update(value, progress, covered || draggingLeft)?.let(nativeListener)
                    }
                } }
                2 -> { val state = data.readInt(); handler.post {
                    if(running && foreground && epoch == generation) {
                        awaitingStatus = false
                        if(lastStatus != state) Log.i("VioletDiscover", "${provider.name}: overlay_status=$state")
                        lastStatus = state
                        val wasReady = mutable.value == DiscoverStatus.READY
                        mutable.value = if(state and 1 != 0) DiscoverStatus.READY else DiscoverStatus.UNAVAILABLE
                        if(mutable.value == DiscoverStatus.READY) {
                            attempts = 0
                            handler.removeCallbacks(timeout)
                            if(!wasReady) scrollSession.reset()
                            scroll(progress)
                        }
                    }
                } }
            }
            return true
        }
    }
    private fun bridgeCallback(epoch: Int) = object : Binder() {
        init { attachInterface(null, BRIDGE_CALLBACK) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if(code == INTERFACE_TRANSACTION) { reply?.writeString(BRIDGE_CALLBACK); return true }
            if(epoch != generation) return true
            data.enforceInterface(BRIDGE_CALLBACK)
            if(data.readInt() != 0) ComponentName.CREATOR.createFromParcel(data)
            if(code == 1) { val service = data.readStrongBinder(); handler.post { if(running && epoch == generation) attach(service) } }
            else if(code == 2) handler.post { if(running && epoch == generation) { remote = null; fail("bridge_disconnected") } }
            return true
        }
    }
    private fun serviceConnection(epoch: Int) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if(!running || epoch != generation) return
            if(provider == DiscoverProvider.DIRECT) attach(service)
            else transact(service, "amirz.aidlbridge.IBridge", 1) { writeStrongBinder(bridgeCallback(epoch)); writeInt(Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT) }
        }
        override fun onServiceDisconnected(name: ComponentName) { if(epoch == generation) { remote = null; if(running) fail("service_disconnected") } }
        override fun onNullBinding(name: ComponentName) { if(running && epoch == generation) fail("provider_declined_binding", if(provider == DiscoverProvider.DIRECT) DiscoverStatus.BRIDGE_REQUIRED else DiscoverStatus.UNAVAILABLE) }
        override fun onBindingDied(name: ComponentName) { if(running && epoch == generation) fail("binding_died") }
    }

    override fun start(value: DiscoverProvider) { connect(value, retry = false) }
    private fun connect(value: DiscoverProvider, retry: Boolean) {
        if(!retry) attempts = 0
        val selected = if(trustedBridge()) DiscoverProvider.BRIDGE else value
        val existing = remote
        if(!retry && running && bound && provider == selected && existing?.isBinderAlive == true) {
            foreground = true
            mutable.value = DiscoverStatus.CONNECTING
            attach(existing)
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, 6000)
            return
        }
        stop()
        attempts++
        provider = selected
        running = true; foreground = true; lastStatus = null; mutable.value = DiscoverStatus.CONNECTING
        val epoch = generation
        activeOverlayCallback = overlayCallback(epoch)
        val google = runCatching { activity.packageManager.getApplicationInfo("com.google.android.googlequicksearchbox", 0).enabled }.getOrDefault(false)
        if(!google) { fail("google_missing_or_disabled", DiscoverStatus.GOOGLE_REQUIRED); return }
        if(provider == DiscoverProvider.BRIDGE && !trustedBridge()) { fail("bridge_missing_or_signature_mismatch", DiscoverStatus.BRIDGE_REQUIRED); return }
        val transportPrefs = activity.getSharedPreferences("discover_transport", Context.MODE_PRIVATE)
        val version = OverlayProtocol.nextVersion(transportPrefs.getInt("last_version", 9))
        transportPrefs.edit().putInt("last_version", version).apply()
        val intent = Intent("com.android.launcher3.WINDOW_OVERLAY")
            .setPackage(if(provider == DiscoverProvider.BRIDGE) "amirz.aidlbridge" else "com.google.android.googlequicksearchbox")
            // Google 17.74 caches its Binder by protocol version, including after
            // onUnbind closes it. A different supported version forces a fresh
            // endpoint. Both 8 and 9 use the int-status callback and methods 14/16.
            .setData("app://${activity.packageName}:${Process.myUid()}?v=$version&cv=14&session=${java.util.UUID.randomUUID()}".toUri())
        // Let the previous service's asynchronous onUnbind finish before the
        // new endpoint is created; Google's onUnbind closes its global cache.
        handler.postDelayed({
            if(!running || !foreground || generation != epoch) return@postDelayed
            try {
                val nextConnection = serviceConnection(epoch)
                connection = nextConnection
                bound = activity.bindService(intent, nextConnection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
                if(!bound) fail("service_unavailable") else handler.postDelayed(timeout, 6000)
            } catch (_: SecurityException) { fail("binding_not_authorized") }
              catch (_: RuntimeException) { fail("binding_failed") }
        }, 250)
    }
    private fun trustedBridge(): Boolean = runCatching {
        val info = activity.packageManager.getPackageInfo("amirz.aidlbridge", PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo?.apkContentsSigners?.any { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } == BRIDGE_CERTIFICATE
        } == true
    }.getOrDefault(false)
    private fun attach(service: IBinder?) {
        if(service == null) { fail("empty_overlay"); return }
        try { if(service.interfaceDescriptor != OVERLAY) { fail("unexpected_interface", if(provider == DiscoverProvider.DIRECT) DiscoverStatus.BRIDGE_REQUIRED else DiscoverStatus.UNAVAILABLE); return } }
        catch (_: RemoteException) { fail("descriptor_unavailable"); return }
        remote = service
        nativeMotion.reset()
        awaitingStatus = true
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 6000)
        scrollSession.reset()
        val request = ++attachment
        activity.window.decorView.postDelayed({
            if(!running || !foreground || remote !== service || attachment != request) return@postDelayed
            // Reattach on resume without destroying the provider's window first.
            exchangeFrame(service)
            for(delay in listOf(150L, 450L)) handler.postDelayed({
                if(running && foreground && remote === service && attachment == request) {
                    layerListener?.invoke()
                    edgeGesture.raise()
                }
            }, delay)
            Log.i("VioletDiscover", "${provider.name}: window_attached")
        }, 200)
    }
    override fun frame(left: Int, top: Int, width: Int, height: Int) {
        // Google's window is attached to the Activity, not to a Compose page.
        // Reattaching it for every pager layout destroys its content on ColorOS.
        // The provider owns system insets and its slide animation via onScroll.
    }
    private fun exchangeFrame(service: IBinder) {
            val params = WindowManager.LayoutParams().apply {
                copyFrom(activity.window.attributes)
                flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                flags = flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER.inv()
                format = android.graphics.PixelFormat.OPAQUE
                packageName = activity.packageName
            }
            val bundle = Bundle().apply {
                putParcelable("layout_params", params)
                putParcelable("configuration", activity.resources.configuration)
                putInt("client_options", 15)
            }
            transact(service, OVERLAY, 14) { writeInt(1); bundle.writeToParcel(this, 0); writeStrongBinder(activeOverlayCallback) }
            transact(service, OVERLAY, 16) { writeInt(3) }
            // Some Google versions emit content status only after the first
            // scroll/open request. Waiting for READY here deadlocks startup.
            scroll(progress)
    }
    override fun scroll(value: Float) {
        if (pl.violetglass.launcher.BuildConfig.DEBUG) Log.d("VioletMotion", "command=$value tracking=$nativeGesture")
        progress = when { value <= .001f -> 0f; value >= .999f -> 1f; else -> value.coerceIn(0f, 1f) }
        updateEdgeGesture()
        if(nativeGesture || !foreground) return
        val service = remote ?: return
        lastCommandAt = SystemClock.uptimeMillis()
        scrollSession.commands(progress).forEach { command ->
            when(command) {
                2 -> transact(service, OVERLAY, 2) { writeFloat(progress) }
                9 -> transact(service, OVERLAY, 9) { writeInt(1) }
                6 -> transact(service, OVERLAY, 6) { writeInt(0) }
                else -> transact(service, OVERLAY, command)
            }
        }
    }
    override fun pause() {
        foreground = false
        attachment++
        handler.removeCallbacks(timeout)
        edgeGesture.hide()
        nativeMotion.cancel(); draggingLeft = false
        remote?.let { service ->
            transact(service, OVERLAY, 6) { writeInt(0) }
            transact(service, OVERLAY, 16) { writeInt(0) }
        }
        // Keep the binding/window for this Activity. Detaching on every ON_STOP
        // leaves Google's cached client without a window on later resumes.
    }
    override fun stop() {
        generation++
        attachment++
        edgeGesture.hide()
        running = false; foreground = false; handler.removeCallbacks(timeout)
        remote?.let { service ->
            transact(service, OVERLAY, 6) { writeInt(0) }
            transact(service, OVERLAY, 16) { writeInt(0) }
            transact(service, OVERLAY, 5) { writeInt(if(activity.isChangingConfigurations) 1 else 0) }
        }
        remote = null; activeOverlayCallback = null; awaitingStatus = false; nativeMotion.reset(); draggingLeft = false; scrollSession.reset()
        if(bound) connection?.let { oldConnection -> runCatching { activity.unbindService(oldConnection) } }
        connection = null
        bound = false
    }
    private fun transact(service: IBinder, descriptor: String, code: Int, body: Parcel.() -> Unit = {}) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor); body(data)
            if(!service.transact(code, data, null, IBinder.FLAG_ONEWAY) && running) fail("unsupported_method_$code")
        } catch (_: RemoteException) { if(running) fail("remote_failed") }
          catch (_: RuntimeException) { if(running) fail("protocol_rejected") }
        finally { data.recycle() }
    }
    companion object {
        private const val OVERLAY = "com.google.android.libraries.launcherclient.ILauncherOverlay"
        private const val CALLBACK = "com.google.android.libraries.launcherclient.ILauncherOverlayCallback"
        private const val BRIDGE_CALLBACK = "amirz.aidlbridge.IBridgeCallback"
        private const val BRIDGE_CERTIFICATE = "33c21ddf39dd093c75ace374155a5c42be69a31dba65614d28b3886dc53335f9"
    }
}

