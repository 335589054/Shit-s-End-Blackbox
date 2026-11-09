package mai.solium.shitsendblackbox.data

data class AppInfo(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val versionName: String,
    val versionCode: Long,
    val uid: Int,
    val hasInternet: Boolean,
    val hasLauncher: Boolean,
    val permissions: List<String>,
    val risk: Int,
    val reasons: List<String>
) {
    val installed: Boolean get() = uid > 0
}

data class OpResult(val ok: Boolean, val message: String, val detail: String = "")
