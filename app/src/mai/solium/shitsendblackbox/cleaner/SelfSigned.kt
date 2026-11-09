package mai.solium.shitsendblackbox.cleaner

import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.Signature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 最小自签名 X.509 v3 证书生成（DER），仅用于给运行时生成的 stub APK 做 v2 签名。
 * APK Signature Scheme v2 要求 signed data 中至少存在一张证书，且其公钥与 signer 公钥一致。
 */
object SelfSigned {
    private val UTF8_CN = "BlackBox Stub"

    fun generate(kp: KeyPair): ByteArray {
        val algId = AlgorithmIdentifier()
        val name = name()
        val validity = seq(utcTime("200101000000Z"), utcTime("491231235959Z"))
        val serial = integer(positiveRandom(16))
        val tbs = seq(
            tagged(0xA0, integer(byteArrayOf(2))), // version v3
            serial,
            algId,
            name,
            validity,
            name,
            spki(kp)
        )
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(kp.private)
        sig.update(tbs)
        val sigBytes = sig.sign()
        return seq(tbs, algId, bitString(sigBytes))
    }

    private fun positiveRandom(n: Int): ByteArray {
        val b = ByteArray(n)
        java.security.SecureRandom().nextBytes(b)
        b[0] = (b[0].toInt() and 0x7F).toByte()
        if (b[0].toInt() == 0) b[0] = 1
        return b
    }

    private fun AlgorithmIdentifier(): ByteArray = seq(oid("1.2.840.113549.1.1.11"), bytes(0x05, ByteArray(0)))

    private fun name(): ByteArray {
        val cn = seq(oid("2.5.4.3"), utf8(UTF8_CN))
        return seq(set(cn))
    }

    private fun spki(kp: KeyPair): ByteArray = kp.public.encoded

    // ---- DER primitives ----
    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun bytes(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        out.write(len(content.size))
        out.write(content)
        return out.toByteArray()
    }

    private fun seq(vararg items: ByteArray): ByteArray {
        val c = ByteArrayOutputStream()
        items.forEach { c.write(it) }
        return bytes(0x30, c.toByteArray())
    }

    private fun set(vararg items: ByteArray): ByteArray {
        val c = ByteArrayOutputStream()
        items.forEach { c.write(it) }
        return bytes(0x31, c.toByteArray())
    }

    private fun tagged(tag: Int, content: ByteArray): ByteArray = bytes(tag, content)

    private fun integer(v: ByteArray): ByteArray {
        var b = v
        if ((b[0].toInt() and 0x80) != 0) {
            val n = ByteArray(b.size + 1)
            System.arraycopy(b, 0, n, 1, b.size)
            b = n
            b[0] = 0
        }
        return bytes(0x02, b)
    }

    private fun oid(s: String): ByteArray {
        val parts = s.split(".").map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (i in 2 until parts.size) {
            var v = parts[i]
            val tmp = ArrayList<Int>()
            tmp.add((v and 0x7F).toInt())
            v = v shr 7
            while (v > 0) {
                tmp.add(((v and 0x7F) or 0x80).toInt())
                v = v shr 7
            }
            for (j in tmp.indices.reversed()) out.write(tmp[j])
        }
        return bytes(0x06, out.toByteArray())
    }

    private fun utf8(s: String): ByteArray = bytes(0x0C, s.toByteArray(Charsets.UTF_8))

    private fun utcTime(s: String): ByteArray = bytes(0x17, s.toByteArray(Charsets.US_ASCII))

    private fun bitString(b: ByteArray): ByteArray {
        val c = ByteArrayOutputStream()
        c.write(0)
        c.write(b)
        return bytes(0x03, c.toByteArray())
    }
}
