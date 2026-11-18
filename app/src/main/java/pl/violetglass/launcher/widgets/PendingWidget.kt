package pl.violetglass.launcher.widgets

import android.os.Bundle

data class PendingWidget(
    val id: Int, val provider: String, val label: String, val page: Int,
    val spanX: Int, val spanY: Int, val replaceId: String? = null,
    val stage: String = "bind", val configureOnly: Boolean = false,
) {
    fun bundle() = Bundle().apply {
        putInt("id", id); putString("provider", provider); putString("label", label); putInt("page", page)
        putInt("spanX", spanX); putInt("spanY", spanY); putString("replace", replaceId)
        putString("stage", stage); putBoolean("configureOnly", configureOnly)
    }
    companion object {
        fun from(bundle: Bundle) = PendingWidget(bundle.getInt("id"), bundle.getString("provider").orEmpty(),
            bundle.getString("label").orEmpty(), bundle.getInt("page"), bundle.getInt("spanX", 1), bundle.getInt("spanY", 1),
            bundle.getString("replace"), bundle.getString("stage") ?: "bind", bundle.getBoolean("configureOnly"))
    }
}
