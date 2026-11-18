package pl.violetglass.launcher.backup

data class BackupState(val preview: BackupDocument? = null, val busy: Boolean = false, val message: Int? = null)
