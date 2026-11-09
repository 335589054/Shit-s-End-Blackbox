package mai.solium.shitsendblackbox.network

import mai.solium.shitsendblackbox.core.Logx
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** DNS 报文小工具：解析问题、合成屏蔽应答、转发上游、取第一条 A/AAAA。 */
object Dns {

    data class Question(val name: String, val type: Int, val end: Int)

    fun parseQuestion(dns: ByteArray): Question? {
        if (dns.size < 12) return null
        var i = 12
        val sb = StringBuilder()
        while (i < dns.size) {
            val l = dns[i].toInt() and 0xff
            i++
            if (l == 0) break
            if (l and 0xc0 != 0) return null
            if (i + l > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, i, l, Charsets.UTF_8))
            i += l
        }
        if (i + 4 > dns.size) return null
        val type = u16(dns, i)
        return Question(sb.toString(), type, i + 4)
    }

    fun blocked(query: ByteArray, q: Question): ByteArray {
        val qtype = q.type
        val out = ByteArray(12 + (q.end - 12) + 16)
        System.arraycopy(query, 0, out, 0, 12)
        out[2] = 0x81.toByte()
        out[3] = 0x80.toByte()
        out[4] = 0; out[5] = 1
        val hasAnswer = qtype == 1 || qtype == 28
        out[6] = 0; out[7] = if (hasAnswer) 1 else 0
        out[8] = 0; out[9] = 0
        out[10] = 0; out[11] = 0
        System.arraycopy(query, 12, out, 12, q.end - 12)
        if (!hasAnswer) return out.copyOf(12 + (q.end - 12))
        var p = 12 + (q.end - 12)
        out[p++] = 0xC0.toByte(); out[p++] = 0x0C
        out[p++] = 0; out[p++] = qtype.toByte()
        out[p++] = 0; out[p++] = 1
        out[p++] = 0; out[p++] = 0; out[p++] = 0; out[p++] = 60
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

    fun servFail(query: ByteArray, q: Question): ByteArray {
        val out = query.copyOf(12 + (q.end - 12))
        out[2] = 0x81.toByte()
        out[3] = 0x82.toByte() // RA + SERVFAIL
        out[6] = 0; out[7] = 0
        return out
    }

    fun forward(query: ByteArray, upstream: String, protect: (DatagramSocket) -> Unit): ByteArray? {
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

    fun firstIp(dns: ByteArray): String? {
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
                    InetAddress.getByAddress(dns.copyOfRange(p, p + 16)).hostAddress
                } catch (_: Throwable) {
                    null
                }
            }
            p += rdlen
        }
        return null
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)
}