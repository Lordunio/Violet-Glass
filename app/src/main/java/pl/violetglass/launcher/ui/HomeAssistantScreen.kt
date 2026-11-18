package pl.violetglass.launcher.ui

import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import pl.violetglass.launcher.R
import pl.violetglass.launcher.ha.*
import pl.violetglass.launcher.settings.GlassTarget

@Composable
fun HomeAssistantScreen(settings: HaSettings, onHome: () -> Unit, onSettings: () -> Unit, active: Boolean = true, refreshKey: Int = 0) {
    val target = HaUrls.target(settings)
    var generation by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HaFailure?>(null) }
    var external by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf<HaWebView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val localPermission = "android.permission.ACCESS_LOCAL_NETWORK"
    val host = runCatching { java.net.URI(target ?: "").host }.getOrNull().orEmpty().removeSurrounding("[", "]")
    val needsLan = Build.VERSION.SDK_INT >= 37 && host !in listOf("127.0.0.1", "localhost", "::1") &&
        (settings.connection == HaConnection.LOCAL || HaUrls.privateHost(host))
    var lanGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, localPermission) == PackageManager.PERMISSION_GRANTED) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME)
            lanGranted = ContextCompat.checkSelfPermission(context, localPermission) == PackageManager.PERMISSION_GRANTED }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = active) { onHome() }
    LaunchedEffect(target, refreshKey) { error = null; loading = false }
    DisposableEffect(view, lifecycle, active) {
        val current = view
        if (!active) current?.onPause()
        else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { current?.resumeTimers(); current?.resumePage(); current?.onResume() }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { current?.onPause(); current?.pauseTimers() }
            if (event == Lifecycle.Event.ON_START && active) { current?.resumeTimers(); current?.resumePage(); current?.onResume() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Surface(Modifier.fillMaxSize().testTag("haScreen"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            when {
                target == null -> Column(Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.ha_invalid)); Text(stringResource(R.string.ha_settings_path))
                }
                needsLan && !lanGranted -> Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.ha_lan_hint))
                    Text(stringResource(R.string.ha_settings_path))
                }
                error != null -> Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(when(error!!) {
                        HaFailure.CERTIFICATE -> R.string.ha_certificate_error
                        HaFailure.RENDERER -> R.string.ha_renderer_error
                        HaFailure.ADDRESS -> R.string.ha_invalid
                        HaFailure.SERVER -> R.string.ha_server_error
                        else -> R.string.ha_connection_error
                    }), modifier = Modifier.testTag("haError"))
                    Text(stringResource(R.string.ha_settings_path))
                }
                else -> key(target, refreshKey, generation) {
                    AndroidView(factory = { nativeContext -> HaWebView(nativeContext, HaUrls.trustedOrigins(settings),
                        loading = { loading = it }, failure = { error = it; loading = false }, external = { external = it }).also {
                        view = it
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { it.open(target); if(!active) it.onPause() }
                        else { it.resumeUrl = target; it.suspendPage() }
                    } }, modifier = Modifier.weight(1f).fillMaxWidth().testTag("haWebView"),
                        onReset = null, onRelease = { it.release(); if (view === it) view = null })
                }
            }
        }
    }
    external?.let { value ->
        GlassAlertDialog(onDismissRequest = { external = null }, title = { Text(stringResource(R.string.ha_external_title)) },
            text = { Text(stringResource(R.string.ha_external_hint, runCatching { java.net.URI(value).host }.getOrNull() ?: stringResource(R.string.ha_external_app))) },
            confirmButton = { TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, value.toUri()).apply {
                    if(value.startsWith("http")) addCategory(Intent.CATEGORY_BROWSABLE)
                }) }.onFailure { android.widget.Toast.makeText(context, R.string.ha_external_missing, android.widget.Toast.LENGTH_LONG).show() }
                external = null
            }) { Text(stringResource(R.string.ha_browser)) } },
            dismissButton = { TextButton(onClick = { external = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
fun HaSettingsControls(settings: HaSettings, onSave: (HaSettings) -> Unit) {
    val context = LocalContext.current
    val localPermission = "android.permission.ACCESS_LOCAL_NETWORK"
    var lanGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, localPermission) == PackageManager.PERMISSION_GRANTED) }
    val lanRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { lanGranted = it }
    var local by remember(settings) { mutableStateOf(settings.localUrl) }
    var remote by remember(settings) { mutableStateOf(settings.remoteUrl) }
    var path by remember(settings) { mutableStateOf(settings.dashboardPath) }
    var invalid by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf(false) }
    var forgotten by remember { mutableStateOf(false) }
    Toggle(stringResource(R.string.ha_enabled), settings.enabled, "haEnabled") { onSave(settings.copy(enabled = it)) }
    Text(stringResource(R.string.ha_hint), style = MaterialTheme.typography.bodySmall)
    if(Build.VERSION.SDK_INT >= 37 && !lanGranted) {
        TextButton(onClick = { lanRequest.launch(localPermission) }, modifier = Modifier.testTag("haLanPermission")) { Text(stringResource(R.string.ha_lan_allow)) }
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }) { Text(stringResource(R.string.ha_lan_settings)) }
    }
    OutlinedTextField(local, { local = it }, label = { Text(stringResource(R.string.ha_local)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("haLocal"))
    OutlinedTextField(remote, { remote = it }, label = { Text(stringResource(R.string.ha_remote)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("haRemote"))
    OutlinedTextField(path, { path = it }, label = { Text(stringResource(R.string.ha_path)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("haPath"))
    if (invalid) Text(stringResource(R.string.ha_invalid), color = MaterialTheme.colorScheme.error)
    Button(onClick = {
        invalid = HaUrls.endpoint(local, false) == null || remote.isNotBlank() && HaUrls.endpoint(remote, true) == null || HaUrls.path(path) == null
        if (!invalid) onSave(settings.copy(localUrl = local.trim().trimEnd('/'), remoteUrl = remote.trim().trimEnd('/'), dashboardPath = HaUrls.path(path)!!))
    }, modifier = Modifier.testTag("haSave")) { Text(stringResource(R.string.save)) }
    HaConnection.entries.forEach { mode ->
        RadioRow(stringResource(if(mode == HaConnection.LOCAL) R.string.ha_use_local else R.string.ha_use_remote), settings.connection == mode, "haConnection:${mode.name}") {
            if (mode == HaConnection.LOCAL || HaUrls.endpoint(settings.remoteUrl, true) != null) onSave(settings.copy(connection = mode)) else invalid = true
        }
    }
    Text(stringResource(R.string.ha_http_hint), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { forget = true }, modifier = Modifier.testTag("haForget")) { Text(stringResource(R.string.ha_forget)) }
    if (forgotten) Text(stringResource(R.string.ha_forgotten))
    if (forget) GlassAlertDialog(onDismissRequest = { forget = false }, title = { Text(stringResource(R.string.ha_forget)) },
        text = { Text(stringResource(R.string.ha_forget_hint)) }, confirmButton = {
            TextButton(onClick = {
                CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData(); forgotten = true }
                forget = false
            }, modifier = Modifier.testTag("haConfirmForget")) { Text(stringResource(R.string.remove)) }
        }, dismissButton = { TextButton(onClick = { forget = false }) { Text(stringResource(R.string.cancel)) } })
}
