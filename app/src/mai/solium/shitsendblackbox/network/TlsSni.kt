package mai.solium.shitsendblackbox.network

/**
 * 从 TLS ClientHello 里解析 SNI（Server Name Indication）。
 * 只要拿到连接开头的几十字节就能读出 App 要访问的域名，
 * 这也是绕过 App 自带 DoH 之后仍能看见“它在连谁”的原因。
 */
object TlsSni {

    /** 返回 null 表示不是 TLS 或数据还不够（调用方应继续读）。 */
    fun parse(buf: ByteArray, len: Int): String? {
        if (len < 5) return null
        if ((buf[0].toInt() and 0xff) != 0x16) return "NOT_TLS"
        if ((buf[1].toInt() and 0xff) != 0x03) return "NOT_TLS"
        var p = 5
        if (len < p + 4) return null
        if ((buf[p].toInt() and 0xff) != 0x01) return "NOT_TLS"
        val hsLen = u24(buf, p + 1)
        p += 4
        if (len < p + hsLen) return null
        p += 2 // client_version
        p += 32 // random
        if (p >= len) return null
        val sidLen = buf[p].toInt() and 0xff
        p += 1 + sidLen
        if (p + 2 > len) return null
        val csLen = u16(buf, p)
        p += 2 + csLen
        if (p + 1 > len) return null
        val compLen = buf[p].toInt() and 0xff
        p += 1 + compLen
        if (p + 2 > len) return null
        val extTotal = u16(buf, p)
        p += 2
        val extEnd = minOf(len, p + extTotal)
        while (p + 4 <= extEnd) {
            val type = u16(buf, p)
            val elen = u16(buf, p + 2)
            p += 4
            if (p + elen > extEnd) return null
            if (type == 0x0000) {
                var q = p
                if (q + 2 > p + elen) return null
                val listLen = u16(buf, q)
                q += 2
                val listEnd = minOf(p + elen, q + listLen)
                while (q + 3 <= listEnd) {
                    val nt = buf[q].toInt() and 0xff
                    val nlen = u16(buf, q + 1)
                    q += 3
                    if (q + nlen > listEnd) return null
                    if (nt == 0) return String(buf, q, nlen, Charsets.UTF_8).lowercase()
                    q += nlen
                }
            }
            p += elen
        }
        return null
    }

    /** 是否是 TLS 握手首包（用于决定继续读取）。 */
    fun looksLikeTls(buf: ByteArray, len: Int): Boolean =
        len >= 3 && (buf[0].toInt() and 0xff) == 0x16 && (buf[1].toInt() and 0xff) == 0x03

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    private fun u24(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 16) or ((b[o + 1].toInt() and 0xff) shl 8) or (b[o + 2].toInt() and 0xff)
}