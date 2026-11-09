package mai.solium.shitsendblackbox.cleaner

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkSigner
import mai.solium.shitsendblackbox.core.CmdResult
import mai.solium.shitsendblackbox.core.Logx
import mai.solium.shitsendblackbox.core.Shell
import mai.solium.shitsendblackbox.data.AppInfo
import mai.solium.shitsendblackbox.data.Backup
import mai.solium.shitsendblackbox.data.BackupStore
import mai.solium.shitsendblackbox.data.OpResult
import mai.solium.shitsendblackbox.data.OpsStore
import mai.solium.shitsendblackbox.data.PermZones
import mai.solium.shitsendblackbox.data.Scanner
import mai.solium.shitsendblackbox.data.Zone
import mai.solium.shitsendblackbox.permission.PolicyEngine
import java.io.File
import java.security.KeyPair
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * 清理核心：备份 -> 反射删除(失败再走 pm uninstall) -> 安装同包名异签名占位包 -> 校验；失败自动回滚。
 * 占位包 versionCode 取 Android 允许的最大值 2100000000，避免被“版本降级”拦截；
 * 使用 apksig 同时做 v1+v2 签名，兼容 MIUI/HyperOS 的安装校验。
 * 所有特权操作都经 Shizuku 的 shell(uid 2000)，不依赖 root。
 */
object Cleaner {
    /** Android 允许的最大 versionCode；高于绝大多数系统应用，避免 INSTALL_FAILED_VERSION_DOWNGRADE。 */
    const val STUB_VERSION = 2_100_000_000L
    private const val APP_PKG = "mai.solium.shitsendblackbox"
    private const val TMP_DIR = "/data/local/tmp/blackbox"

