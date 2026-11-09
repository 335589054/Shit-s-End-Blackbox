package mai.solium.shitsendblackbox.network

import android.content.Context
import mai.solium.shitsendblackbox.core.Logx
import mai.solium.shitsendblackbox.data.BlocklistStore
import mai.solium.shitsendblackbox.data.SettingsStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * 全隧道模式：把 0.0.0.0/0 全部送进 tun，由 hev-socks5-tunnel（native，lwIP）还原成 TCP/UDP，
 * 再交给本文件里的 SOCKS5 服务端落地。好处：
 *  - App 自带 DoH/DoT 也跑在这条隧道里，能看到它连的 SNI 域名（绕开系统 DNS 也能监控）
 *  - 可以按 SNI 直接断连，比 DNS 污染更难绕过
 *  - /proc/net/tcp 反查 uid，报告里能给出“哪个应用用了多少流量、谁在用私有 DoH”
 */
object FullTunnel {

    const val SOCKS_PORT = 1080
    const val VIRTUAL_DNS = "10.111.222.2"

    @Volatile private var server: ServerSocket? = null
    @Volatile private var udpRelay: DatagramSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var vpn: Any? = null
    private var protectFails = 0
    private var sniLogs = 0
    @Volatile var running = false
        private set

    private var domains: Set<String> = emptySet()
    private var upstream = "223.5.5.5"
    private var ctxRef: Context? = null

    /** uid -> 收发字节数（rx+tx） */
    private val appBytes = ConcurrentHashMap<Int, Long>()
    /** 已建立连接的 SOCKS5 客户端数量 */
    @Volatile var connCount = 0
        private set

    fun appBytesOf(uid: Int): Long = appBytes[uid] ?: 0L
    fun appTrafficMap(): Map<Int, Long> = HashMap(appBytes)

    /** 每包日志只记前 200 条，避免全隧道下大量 I/O。 */
    private val verboseLeft = java.util.concurrent.atomic.AtomicInteger(200)
    private fun vlog(msg: String) { if (verboseLeft.getAndDecrement() > 0) Logx.d(msg) }

