package mai.solium.shitsendblackbox.network

import mai.solium.shitsendblackbox.core.Logx
import mai.solium.shitsendblackbox.core.Shell
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 通过 Shizuku（shell）读 /proc/net/tcp、tcp6，把「远端地址:端口」映射到 uid。
 * 全隧道模式下，App 的 socket 远端就是真实目标地址，因此 SOCKS5 里拿到的
 * 目标地址可以反查所属应用，用于报告里的“应用流量 / 私有 DoH 归属”。
 */
object UidMap {

    private val map = ConcurrentHashMap<String, Int>()
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var lastOk = false
        private set
    private var emptyLogs = 0
    private var missLogs = 0

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            while (running) {
                refresh()
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.name = "uidmap"; it.start() }
    }

    fun stop() {
        running = false
        try { thread?.interrupt() } catch (_: Throwable) {}
        thread = null
        map.clear()
    }

    /** 先查缓存；查不到立刻同步刷一次（新建连接往往还是 SYN_SENT，2 秒一轮的缓存来不及）。 */
    fun uidFor(ip: String, port: Int): Int {
        map["$ip:$port"]?.let { return it }
        repeat(3) {
            refresh()
            map["$ip:$port"]?.let { return it }
            try { Thread.sleep(40) } catch (_: InterruptedException) { return -1 }
        }
        if (missLogs++ < 5) Logx.d("UidMap 未命中 $ip:$port，缓存 ${map.size} 条")
        return -1
    }

    fun size(): Int = map.size

    private fun refresh() {
        val r = Shell.exec("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null", 4000)
        if (!r.ok) {
            lastOk = false
            Logx.d("UidMap 读取失败 code=${r.code} err=${r.err.take(120)}")
            return
        }
        lastOk = true
        val fresh = HashMap<String, Int>()
        for (line in r.out.lineSequence()) {
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 10) continue
            if (!f[0].endsWith(":")) continue
            // 01=ESTABLISHED 02=SYN_SENT 03=SYN_RECV（新连接常常还在 SYN_SENT）
            if (f[3] != "01" && f[3] != "02" && f[3] != "03") continue
            val uid = f[7].toIntOrNull() ?: continue
            if (uid <= 0) continue
            val ep = decode(f[2]) ?: continue
            fresh[ep] = uid
        }
        map.clear()
        map.putAll(fresh)
        if (fresh.isEmpty() && emptyLogs++ < 1) Logx.d("UidMap 空：out=${r.out.length}B 行=${r.out.count { it == '\n' }}")
    }

    /** "0100007F:1F90" -> "127.0.0.1:8080"；IPv6 为 32 位十六进制（每 8 位一组小端）。 */
    private fun decode(hex: String): String? {
        val i = hex.indexOf(':')
        if (i <= 0) return null
        val addr = hex.substring(0, i)
        val port = hex.substring(i + 1).toIntOrNull(16) ?: return null
        return try {
            when (addr.length) {
                8 -> {
                    val b = ByteArray(4)
                    for (k in 0 until 4) {
                        b[k] = Integer.parseInt(addr.substring((3 - k) * 2, (3 - k) * 2 + 2), 16).toByte()
                    }
                    InetAddress.getByAddress(b).hostAddress + ":" + port
                }
                32 -> {
                    val b = ByteArray(16)
                    for (g in 0 until 4) {
                        val part = addr.substring(g * 8, g * 8 + 8)
                        for (k in 0 until 4) {
                            b[g * 4 + k] = Integer.parseInt(part.substring((3 - k) * 2, (3 - k) * 2 + 2), 16).toByte()
                        }
                    }
                    val s = InetAddress.getByAddress(b).hostAddress ?: return null
                    s.substringBefore('%') + ":" + port
                }
                else -> null
            }
        } catch (t: Throwable) {
            Logx.w("UidMap 解析失败 $hex: ${t.message}")
            null
        }
    }
}