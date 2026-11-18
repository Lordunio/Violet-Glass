package pl.violetglass.launcher

import android.app.role.RoleManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import pl.violetglass.launcher.home.LauncherViewModel
import pl.violetglass.launcher.ui.LauncherScreen
import pl.violetglass.launcher.ui.VioletTheme
import pl.violetglass.launcher.ui.LocalLauncherPreferences
import pl.violetglass.launcher.ui.LocalWidgetController
import pl.violetglass.launcher.widgets.*
import pl.violetglass.launcher.gestures.SystemActions
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import pl.violetglass.launcher.ui.BackupPicker
import pl.violetglass.launcher.ui.LocalBackupPicker
import pl.violetglass.launcher.ui.BackupProgress

class MainActivity : ComponentActivity() {
    private val model: LauncherViewModel by viewModels()
    lateinit var widgets: WidgetController
        private set
    lateinit var systemActions: SystemActions
        private set
    private val widgetBind = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { widgets.onBindResult(it.resultCode) }
    private var homeEvent by mutableIntStateOf(0)
    private var isDefault by mutableStateOf(false)
    private var lightSystemBarIcons = true
    private val homeRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { updateRole() }
    private val backupExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(model::exportBackup) }
    private val backupImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::inspectBackup) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!model.hasHostedActivity && intent.hasCategory(Intent.CATEGORY_HOME)) homeEvent = 1
        model.hasHostedActivity = true
        widgets = WidgetController(this, model) { widgetBind.launch(it) }
        systemActions = SystemActions(this)
        widgets.resumeCommitIfNeeded()
        val initialBars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = initialBars, navigationBarStyle = initialBars)
        setContent {
            val preferences by model.preferences.collectAsStateWithLifecycle()
            LaunchedEffect(preferences.autoRotate) {
                val orientation = if (preferences.autoRotate) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                if (requestedOrientation != orientation) requestedOrientation = orientation
            }
            LaunchedEffect(preferences.systemWallpaper) {
                if (preferences.systemWallpaper) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                window.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
            }
            CompositionLocalProvider(LocalLauncherPreferences provides preferences, LocalWidgetController provides widgets,
                LocalBackupPicker provides BackupPicker(
                    export = { runCatching { backupExport.launch("Violet-Glass-${java.time.LocalDate.now()}.json") }.onFailure { model.notify(R.string.backup_picker_error) } },
                    open = { runCatching { backupImport.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }.onFailure { model.notify(R.string.backup_picker_error) } })) {
                VioletTheme(preferences.theme) {
                    pl.violetglass.launcher.ui.GlassHost {
                    LauncherScreen(model, homeEvent, isDefault, ::requestHome, ::openHomeSettings, systemActions)
                    BackupProgress(model)
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); widgets.start() }
    override fun onStop() { widgets.stop(); super.onStop() }
    override fun onDestroy() { widgets.destroy(); super.onDestroy() }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        model.repository.trimMemory(level)
    }

    // AppWidgetHost has no public ActivityResultContract/IntentSender accessor for protected configure activities.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == WIDGET_CONFIGURE_REQUEST) widgets.onConfigurationResult(resultCode)
        else super.onActivityResult(requestCode, resultCode, data)
    }
    override fun onResume() {
        super.onResume()
        restoreSystemBars()
        updateRole()
        model.refresh()
        model.reconcilePendingUninstall()
        pl.violetglass.launcher.notifications.BadgeStore.refreshAccess(this)
    }

    /** One owner for this Activity window. Never restore a snapshot from a previous screen. */
    internal fun setSystemBarIcons(light: Boolean) {
        lightSystemBarIcons = light
        restoreSystemBars()
    }

    private fun restoreSystemBars() {
        val view = window.decorView
        if(!view.isAttachedToWindow) return
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !lightSystemBarIcons
            isAppearanceLightNavigationBars = !lightSystemBarIcons
            show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus) restoreSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.post { if(!isDestroyed) restoreSystemBars() }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeEvent++
    }

    private fun updateRole() {
        val roles = getSystemService(RoleManager::class.java)
        isDefault = roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME)
    }

    private fun requestHome() {
        val roles = getSystemService(RoleManager::class.java)
        if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            try { homeRole.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
            catch (_: RuntimeException) { openHomeSettings() }
        } else openHomeSettings()
    }

    private fun openHomeSettings() {
        try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        catch (_: RuntimeException) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            catch (_: RuntimeException) { Toast.makeText(this, R.string.settings_error, Toast.LENGTH_LONG).show() }
        }
    }
}







