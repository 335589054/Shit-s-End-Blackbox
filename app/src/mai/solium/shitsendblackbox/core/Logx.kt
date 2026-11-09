package mai.solium.shitsendblackbox.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 应用内日志：环形缓冲 + 追加文件，便于诊断页展示与导出。 */
object Logx {
    private const val TAG = "ShiZhong"
    private const val MAX = 800
    private val ring = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    @Volatile private var file: File? = null

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "logs/blackbox.log")
        file?.parentFile?.mkdirs()
    }

    @Synchronized
    fun d(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        ring.addLast(line)
        while (ring.size > MAX) ring.removeFirst()
        Log.d(TAG, msg)
        try {
            file?.appendText(line + "\n")
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun e(msg: String, t: Throwable? = null) {
        d("E $msg" + (t?.let { ": ${it.message}" } ?: ""))
    }

    @Synchronized
    fun w(msg: String) {
        d("W $msg")
    }

    @Synchronized
    fun dump(): String = ring.joinToString("\n")

    @Synchronized
    fun clear() {
        ring.clear()
        try { file?.writeText("") } catch (_: Throwable) {}
    }
}
