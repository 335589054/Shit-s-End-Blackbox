package mai.solium.shitsendblackbox.core

import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IRemoteProcess
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.io.ByteArrayOutputStream
import java.io.InputStream

data class CmdResult(
    val code: Int,
    val out: String,
    val err: String,
    val timedOut: Boolean = false
) {
    val ok: Boolean get() = code == 0 && !timedOut
    fun summary(): String = (out.trim().ifEmpty { err.trim() }).take(400)
}

/** 所有特权命令的唯一出口：Shizuku.newProcess("sh -c ...")。 */
object Shell {
    @Volatile
    var lastError: String? = null

    fun service(): IShizukuService? {
        return try {
            if (!Shizuku.pingBinder()) {
                lastError = "Shizuku 服务未运行"
                null
            } else {
                val binder = Shizuku.getBinder() ?: throw IllegalStateException("Shizuku binder 为空")
                IShizukuService.Stub.asInterface(ShizukuBinderWrapper(binder))
            }
        } catch (t: Throwable) {
            lastError = "Shizuku 连接失败: ${t.message}"
            null
        }
    }

    fun available(): Boolean = service() != null

    fun exec(cmd: String, timeoutMs: Long = 20_000): CmdResult {
        val svc = service() ?: return CmdResult(-1, "", lastError ?: "Shizuku 不可用")
        var proc: IRemoteProcess? = null
        return try {
            proc = svc.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val t1 = pump(proc.getInputStream(), out)
            val t2 = pump(proc.getErrorStream(), err)
            var code = -2
            var timedOut = false
            val waiter = Thread {
                try {
                    code = proc.waitFor()
                } catch (_: Throwable) {
                }
            }
            waiter.isDaemon = true
            waiter.start()
            waiter.join(timeoutMs)
            if (waiter.isAlive) {
                timedOut = true
                try { proc.destroy() } catch (_: Throwable) {}
                waiter.join(2000)
            }
            t1.join(2000)
            t2.join(2000)
            CmdResult(if (timedOut) -3 else code, out.toString("UTF-8"), err.toString("UTF-8"), timedOut)
        } catch (t: Throwable) {
            lastError = t.message
            CmdResult(-1, "", "执行失败: ${t.message}")
        } finally {
            try { proc?.destroy() } catch (_: Throwable) {}
        }
    }

    private fun pump(pfd: ParcelFileDescriptor, sink: ByteArrayOutputStream): Thread {
        val t = Thread {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input: InputStream ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        synchronized(sink) { sink.write(buf, 0, n) }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }
}