    fun start(ctx: Context, vpnService: Any, fd: Int): Boolean {
        stop()
        ctxRef = ctx
        vpn = vpnService
        Logx.d("全隧道绑定 VPN 实例: ${vpnService.javaClass.name}")
        domains = BlocklistStore.activeDomains(ctx)
        upstream = SettingsStore.string(ctx, SettingsStore.DNS_UPSTREAM, "223.5.5.5")
        appBytes.clear()
        connCount = 0

        val cfg = File(ctx.filesDir, "hev-socks5-tunnel.yml")
        cfg.writeText(
            """
            tunnel:
              mtu: 1500
              ipv4: 10.111.222.1
              ipv6: 'fc00::1'
              multi-queue: false
            socks5:
              port: $SOCKS_PORT
              address: 127.0.0.1
              udp: 'udp'
            misc:
              log-level: warn
              connect-timeout: 10000
            """.trimIndent()
        )

        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), SOCKS_PORT), 64)
            server = s
            running = true
            // hev 会把 UDP 报文发回 TCP 对端地址(127.0.0.1:1080)或 BND 端口，这里用同一个 UDP 端口最稳
            udpRelay = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), SOCKS_PORT))
            Thread { udpLoop(udpRelay!!) }.also { it.isDaemon = true; it.name = "udp-relay"; it.start() }
            acceptThread = Thread { acceptLoop(s) }.also {
                it.isDaemon = true
                it.name = "socks5-accept"
                it.start()
            }
            UidMap.start()
            val ok = hev.htproxy.TProxyService.TProxyStartService(cfg.absolutePath, fd)
            Logx.d("全隧道启动 ok=$ok port=$SOCKS_PORT")
            ok
        } catch (t: Throwable) {
            Logx.e("全隧道启动失败", t)
            stop()
            false
        }
    }

    fun stop() {
        running = false
        try { hev.htproxy.TProxyService.TProxyStopService() } catch (_: Throwable) {}
        try { server?.close() } catch (_: Throwable) {}
        try { udpRelay?.close() } catch (_: Throwable) {}
        udpRelay = null
        try { acceptThread?.interrupt() } catch (_: Throwable) {}
        server = null
        acceptThread = null
        UidMap.stop()
        vpn = null
    }

    private val permits = java.util.concurrent.Semaphore(32)

    private fun protect(s: Socket): Boolean {
        val v = vpn as? android.net.VpnService
        if (v == null) { Logx.w("protect: vpn 实例为空 (${vpn?.javaClass})"); return false }
        return try {
            v.protect(s)
        } catch (t: Throwable) {
            Logx.w("protect 异常: ${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    private fun protect(s: DatagramSocket) {
        try { (vpn as? android.net.VpnService)?.protect(s) } catch (_: Throwable) {}
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val c = try { s.accept() } catch (t: Throwable) { break }
            Thread { handle(c) }.also { it.isDaemon = true; it.start() }
        }
        Logx.d("SOCKS5 接受循环退出")
    }

    private fun handle(client: Socket) {
        if (!permits.tryAcquire()) {
            try { client.close() } catch (_: Throwable) {}
            return
        }
        var remote: Socket? = null
        var relay: DatagramSocket? = null
        try {
            client.soTimeout = 20000
            val inS = client.getInputStream()
            val outS = client.getOutputStream()

            val ver = inS.read()
            if (ver != 5) { client.close(); return }
            val n = inS.read()
            if (n <= 0 || n > 32) { client.close(); return }
            val methods = ByteArray(n).also { readFully(inS, it) }
            if (!methods.contains(0)) {
                outS.write(byteArrayOf(5, 0xFF.toByte())); outS.flush(); client.close(); return
            }
            outS.write(byteArrayOf(5, 0.toByte())); outS.flush()

            // VER, CMD, RSV, ATYP
            val head = ByteArray(4)
            readFully(inS, head)
            if (head[0].toInt() != 5) { client.close(); return }
            val cmd = head[1].toInt() and 0xff
            vlog("SOCKS5 请求 cmd=$cmd")
            val atyp = head[3].toInt() and 0xff
            val host: String
            when (atyp) {
                1 -> {
                    val b = ByteArray(4); readFully(inS, b)
                    host = InetAddress.getByAddress(b).hostAddress ?: ""
                }
                3 -> {
                    val l = inS.read(); if (l <= 0) { client.close(); return }
                    val b = ByteArray(l); readFully(inS, b)
                    host = String(b, Charsets.UTF_8)
                }
                4 -> {
                    val b = ByteArray(16); readFully(inS, b)
                    host = InetAddress.getByAddress(b).hostAddress ?: ""
                }
                else -> { client.close(); return }
            }
            val pb = ByteArray(2); readFully(inS, pb)
            val port = ((pb[0].toInt() and 0xff) shl 8) or (pb[1].toInt() and 0xff)
            vlog("SOCKS5 目标 $host:$port cmd=$cmd")

            when (cmd) {
                1 -> {
                    // 指向虚拟 DNS 的 TCP 连接不能再去 connect（会重新落回 tun 造成递归）
                    if (host == BlockVpn.VIRTUAL_DNS) {
                        if (port == 53) {
                            reply(outS, 0x00, "10.111.222.2", 53)
                            tcpDns(inS, outS)
                        } else {
                            // 私有 DNS(DoT 853) 等：明确拒绝，让解析器回落到 UDP 53
                            Logx.d("拒绝虚拟 DNS 的非 53 端口: $port")
                            reply(outS, 0x05, "0.0.0.0", 0)
                        }
                        client.close()
                        return
                    }
                    val uid = UidMap.uidFor(host, port)
                    val blocked = BlocklistStore.matches(host, domains)
                    if (blocked) {
                        Logx.d("SNI/IP 屏蔽 $host:$port")
                        reply(outS, 0x02, "0.0.0.0", 0)
                        client.close()
                        return
                    }
                    vlog("TCP CONNECT $host:$port uid=$uid")
                    val r = Socket()
                    if (!protect(r) && protectFails++ < 3) Logx.d("protect 不可用（已排除自身，不影响）: $host:$port")
                    r.soTimeout = 20000
                    try {
                        r.connect(InetSocketAddress(host, port), 12000)
                    } catch (t: Throwable) {
                        Logx.w("SOCKS5 连接失败 $host:$port ${t.message}")
                        reply(outS, 0x04, "0.0.0.0", 0)
                        client.close()
                        return
                    }
                    r.soTimeout = 0
                    remote = r
                    val local = r.localAddress?.hostAddress ?: "127.0.0.1"
                    reply(outS, 0x00, local, r.localPort)
                    connCount++
                    pipe(client, inS, outS, r, uid, host, port)
                }
                3 -> {
                    vlog("UDP ASSOCIATE 建立")
                    val port = udpRelay?.localPort ?: SOCKS_PORT
                    reply(outS, 0x00, "127.0.0.1", port)
                    // 保持控制连接，直到对端关闭
                    try {
                        val b = ByteArray(64)
                        while (running && inS.read(b) >= 0) { /* ignore */ }
                    } catch (_: Throwable) {}
                    client.close()
                }
                else -> { reply(outS, 0x07, "0.0.0.0", 0); client.close() }
            }
        } catch (t: Throwable) {
            Logx.w("SOCKS5 会话异常: ${t.javaClass.simpleName} ${t.message}")
        } finally {
            try { remote?.close() } catch (_: Throwable) {}
            try { relay?.close() } catch (_: Throwable) {}
            try { client.close() } catch (_: Throwable) {}
            permits.release()
        }
    }

    private fun reply(out: OutputStream, code: Int, host: String, port: Int) {
        val b = InetAddress.getByName(if (host.isEmpty()) "0.0.0.0" else host).address
        val buf = ByteArray(4 + b.size + 2)
        buf[0] = 5; buf[1] = code.toByte(); buf[2] = 0
        buf[3] = if (b.size == 16) 4 else 1
        System.arraycopy(b, 0, buf, 4, b.size)
        buf[4 + b.size] = (port shr 8).toByte()
        buf[5 + b.size] = port.toByte()
        out.write(buf); out.flush()
    }

    /** 双向转发；同时偷看第一段数据拿 SNI，并统计该 uid 的流量。 */
    private fun pipe(client: Socket, cin: InputStream, cout: OutputStream, remote: Socket, uid: Int, host: String, port: Int) {
        val rin = remote.getInputStream()
        val rout = remote.getOutputStream()

        val first = ByteArray(1024)
        var firstLen = 0
        try {
            var acc = 0
            while (acc < first.size) {
                val n = cin.read(first, acc, first.size - acc)
                if (n <= 0) break
                acc += n
                if (TlsSni.looksLikeTls(first, acc)) {
                    val s = TlsSni.parse(first, acc)
                    if (s != null && s != "NOT_TLS") {
                        if (recordSni(s, host, uid)) {
                            // SNI 命中屏蔽清单：直接掐断，DoH 也绕不过去
                            Logx.d("SNI 命中屏蔽，已断开: $s -> $host")
                            try { remote.close() } catch (_: Throwable) {}
                            try { client.close() } catch (_: Throwable) {}
                            return
                        }
                        break
                    }
                }
                if (acc >= 16 && !TlsSni.looksLikeTls(first, acc)) break
            }
            firstLen = acc
        } catch (_: Throwable) {}
        if (firstLen > 0 && sniLogs++ < 12) {
            vlog("首段 $firstLen B tls=${TlsSni.looksLikeTls(first, firstLen)} sni=${TlsSni.parse(first, firstLen)} host=$host uid=$uid")
        }
        if (firstLen > 0) {
            rout.write(first, 0, firstLen); rout.flush()
            addBytes(uid, firstLen.toLong())
        }

        val t1 = Thread {
            val buf = ByteArray(16384)
            try {
                while (true) {
                    val n = cin.read(buf)
                    if (n <= 0) break
                    rout.write(buf, 0, n); rout.flush()
                    addBytes(uid, n.toLong())
                }
            } catch (_: Throwable) {}
            try { remote.close() } catch (_: Throwable) {}
        }
        t1.isDaemon = true; t1.start()

        val buf = ByteArray(16384)
        try {
            while (true) {
                val n = rin.read(buf)
                if (n <= 0) break
                cout.write(buf, 0, n); cout.flush()
                addBytes(uid, n.toLong())
            }
        } catch (_: Throwable) {}
        try { client.close() } catch (_: Throwable) {}
        try { t1.join(500) } catch (_: Throwable) {}
    }

    /** 极简 TCP DNS（RFC 1035 长度前缀），供系统解析器回退时使用。 */
    private fun tcpDns(cin: InputStream, cout: OutputStream) {
        try {
            while (running) {
                val lenB = ByteArray(2)
                readFully(cin, lenB)
                val len = ((lenB[0].toInt() and 0xff) shl 8) or (lenB[1].toInt() and 0xff)
                if (len <= 0 || len > 4096) return
                val qbuf = ByteArray(len)
                readFully(cin, qbuf)
                val q = Dns.parseQuestion(qbuf) ?: return
                val blocked = BlocklistStore.matches(q.name, domains)
                val ans = if (blocked) Dns.blocked(qbuf, q)
                else (Dns.forward(qbuf, upstream, this::protectDs) ?: Dns.servFail(qbuf, q))
                NetMon.record(q.name, if (blocked) "127.0.0.1" else Dns.firstIp(ans), blocked)
                cout.write(byteArrayOf((ans.size shr 8).toByte(), ans.size.toByte()))
                cout.write(ans)
                cout.flush()
            }
        } catch (_: Throwable) {
        }
    }

    private fun recordSni(sni: String, ip: String, uid: Int): Boolean {
        val blocked = BlocklistStore.matches(sni, domains)
        NetMon.recordSni(sni, ip, blocked, uid)
        return blocked
    }

    private fun addBytes(uid: Int, n: Long) {
        if (uid <= 0 || n <= 0) return
        appBytes.merge(uid, n) { a, b -> a + b }
        NetMon.addTraffic(uid, n)
    }

    // ---------------- UDP ----------------

    private fun udpLoop(relay: DatagramSocket) {
        val buf = ByteArray(65535)
        Logx.d("UDP 中继就绪 port=${relay.localPort}")
        try {
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                relay.receive(p)
                val data = buf.copyOfRange(p.offset, p.offset + p.length)
                vlog("UDP 收到 ${data.size}B 来自 ${p.address.hostAddress}:${p.port}")
                val datagram = parseUdp(p.address.hostAddress ?: "", p.port, data) ?: continue
                Thread { handleUdp(relay, p.address, p.port, datagram) }.also { it.isDaemon = true }.start()
            }
        } catch (_: Throwable) {
        }
    }

    private class UdpReq(val host: String, val port: Int, val payload: ByteArray)

    private fun parseUdp(srcIp: String, srcPort: Int, data: ByteArray): UdpReq? {
        if (data.size < 4) return null
        if (data[0].toInt() != 0 || data[1].toInt() != 0) return null
        if (data[2].toInt() != 0) return null // 不支持分片
        var p = 3
        val atyp = data[p].toInt() and 0xff
        p++
        val host: String
        when (atyp) {
            1 -> {
                if (p + 4 > data.size) return null
                host = InetAddress.getByAddress(data.copyOfRange(p, p + 4)).hostAddress ?: return null
                p += 4
            }
            3 -> {
                if (p >= data.size) return null
                val l = data[p].toInt() and 0xff; p++
                if (p + l > data.size) return null
                host = String(data, p, l, Charsets.UTF_8); p += l
            }
            4 -> {
                if (p + 16 > data.size) return null
                host = InetAddress.getByAddress(data.copyOfRange(p, p + 16)).hostAddress ?: return null
                p += 16
            }
            else -> return null
        }
        if (p + 2 > data.size) return null
        val port = ((data[p].toInt() and 0xff) shl 8) or (data[p + 1].toInt() and 0xff)
        p += 2
        return UdpReq(host, port, data.copyOfRange(p, data.size))
    }

    private fun handleUdp(relay: DatagramSocket, clientAddr: InetAddress, clientPort: Int, req: UdpReq) {
        if (req.port == 53) {
            val q = Dns.parseQuestion(req.payload)
            vlog("UDP53 解析 q=${q?.name} 类型=${q?.type}")
            if (q != null) {
                val blocked = BlocklistStore.matches(q.name, domains)
                val ans = if (blocked) Dns.blocked(req.payload, q) else Dns.forward(req.payload, upstream, this::protectDs)
                vlog("UDP53 应答 ${ans?.size} 字节 blocked=$blocked")
                if (ans != null) {
                    val uid = -1
                    NetMon.record(q.name, if (blocked) "127.0.0.1" else Dns.firstIp(ans), blocked, uid)
                    sendUdp(relay, clientAddr, clientPort, req.host, req.port, ans)
                } else {
                    val fb = Dns.servFail(req.payload, q)
                    sendUdp(relay, clientAddr, clientPort, req.host, req.port, fb)
                }
            }
            return
        }
        val dst = try { InetAddress.getByName(req.host) } catch (_: Throwable) { return }
        val s = DatagramSocket()
        protect(s)
        s.soTimeout = 8000
        try {
            s.send(DatagramPacket(req.payload, req.payload.size, dst, req.port))
            val buf = ByteArray(65535)
            val resp = DatagramPacket(buf, buf.size)
            s.receive(resp)
            val body = buf.copyOfRange(resp.offset, resp.offset + resp.length)
            val uid = UidMap.uidFor(req.host, req.port)
            addBytes(uid, (body.size + req.payload.size).toLong())
            sendUdp(relay, clientAddr, clientPort, req.host, req.port, body)
        } catch (_: Throwable) {
        } finally {
            try { s.close() } catch (_: Throwable) {}
        }
    }

    private fun protectDs(s: DatagramSocket) = protect(s)

    private fun sendUdp(relay: DatagramSocket, addr: InetAddress, port: Int, host: String, hport: Int, payload: ByteArray) {
        val ip = try { InetAddress.getByName(host).address } catch (_: Throwable) { return }
        val hdr = 3 + 1 + ip.size + 2
        val out = ByteArray(hdr + payload.size)
        out[0] = 0; out[1] = 0; out[2] = 0
        out[3] = if (ip.size == 16) 4 else 1
        System.arraycopy(ip, 0, out, 4, ip.size)
        out[4 + ip.size] = (hport shr 8).toByte()
        out[5 + ip.size] = hport.toByte()
        System.arraycopy(payload, 0, out, hdr, payload.size)
        try {
            relay.send(DatagramPacket(out, out.size, addr, port))
        } catch (_: Throwable) {}
    }

    private fun readFully(i: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = i.read(b, off, b.size - off)
            if (n <= 0) throw IllegalStateException("EOF")
            off += n
        }
    }
}