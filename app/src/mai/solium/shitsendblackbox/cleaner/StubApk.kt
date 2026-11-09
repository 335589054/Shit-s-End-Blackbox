package mai.solium.shitsendblackbox.cleaner

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.CRC32

/**
 * 运行时生成“同包名、versionCode 25565、异签名、无图标”的空 APK。
 *
 * 不依赖 Android / build-tools：
 *  - 手写二进制 AndroidManifest.xml (AXML)
 *  - 手写 ZIP（stored）
 *  - 手写 APK Signature Scheme v2（RSA PKCS#1 v1.5 + SHA-256）
 *
 * 纯 JVM 代码，可在桌面用 kotlinc 单测。
 */
object StubApk {
    const val STUB_VERSION = 2_100_000_000

    fun generateKey(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    fun privateKeyFromPkcs8(bytes: ByteArray): PrivateKey =
        java.security.KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))

    fun publicKeyFromX509(bytes: ByteArray): java.security.PublicKey =
        java.security.KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.X509EncodedKeySpec(bytes))

    /** 未签名 APK（local + central dir + EOCD），交给 apksig 做 v1+v2 签名。 */
    fun buildUnsigned(packageName: String): ByteArray {
        val z = zipLayout(packageName)
        val out = ByteArrayOutputStream()
        out.write(z.local); out.write(z.centralDir)
        out.write(eocdBytes(z.local.size, z.centralDir.size, z.count))
        return out.toByteArray()
    }

    private class ZipLayout(val local: ByteArray, val centralDir: ByteArray, val count: Int)

    private fun zipLayout(packageName: String): ZipLayout {
        val entries = listOf(Entry("AndroidManifest.xml", manifestAxml(packageName)))

        val local = ByteArrayOutputStream()
        val cd = ByteArrayOutputStream()
        for (e in entries) {
            val offset = local.size()
            val crc = CRC32().apply { update(e.data) }.value
            val nameBytes = e.name.toByteArray(Charsets.UTF_8)
            val h = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
            h.putInt(0x04034b50); h.putShort(20); h.putShort(0); h.putShort(0)
            h.putShort(0); h.putShort(0)
            h.putInt(crc.toInt()); h.putInt(e.data.size); h.putInt(e.data.size)
            h.putShort(nameBytes.size.toShort()); h.putShort(0)
            local.write(h.array()); local.write(nameBytes); local.write(e.data)

            val c = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
            c.putInt(0x02014b50); c.putShort(20); c.putShort(20)
            c.putShort(0); c.putShort(0); c.putShort(0); c.putShort(0)
            c.putInt(crc.toInt()); c.putInt(e.data.size); c.putInt(e.data.size)
            c.putShort(nameBytes.size.toShort()); c.putShort(0); c.putShort(0)
            c.putShort(0); c.putShort(0); c.putInt(0); c.putInt(offset)
            cd.write(c.array()); cd.write(nameBytes)
        }
        return ZipLayout(local.toByteArray(), cd.toByteArray(), entries.size)
    }

    fun build(packageName: String, kp: KeyPair): ByteArray {
        val z = zipLayout(packageName)
        // EOCD as it appeared before the signing block was inserted (CD offset = local size).
        val eocdOld = eocdBytes(z.local.size, z.centralDir.size, z.count)
        val digests = listOf(contentDigest(z.local, z.centralDir, eocdOld))

        // ---- v2 signed data ----
        val digestsBytes = ByteArrayOutputStream()
        for (d in digests) {
            val rec = ByteArrayOutputStream()
            rec.write(le32(0x0103)); rec.write(le32(d.size)); rec.write(d)
            digestsBytes.write(le32(rec.size())); digestsBytes.write(rec.toByteArray())
        }
        val signed = ByteArrayOutputStream()
        signed.write(le32(digestsBytes.size())); signed.write(digestsBytes.toByteArray())
        val cert = SelfSigned.generate(kp)
        val certSeq = ByteArrayOutputStream()
        certSeq.write(le32(cert.size)); certSeq.write(cert)
        signed.write(le32(certSeq.size())); signed.write(certSeq.toByteArray()) // certificates
        signed.write(le32(0)) // additional attributes
        val signedData = signed.toByteArray()

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(kp.private)
        sig.update(signedData)
        val signature = sig.sign()

        val signer = ByteArrayOutputStream()
        signer.write(le32(signedData.size)); signer.write(signedData)
        val signatures = ByteArrayOutputStream()
        val sigRecord = ByteArrayOutputStream()
        sigRecord.write(le32(0x0103))
        sigRecord.write(le32(signature.size)); sigRecord.write(signature)
        signatures.write(le32(sigRecord.size())); signatures.write(sigRecord.toByteArray())
        signer.write(le32(signatures.size())); signer.write(signatures.toByteArray())
        val pubKey = kp.public.encoded
        signer.write(le32(pubKey.size)); signer.write(pubKey)
        val signerBytes = signer.toByteArray()

        val signers = ByteArrayOutputStream()
        signers.write(le32(signerBytes.size)); signers.write(signerBytes)
        val v2Value = ByteArrayOutputStream()
        v2Value.write(le32(signers.size())); v2Value.write(signers.toByteArray())
        val value = v2Value.toByteArray()

        val pairLen = 4 + value.size
        val total = 8 + 8 + pairLen + 8 + 16
        val block = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        block.putLong((total - 8).toLong())
        block.putLong(pairLen.toLong())
        block.putInt(0x7109871a)
        block.put(value)
        block.putLong((total - 8).toLong())
        block.put("APK Sig Block 42".toByteArray(Charsets.US_ASCII))
        val blockBytes = block.array()

        val out = ByteArrayOutputStream()
        out.write(z.local)
        out.write(blockBytes)
        out.write(z.centralDir)
        out.write(eocdBytes(z.local.size + blockBytes.size, z.centralDir.size, z.count))
        return out.toByteArray()
    }

    private data class Entry(val name: String, val data: ByteArray)

    private fun le32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun eocdBytes(cdOffset: Int, cdSize: Int, count: Int): ByteArray {
        val b = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x06054b50)
        b.putShort(0); b.putShort(0)
        b.putShort(count.toShort()); b.putShort(count.toShort())
        b.putInt(cdSize)
        b.putInt(cdOffset)
        b.putShort(0)
        return b.array()
    }

    /** APK Signature Scheme v2 content digest: SHA256(0x5a || le32(chunkCount) || chunkDigests). */
    private fun contentDigest(vararg sections: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val chunks = ArrayList<ByteArray>()
        for (sec in sections) {
            var off = 0
            while (off < sec.size) {
                val n = minOf(1024 * 1024, sec.size - off)
                md.reset()
                md.update(0xa5.toByte())
                md.update(le32(n))
                md.update(sec, off, n)
                chunks.add(md.digest())
                off += n
            }
        }
        val buf = ByteArrayOutputStream()
        buf.write(0x5a)
        buf.write(le32(chunks.size))
        for (c in chunks) buf.write(c)
        md.reset()
        return md.digest(buf.toByteArray())
    }

    // ------------------------------------------------------------------ AXML
    private fun manifestAxml(pkg: String): ByteArray {
        val sp = StringPool()
        val sManifest = sp.intern("manifest")
        val sPackage = sp.intern("package")
        val sVersionCode = sp.intern("versionCode")
        val sVersionName = sp.intern("versionName")
        val sApplication = sp.intern("application")
        val sHasCode = sp.intern("hasCode")
        val sEnabled = sp.intern("enabled")
        val sAllowBackup = sp.intern("allowBackup")
        val sUsesSdk = sp.intern("uses-sdk")
        val sMinSdk = sp.intern("minSdkVersion")
        val sTargetSdk = sp.intern("targetSdkVersion")
        val sAndroid = sp.intern("android")
        val sUri = sp.intern("http://schemas.android.com/apk/res/android")
        val sPkgValue = sp.intern(pkg)
        val sVer = sp.intern(STUB_VERSION.toString())

        val resIds = IntArray(sp.size)
        resIds[sVersionCode] = 0x0101021b
        resIds[sVersionName] = 0x0101021c
        resIds[sHasCode] = 0x0101000c
        resIds[sEnabled] = 0x0101000e
        resIds[sAllowBackup] = 0x01010280
        resIds[sMinSdk] = 0x0101020c
        resIds[sTargetSdk] = 0x01010270

        val manifestAttrs = listOf(
            Attr(-1, sPackage, Attr.TYPE_STRING, sPkgValue),
            Attr(sUri, sVersionCode, Attr.TYPE_INT_DEC, STUB_VERSION),
            Attr(sUri, sVersionName, Attr.TYPE_STRING, sVer)
        )
        val appAttrs = listOf(
            Attr(sUri, sHasCode, Attr.TYPE_INT_BOOLEAN, 0),
            Attr(sUri, sEnabled, Attr.TYPE_INT_BOOLEAN, 0),
            Attr(sUri, sAllowBackup, Attr.TYPE_INT_BOOLEAN, 0)
        )
        val sdkAttrs = listOf(
            Attr(sUri, sMinSdk, Attr.TYPE_INT_DEC, 26),
            Attr(sUri, sTargetSdk, Attr.TYPE_INT_DEC, 26)
        )

        val body = ByteArrayOutputStream()
        body.write(startNamespace(sAndroid, sUri))
        body.write(startElement(sManifest, sUri, manifestAttrs))
        body.write(startElement(sApplication, sUri, appAttrs))
        body.write(endElement(sApplication))
        body.write(startElement(sUsesSdk, sUri, sdkAttrs))
        body.write(endElement(sUsesSdk))
        body.write(endElement(sManifest))
        body.write(endNamespace(sAndroid, sUri))

        val spChunk = sp.toChunk()
        val resMap = resourceMap(resIds)
        val total = 8 + spChunk.size + resMap.size + body.size()
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x0003).putShort(8).putInt(total).array())
        out.write(spChunk); out.write(resMap); out.write(body.toByteArray())
        return out.toByteArray()
    }

    private data class Attr(val ns: Int, val name: Int, val type: Int, val data: Int) {
        companion object {
            const val TYPE_STRING = 0x03
            const val TYPE_INT_DEC = 0x10
            const val TYPE_INT_BOOLEAN = 0x12
        }
    }

    private fun attrCount(n: Int) = 36 + n * 20

    private fun startElement(name: Int, uriIdx: Int, attrs: List<Attr>): ByteArray {
        val size = 36 + attrs.size * 20
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0x0102); b.putShort(16); b.putInt(size)
        b.putInt(0); b.putInt(-1); b.putInt(-1); b.putInt(name)
        b.putShort(20); b.putShort(20); b.putShort(attrs.size.toShort())
        b.putShort(0); b.putShort(0); b.putShort(0)
        for (a in attrs) {
            b.putInt(if (a.ns < 0) -1 else uriIdx)
            b.putInt(a.name)
            b.putInt(if (a.type == Attr.TYPE_STRING) a.data else -1)
            b.putShort(8); b.put(0); b.put(a.type.toByte()); b.putInt(a.data)
        }
        return b.array()
    }

    private fun endElement(name: Int): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x0103).putShort(16).putInt(24)
            .putInt(0).putInt(-1).putInt(-1).putInt(name).array()

    private fun startNamespace(prefix: Int, uri: Int): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x0100).putShort(16).putInt(24)
            .putInt(0).putInt(-1).putInt(prefix).putInt(uri).array()

    private fun endNamespace(prefix: Int, uri: Int): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x0101).putShort(16).putInt(24)
            .putInt(0).putInt(-1).putInt(prefix).putInt(uri).array()

    private fun resourceMap(resIds: IntArray): ByteArray {
        val size = 8 + resIds.size * 4
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0x0180); b.putShort(8); b.putInt(size)
        resIds.forEach { b.putInt(it) }
        return b.array()
    }

    private class StringPool {
        private val items = ArrayList<String>()
        private val map = HashMap<String, Int>()
        val size: Int get() = items.size

        fun intern(s: String): Int = map.getOrPut(s) { items.add(s); items.size - 1 }

        fun toChunk(): ByteArray {
            val data = ByteArrayOutputStream()
            val offsets = IntArray(items.size)
            items.forEachIndexed { i, s ->
                offsets[i] = data.size()
                val u16 = s.toCharArray()
                data.write(u16.size and 0xFF); data.write((u16.size shr 8) and 0xFF)
                for (c in u16) { data.write(c.code and 0xFF); data.write((c.code shr 8) and 0xFF) }
                data.write(0); data.write(0)
            }
            while (data.size() % 4 != 0) data.write(0)
            val headerSize = 28
            val total = headerSize + items.size * 4 + data.size()
            val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(0x0001); b.putShort(headerSize.toShort()); b.putInt(total)
            b.putInt(items.size); b.putInt(0); b.putInt(0)
            b.putInt(headerSize + items.size * 4); b.putInt(0)
            offsets.forEach { b.putInt(it) }
            b.put(data.toByteArray())
            return b.array()
        }
    }
}
