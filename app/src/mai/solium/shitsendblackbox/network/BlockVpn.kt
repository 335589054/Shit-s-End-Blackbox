package mai.solium.shitsendblackbox.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import mai.solium.shitsendblackbox.MainActivity
import mai.solium.shitsendblackbox.core.Logx
import mai.solium.shitsendblackbox.data.BlocklistStore
import mai.solium.shitsendblackbox.data.SettingsStore
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 分流式 VPN：只把「被屏蔽 IP」和「DNS 服务器地址」路由进 tun，
 * 其余流量走原网络，因此无需实现完整 TCP/IP 栈。
 *  - 命中黑名单域名 -> 直接应答 127.0.0.1 / ::1
 *  - 未命中 -> 转发到上游 DNS 并原样返回
 *  - 被屏蔽 IP 的数据包 -> 丢弃（进入 tun 后不转发即断）
 */
class BlockVpn : VpnService() {
    companion object {
        const val CHANNEL = "blackbox-vpn"
        const val NOTI_ID = 2101
        const val VIRTUAL_DNS = "10.111.222.2"
        @Volatile var running = false

        fun start(ctx: Context) {
            val i = Intent(ctx, BlockVpn::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            // 直接 stopService 在部分厂商系统上会被 BIND_VPN_SERVICE 权限拦截，改为显式命令
            Logx.d("请求停止 VPN")
            try {
                val i = Intent(ctx, BlockVpn::class.java).setAction(ACTION_STOP)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                Logx.d("已发送 STOP 命令，running=${running}")
            } catch (t: Throwable) {
                Logx.w("STOP 命令失败: ${t.message}")
                try {
                    Logx.d("stopService=${ctx.stopService(Intent(ctx, BlockVpn::class.java))}")
                } catch (e: Throwable) {
                    Logx.w("stopService 失败: ${e.message}")
                }
            }
        }
        const val ACTION_STOP = "mai.solium.shitsendblackbox.STOP"
    }

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var worker: Thread? = null
    private var domains: Set<String> = emptySet()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logx.d("onStartCommand action=${intent?.action} running=$running")
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        startForegroundSafe()
        if (!running) startTunnel()
        return START_STICKY
    }

