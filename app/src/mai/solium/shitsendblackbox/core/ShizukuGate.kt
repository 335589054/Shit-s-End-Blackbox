package mai.solium.shitsendblackbox.core

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Shizuku 状态机 + 权限申请。 */
object ShizukuGate {
    const val REQ_PERMISSION = 4001
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    enum class State(val label: String) {
        NOT_INSTALLED("未安装 Shizuku"),
        NOT_RUNNING("Shizuku 未运行"),
        NO_PERMISSION("未授权"),
        READY("已就绪")
    }

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    } catch (_: Throwable) {
        false
    }

    fun state(ctx: Context): State {
        if (!isInstalled(ctx)) return State.NOT_INSTALLED
        val running = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!running) return State.NOT_RUNNING
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        return if (granted) State.READY else State.NO_PERMISSION
    }

    fun requestPermission(): Boolean = try {
        if (!Shizuku.pingBinder()) false
        else {
            Shizuku.requestPermission(REQ_PERMISSION)
            true
        }
    } catch (_: Throwable) {
        false
    }

    fun version(): Int = try { Shizuku.getVersion() } catch (_: Throwable) { -1 }
    fun serverUid(): Int = try { Shizuku.getUid() } catch (_: Throwable) { -1 }
    fun selinux(): String = try { Shizuku.getSELinuxContext() } catch (_: Throwable) { "?" }
}
