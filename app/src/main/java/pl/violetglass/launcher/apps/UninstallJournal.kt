package pl.violetglass.launcher.apps

import android.content.Context

/** A pending user request survives an Activity/process change during Android's dialog. */
object UninstallJournal {
    private fun store(context: Context) = context.getSharedPreferences("uninstall_request", Context.MODE_PRIVATE)
    fun pending(context: Context): String? = store(context).getString("package", null)
    fun begin(context: Context, packageName: String) { check(store(context).edit().putString("package", packageName).commit()) }
    fun clear(context: Context, packageName: String) {
        if (pending(context) == packageName) store(context).edit().remove("package").apply()
    }
}