    /** 真正拆掉隧道并退出；stopSelf() 单独调用在部分厂商系统上会残留服务。 */
    private fun shutdown() {
        Logx.d("执行 shutdown")
        running = false
        try { FullTunnel.stop() } catch (_: Throwable) {}
        try { worker?.interrupt() } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Throwable) {}
        tun = null
        SettingsStore.setFlag(this, SettingsStore.VPN_ON, false)
        try { stopForeground(true) } catch (_: Throwable) {}
        try { stopSelf() } catch (_: Throwable) {}
        Logx.d("VPN 已停止（shutdown）")
    }

    override fun onRevoke() {
        Logx.w("VPN 授权被撤销")
        shutdown()
    }

    override fun onDestroy() {
        running = false
        try { FullTunnel.stop() } catch (_: Throwable) {}
        try { worker?.interrupt() } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Throwable) {}
        tun = null
        SettingsStore.setFlag(this, SettingsStore.VPN_ON, false)
        Logx.d("VPN 已停止")
        super.onDestroy()
    }

    private fun startForegroundSafe() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL, "域名/IP 屏蔽", NotificationManager.IMPORTANCE_LOW)
            ch.description = "保持 DNS 污染与 IP 屏蔽生效"
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("史终黑盒正在屏蔽")
            .setContentText("DNS 污染与 IP 屏蔽运行中")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(NOTI_ID, n)
    }

    private fun startTunnel() {
        domains = BlocklistStore.activeDomains(this)
        val ips = BlocklistStore.activeIps(this)
        val full = SettingsStore.flag(this, SettingsStore.FULL_TUNNEL, false)
        val b = Builder()
            .setSession("史终黑盒")
            .setMtu(1500)
            .addAddress("10.111.222.1", 32)
            .addDnsServer(VIRTUAL_DNS)
        var ok = 0
        if (full) {
            // 全隧道：所有流量进 tun，交给 hev-socks5-tunnel + 内置 SOCKS5 服务端。
            // 必须把自己排除在外，否则本进程的 SOCKS5 出站 socket 会再次落回 tun（无限递归）；
            // 某些 ROM 上 VpnService.protect() 会返回 false，用 disallow 更可靠。
            try {
                b.addDisallowedApplication(packageName)
                Logx.d("已把自身排除出 VPN: $packageName")
            } catch (t: Throwable) {
                Logx.w("addDisallowedApplication 失败: ${t.message}")
            }
            b.addRoute("0.0.0.0", 0)
        } else {
            b.addRoute("10.111.222.0", 24)
            for (ip in ips) {
                try {
                    b.addRoute(ip.trim(), 32); ok++
                } catch (t: Throwable) {
                    Logx.w("无效屏蔽 IP $ip: ${t.message}")
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            b.setMetered(false)
        }
        tun = try { b.establish() } catch (t: Throwable) {
            Logx.e("VPN 建立失败", t); null
        }
        val fd = tun ?: run { stopSelf(); return }
        running = true
        SettingsStore.setFlag(this, SettingsStore.VPN_ON, true)
        Logx.d("VPN 已建立：域名 ${domains.size} 个，IP $ok 个，全隧道=$full")
        val f = fd
        if (full) {
            val started = FullTunnel.start(this, this, f.fd)
            Logx.d("全隧道模式启动=$started")
            if (!started) stopSelf()
        } else {
            worker = Thread { loop(f) }.also { it.isDaemon = true; it.start() }
        }
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        val upstream = SettingsStore.string(this, SettingsStore.DNS_UPSTREAM, "223.5.5.5")
        var lastN = -2
        Logx.d("VPN 循环启动 fd=${fd.fd} running=$running")
        while (running) {
            val n = try { input.read(buf) } catch (t: Throwable) {
                Logx.w("VPN 读取失败: ${t.javaClass.simpleName} ${t.message}")
                -1
            }
            lastN = n
            if (n < 0) break
            if (n == 0) {
                // 某些内核/模拟器上 tun 读会返回 0（无数据/非阻塞），稍等后重试
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                continue
            }
            val reply = try { handle(buf, n, upstream) } catch (t: Throwable) {
                null
            }
            if (reply != null) {
                try { output.write(reply) } catch (_: Throwable) {}
            }
        }
        Logx.w("VPN 读循环退出 lastN=$lastN running=$running")
    }

    private fun handle(pkt: ByteArray, len: Int, upstream: String): ByteArray? {
        if (len < 28) return null
        if ((pkt[0].toInt() shr 4) != 4) return null
        val ihl = (pkt[0].toInt() and 0x0f) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if (pkt[9].toInt() != 17) return null // 仅 UDP
        val src = pkt.copyOfRange(12, 16)
        val dst = pkt.copyOfRange(16, 20)
        val sp = u16(pkt, ihl)
        val dp = u16(pkt, ihl + 2)
        if (dp != 53) return null
        val udpLen = u16(pkt, ihl + 4)
        val end = minOf(len, ihl + udpLen)
        if (end <= ihl + 8) return null
        val dns = pkt.copyOfRange(ihl + 8, end)

        val q = parseQuestion(dns) ?: return null
        val blocked = BlocklistStore.matches(q.name, domains)
        val answer: ByteArray = if (blocked) {
            Logx.d("DNS 屏蔽 ${q.name}")
            blockedResponse(dns, q)
        } else {
            forward(dns, upstream) ?: return null
        }
        if (NetMon.active) {
            NetMon.record(q.name, if (blocked) "127.0.0.1" else firstAnswerIp(answer), blocked)
        }
        return ipUdpPacket(dst, src, 53, sp, answer)
    }

    /** 从 DNS 应答里取第一条 A/AAAA 记录（仅用于监控报告展示，解析失败返回 null）。 */
    private fun firstAnswerIp(dns: ByteArray): String? {
        if (dns.size < 12) return null
        val qd = u16(dns, 4)
        val an = u16(dns, 6)
        if (an <= 0) return null
        var p = 12
        repeat(qd) {
            while (p < dns.size) {
                val l = dns[p].toInt() and 0xff
                p++
                if (l == 0) break
                if (l and 0xc0 != 0) { p += 1; break }
                p += l
            }
            p += 4
        }
        for (i in 0 until an) {
            if (p >= dns.size) return null
            var guard = 0
            while (p < dns.size && guard++ < 64) {
                val l = dns[p].toInt() and 0xff
                if (l and 0xc0 == 0xc0) { p += 2; break }
                p++
                if (l == 0) break
                p += l
            }
            if (p + 10 > dns.size) return null
            val type = u16(dns, p)
            val rdlen = u16(dns, p + 8)
            p += 10
            if (p + rdlen > dns.size) return null
            if (type == 1 && rdlen == 4) {
                return "${dns[p].toInt() and 0xff}.${dns[p + 1].toInt() and 0xff}." +
                    "${dns[p + 2].toInt() and 0xff}.${dns[p + 3].toInt() and 0xff}"
            }
            if (type == 28 && rdlen == 16) {
                return try {
                    java.net.InetAddress.getByAddress(dns.copyOfRange(p, p + 16)).hostAddress
                } catch (_: Throwable) {
                    null
                }
            }
            p += rdlen
        }
        return null
    }

    private fun forward(query: ByteArray, upstream: String): ByteArray? {
        return try {
            DatagramSocket().use { s ->
                protect(s)
                val addr = InetAddress.getByName(upstream)
                s.soTimeout = 4000
                s.send(DatagramPacket(query, query.size, addr, 53))
                val buf = ByteArray(4096)
                val resp = DatagramPacket(buf, buf.size)
                s.receive(resp)
                buf.copyOf(resp.length)
            }
        } catch (t: Throwable) {
            Logx.w("DNS 转发失败: ${t.message}")
            null
        }
    }

    private data class Question(val name: String, val type: Int, val end: Int)

    private fun parseQuestion(dns: ByteArray): Question? {
        if (dns.size < 12) return null
        var i = 12
        val sb = StringBuilder()
        while (i < dns.size) {
            val l = dns[i].toInt() and 0xff
            i++
            if (l == 0) break
            if (l and 0xc0 != 0) return null // 不支持压缩指针
            if (i + l > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, i, l, Charsets.UTF_8))
            i += l
        }
        if (i + 4 > dns.size) return null
        val type = u16(dns, i)
        return Question(sb.toString(), type, i + 4)
    }

    private fun blockedResponse(query: ByteArray, q: Question): ByteArray {
        val qtype = q.type
        val out = ByteArray(12 + (q.end - 12) + 16)
        System.arraycopy(query, 0, out, 0, 12)
        out[2] = (0x81).toByte() // QR=1, RD=1
        out[3] = (0x80).toByte() // RA=1
        out[4] = 0; out[5] = 1 // QDCOUNT
        val hasAnswer = qtype == 1 || qtype == 28
        out[6] = 0; out[7] = if (hasAnswer) 1 else 0
        out[8] = 0; out[9] = 0
        out[10] = 0; out[11] = 0
        System.arraycopy(query, 12, out, 12, q.end - 12)
        if (!hasAnswer) return out.copyOf(12 + (q.end - 12))
        var p = 12 + (q.end - 12)
        out[p++] = 0xC0.toByte(); out[p++] = 0x0C
        out[p++] = 0; out[p++] = qtype.toByte()
        out[p++] = 0; out[p++] = 1 // class IN
        out[p++] = 0; out[p++] = 0; out[p++] = 0; out[p++] = 60 // TTL
        if (qtype == 1) {
            out[p++] = 0; out[p++] = 4
            out[p++] = 127; out[p++] = 0; out[p++] = 0; out[p++] = 1
        } else {
            out[p++] = 0; out[p++] = 16
            for (k in 0 until 15) out[p++] = 0
            out[p] = 1
        }
        return out
    }

    private fun ipUdpPacket(srcIp: ByteArray, dstIp: ByteArray, sp: Int, dp: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val out = ByteArray(total)
        out[0] = 0x45
        out[1] = 0
        out[2] = (total shr 8).toByte(); out[3] = total.toByte()
        out[4] = 0; out[5] = 0
        out[6] = 0x40; out[7] = 0
        out[8] = 64
        out[9] = 17
        out[10] = 0; out[11] = 0
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)
        val c = checksum(out, 0, 20)
        out[10] = (c shr 8).toByte(); out[11] = c.toByte()

        val u = 20
        out[u] = (sp shr 8).toByte(); out[u + 1] = sp.toByte()
        out[u + 2] = (dp shr 8).toByte(); out[u + 3] = dp.toByte()
        out[u + 4] = (udpLen shr 8).toByte(); out[u + 5] = udpLen.toByte()
        out[u + 6] = 0; out[u + 7] = 0
        System.arraycopy(payload, 0, out, u + 8, payload.size)

        // UDP 校验和（含伪首部）
        var sum = 0L
        sum += sum16(srcIp, 0, 4)
        sum += sum16(dstIp, 0, 4)
        sum += 17
        sum += udpLen
        for (i in u until total step 2) {
            val hi = out[i].toInt() and 0xff
            val lo = if (i + 1 < total) out[i + 1].toInt() and 0xff else 0
            sum += (hi shl 8) or lo
        }
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        val uc = (sum.inv() and 0xffff).toInt()
        out[u + 6] = (uc shr 8).toByte(); out[u + 7] = uc.toByte()
        return out
    }

    private fun sum16(b: ByteArray, off: Int, len: Int): Long {
        var s = 0L
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
            i += 2
        }
        if (i < end) s += (b[i].toInt() and 0xff) shl 8
        return s
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var s = 0L
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
            i += 2
        }
        if (i < end) s += (b[i].toInt() and 0xff) shl 8
        while (s shr 16 != 0L) s = (s and 0xffff) + (s shr 16)
        return (s.inv() and 0xffff).toInt()
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)
}