    private fun ext(ctx: Context): File =
        ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "ext")

    fun backupDir(ctx: Context): File = File(ext(ctx), "backups").apply { mkdirs() }
    fun stubDir(ctx: Context): File = File(ext(ctx), "stub").apply { mkdirs() }

    // ---------------------------------------------------------------- backup

    fun apkPathOf(pkg: String): String? {
        val r = Shell.exec("pm path '$pkg'", 10_000)
        val line = r.out.lineSequence().firstOrNull { it.startsWith("package:") } ?: return null
        return line.removePrefix("package:").trim()
    }

    fun backup(ctx: Context, app: AppInfo, log: ((String) -> Unit)? = null): OpResult {
        val src = apkPathOf(app.pkg) ?: return OpResult(false, "未找到安装包路径")
        val name = "${app.pkg}-${System.currentTimeMillis()}.apk"
        val dst = File(backupDir(ctx), name)
        log?.invoke("备份原始安装包：$src")
        val r = Shell.exec("cp '$src' '${dst.absolutePath}' && ls -l '${dst.absolutePath}'", 60_000)
        if (!r.ok || !dst.exists() || dst.length() < 4096) {
            return OpResult(false, "备份失败", r.summary())
        }
        BackupStore.put(ctx, Backup(app.pkg, name, app.versionCode, app.versionName, app.system, System.currentTimeMillis()))
        Logx.d("已备份 ${app.pkg} -> $name (${dst.length()} B)")
        log?.invoke("已备份 ${app.label} (${dst.length() / 1024} KB)")
        return OpResult(true, "已备份 ${app.label} (${dst.length() / 1024} KB)")
    }

    // -------------------------------------------------------------- replace

    fun replace(ctx: Context, app: AppInfo, log: ((String) -> Unit)? = null): OpResult {
        if (Scanner.isCritical(app.pkg)) return OpResult(false, "受保护的系统关键组件，拒绝操作")
        if (Scanner.isProtectedDelete(app.pkg)) return OpResult(false, "该系统组件负责定位等基础功能，已禁止卸载；请改用隔离")
        if (app.pkg == APP_PKG) return OpResult(false, "不能操作自身")
        if (!Shell.available()) return OpResult(false, "Shizuku 不可用")

        // 1. 备份原始安装包（删除前必须完成）
        val bk = backup(ctx, app, log)
        if (!bk.ok) return bk

        // 2. 生成占位包（未签名 zip），并用 apksig 做 v1+v2 签名
        val stubFile = File(stubDir(ctx), "${app.pkg}.apk")
        try {
            log?.invoke("生成占位包（v$STUB_VERSION，异签名）")
            val unsigned = StubApk.buildUnsigned(app.pkg)
            signStub(ctx, unsigned, stubFile)
            log?.invoke("占位包已签名：${stubFile.length()} B")
        } catch (t: Throwable) {
            Logx.e("占位包生成失败", t)
            return OpResult(false, "占位包生成失败", t.message ?: "")
        }

        // 3. 反射删除：系统应用（含已更新的系统应用）带 DELETE_SYSTEM_APP，普通应用用 0
        val flags = if (app.system) 5 else 0
        log?.invoke("反射删除：deletePackageAsUser flags=$flags")
        val del = deleteByReflection(app.pkg, flags)
        log?.invoke("删除返回：" + del.out.lineSequence().firstOrNull { it.isNotBlank() })
        if (!del.ok) log?.invoke("删除调用异常：" + del.summary())

        var gone = waitGone(app.pkg, 20_000)
        if (!gone) {
            log?.invoke("包仍可见，尝试 pm uninstall -k --user 0（MIUI 可能拦截）")
            val fb = Shell.exec("pm uninstall -k --user 0 '${app.pkg}'", 40_000)
            log?.invoke("pm uninstall：" + (fb.out.ifBlank { fb.err }).lineSequence().firstOrNull())
            gone = waitGone(app.pkg, 12_000)
        }
        if (!gone) log?.invoke("警告：删除后包记录仍在（墓碑），继续尝试安装占位包")

        // 4. 安装占位包
        log?.invoke("安装占位包…")
        val inst = pmInstall(stubFile.absolutePath, replace = true, downgrade = true)
        val installedVer = versionCodeOf(app.pkg)
        log?.invoke("安装结果：" + (inst.out.ifBlank { inst.err }).lineSequence().firstOrNull { it.isNotBlank() })
        if (inst.ok && installedVer == STUB_VERSION) {
            val b = BackupStore.all(ctx)[app.pkg]
            if (b != null) BackupStore.put(ctx, b.copy(stubVersion = STUB_VERSION))
            OpsStore.set(ctx, app.pkg, "replaced")
            Logx.d("顶替成功 ${app.pkg} -> v$STUB_VERSION")
            log?.invoke("✓ 顶替成功：versionCode=$STUB_VERSION")
            return OpResult(true, "已卸载并顶替 ${app.label}", "versionCode=$STUB_VERSION")
        }

        // 5. 占位包安装失败：按用户策略「保持删除」，不自动恢复原包（备份保留，可手动恢复）
        Logx.w("占位包安装失败: ${inst.summary()} (安装版本=$installedVer)")
        log?.invoke("✗ 占位包安装失败：" + inst.summary())
        OpsStore.set(ctx, app.pkg, "deleted")
        log?.invoke("保持删除状态：未恢复原安装包（备份仍在，可点「恢复」找回）")
        return OpResult(false, "占位包安装失败，已保持删除状态", "install=${inst.summary()}; currentVer=$installedVer")
    }

    fun deleteByReflection(pkg: String, flags: Int = 5): CmdResult {
        val apk = apkPathOf(APP_PKG) ?: return CmdResult(-1, "", "找不到自身 APK 路径")
        val cmd = "CLASSPATH='$apk' app_process /system/bin " +
            "mai.solium.shitsendblackbox.cleaner.PackageUninstaller '$pkg' 0 $flags"
        return Shell.exec(cmd, 30_000)
    }

    private fun waitGone(pkg: String, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val r = Shell.exec("pm path '$pkg'", 10_000)
            if (r.ok && !r.out.contains("package:")) return true
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
        }
        return false
    }

    fun versionCodeOf(pkg: String): Long {
        val r = Shell.exec("dumpsys package '$pkg' | grep -m1 versionCode", 10_000)
        val m = Regex("versionCode=(\\d+)").find(r.out) ?: return -1
        return m.groupValues[1].toLongOrNull() ?: -1
    }

    private fun pmInstall(path: String, replace: Boolean, downgrade: Boolean): CmdResult {
        val flags = buildString {
            append("-t")
            if (replace) append(" -r")
            if (downgrade) append(" -d")
        }
        // pm install 以 system 身份解析 APK，读不到 app 私有外部目录；
        // 先复制到 shell 可读的 /data/local/tmp 再安装。
        val name = File(path).name
        val tmp = "$TMP_DIR/$name"
        val cp = Shell.exec("mkdir -p $TMP_DIR && cp '$path' '$tmp' && chmod 644 '$tmp'", 60_000)
        if (!cp.ok) return cp
        val r = Shell.exec("pm install $flags --user 0 '$tmp'", 180_000)
        if (r.ok && !r.out.contains("Success")) {
            return CmdResult(1, r.out, r.err)
        }
        return r
    }

    // -------------------------------------------------------------- restore

    fun restore(ctx: Context, app: AppInfo, log: ((String) -> Unit)? = null): OpResult {
        if (!Shell.available()) return OpResult(false, "Shizuku 不可用")
        val meta = BackupStore.all(ctx)[app.pkg]
        val cur = versionCodeOf(app.pkg)
        // 若当前是占位包，先移除异签名包，否则原包无法覆盖安装
        if (cur == STUB_VERSION) {
            log?.invoke("移除占位包…")
            Shell.exec("pm uninstall --user 0 '${app.pkg}'", 40_000)
        }
        val ok = restoreApk(ctx, app.pkg, log)
        return if (ok) {
            liftRestrictions(app, log)
            OpsStore.clear(ctx, app.pkg)
            val ver = versionCodeOf(app.pkg)
            log?.invoke("✓ 已恢复：versionCode=$ver")
            OpResult(true, "已恢复 ${app.label}", "versionCode=$ver")
        } else {
            OpResult(false, "恢复失败", if (meta == null) "无备份可用，且系统禁用包恢复失败" else "备份安装与 install-existing 均失败")
        }
    }

    /** 恢复时解除此前的隔离限制（挂起、后台网络、定位与录屏 appops）。 */
    private fun liftRestrictions(app: AppInfo, log: ((String) -> Unit)?) {
        val p = "'${app.pkg}'"
        val cmd = listOf(
            "pm unsuspend --user 0 $p",
            "cmd netpolicy remove restrict-background-blacklist ${app.uid}",
            "cmd appops set $p FINE_LOCATION allow",
            "cmd appops set $p COARSE_LOCATION allow",
            "cmd appops set $p PROJECT_MEDIA allow",
            "cmd appops set $p RUN_IN_BACKGROUND allow"
        ).joinToString("; ")
        log?.invoke("解除隔离限制…")
        Shell.exec(cmd, 25_000)
    }

    /** 优先安装备份 APK；失败再尝试 pm install-existing（系统包 tombstone 恢复）。 */
    fun restoreApk(ctx: Context, pkg: String, log: ((String) -> Unit)? = null): Boolean {
        val meta = BackupStore.all(ctx)[pkg]
        if (meta != null) {
            val f = File(backupDir(ctx), meta.apk)
            if (f.exists()) {
                log?.invoke("安装备份 ${meta.apk}")
                val r = pmInstall(f.absolutePath, replace = true, downgrade = true)
                if (r.ok && apkPathOf(pkg) != null) {
                    Logx.d("恢复成功(备份) $pkg ${r.summary()}")
                    log?.invoke("备份安装成功")
                    return true
                }
                Logx.w("备份安装失败: ${r.summary()}")
                log?.invoke("备份安装失败：" + r.summary())
            }
        }
        log?.invoke("尝试 pm install-existing（墓碑恢复）")
        val r2 = Shell.exec("pm install-existing --user 0 '$pkg'", 40_000)
        if (r2.ok && apkPathOf(pkg) != null) {
            Logx.d("恢复成功(install-existing) $pkg")
            log?.invoke("install-existing 成功")
            return true
        }
        return false
    }

    // -------------------------------------------------------------- isolate

    /** 无法卸载时的降级方案：网络/定位/录屏全部黑区 + 撤销权限 + 冻结强停。 */
    fun isolate(ctx: Context, app: AppInfo, log: ((String) -> Unit)? = null): OpResult {
        if (Scanner.isCritical(app.pkg)) return OpResult(false, "受保护的系统关键组件，拒绝操作")
        if (!Shell.available()) return OpResult(false, "Shizuku 不可用")
        val p = "'${app.pkg}'"
        val fails = ArrayList<String>()
        log?.invoke("应用全黑分区策略…")
        val pol = PolicyEngine.apply(
            ctx, app, PermZones(Zone.BLACK, Zone.BLACK, Zone.BLACK), log
        )
        if (!pol.ok) fails.add(pol.detail.ifEmpty { pol.message })
        val cmds = listOf(
            "pm revoke $p android.permission.ACCESS_FINE_LOCATION",
            "pm revoke $p android.permission.ACCESS_COARSE_LOCATION",
            "cmd appops set $p RUN_ANY_IN_BACKGROUND ignore",
            "cmd appops set $p GET_USAGE_STATS ignore",
            "am force-stop $p",
            "pm suspend --user 0 $p"
        )
        for (c in cmds) {
            log?.invoke("$ $c")
            val r = Shell.exec(c, 15_000)
            if (!r.ok) fails.add("${c.split(" ").take(3).joinToString(" ")}: ${r.summary()}")
        }
        OpsStore.set(ctx, app.pkg, "isolated")
        Logx.d("隔离 ${app.pkg}: 失败 ${fails.size} 项")
        log?.invoke("✓ 已隔离（失败 ${fails.size} 项）")
        return if (fails.size <= 2) OpResult(true, "已隔离 ${app.label}", "网络/定位/录屏 + 冻结强停")
        else OpResult(false, "隔离部分失败", fails.joinToString("\n"))
    }

    fun unIsolate(ctx: Context, app: AppInfo, log: ((String) -> Unit)? = null): OpResult {
        val p = "'${app.pkg}'"
        val cmds = listOf(
            "pm unsuspend --user 0 $p",
            "cmd netpolicy remove restrict-background-blacklist ${app.uid}",
            "cmd appops set $p FINE_LOCATION default",
            "cmd appops set $p COARSE_LOCATION default",
            "cmd appops set $p PROJECT_MEDIA default",
            "cmd appops set $p RUN_IN_BACKGROUND default"
        )
        for (c in cmds) {
            log?.invoke("$ $c")
            Shell.exec(c, 15_000)
        }
        OpsStore.clear(ctx, app.pkg)
        return OpResult(true, "已解除隔离 ${app.label}")
    }

    // ------------------------------------------------------------- signing

    /** 用 apksig 同时生成 v1 + v2 签名，兼容 MIUI/HyperOS 的安装校验。 */
    private fun signStub(ctx: Context, unsigned: ByteArray, out: File) {
        val kp = keyPair(ctx)
        val certBytes = SelfSigned.generate(kp)
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(certBytes.inputStream()) as X509Certificate
        val tmp = File(stubDir(ctx), "${out.name}.unsigned")
        tmp.writeBytes(unsigned)
        try {
            val cfg = ApkSigner.SignerConfig.Builder("BlackBox Stub", kp.private, listOf(cert)).build()
            ApkSigner.Builder(listOf(cfg))
                .setInputApk(tmp)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setMinSdkVersion(26)
                .build()
                .sign()
        } finally {
            tmp.delete()
        }
    }

    // ------------------------------------------------------------- keypair

    fun keyPair(ctx: Context): KeyPair {
        val kf = File(ctx.filesDir, "stub.key")
        val cf = File(ctx.filesDir, "stub.crt")
        if (kf.exists() && cf.exists()) {
            try {
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(cf.inputStream()) as X509Certificate
                val priv = java.security.KeyFactory.getInstance("RSA")
                    .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(kf.readBytes()))
                return KeyPair(cert.publicKey, priv)
            } catch (t: Throwable) {
                Logx.w("读取 stub 密钥失败，重新生成: ${t.message}")
            }
        }
        val kp = StubApk.generateKey()
        kf.writeBytes(kp.private.encoded)
        cf.writeBytes(SelfSigned.generate(kp))
        return kp
    }

    fun installedVersion(ctx: Context, pkg: String): Long = try {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    } catch (_: PackageManager.NameNotFoundException) {
        -1
    }
}
