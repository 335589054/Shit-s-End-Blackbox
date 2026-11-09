package mai.solium.shitsendblackbox.permission

import android.content.Context
import mai.solium.shitsendblackbox.core.Logx
import mai.solium.shitsendblackbox.core.Shell
import mai.solium.shitsendblackbox.data.AppInfo
import mai.solium.shitsendblackbox.data.OpResult
import mai.solium.shitsendblackbox.data.Perm
import mai.solium.shitsendblackbox.data.PermFailStore
import mai.solium.shitsendblackbox.data.PermZones
import mai.solium.shitsendblackbox.data.Scanner
import mai.solium.shitsendblackbox.data.SettingsStore
import mai.solium.shitsendblackbox.data.Zone

/**
 * 分区权限矩阵落地（每个权限维度独立应用）。
 *
 *  网络黑：后台断网（netpolicy）+ 禁止后台运行（appops）+ 待机桶 restricted + 强停。
 *          系统共享 UID（<10000）无法进 netpolicy，只做 appops/待机桶限制并明确记录「跳过」。
 *  网络灰：仅前台可联网（netpolicy restrict-background，仅 UID>=10000）+ 待机桶 rare。
 *  网络白：解除 netpolicy 限制、放行后台运行、待机桶 active。
 *  定位黑：appops FINE/COARSE ignore（必要时 pm revoke）。
 *  定位灰：appops foreground。
 *  定位白：pm grant 运行时定位权限 + appops allow；开启「屏蔽卫星」时仅保留粗定位。
 *  录屏黑：appops PROJECT_MEDIA deny；灰/白：allow。
 * 三权全黑时 pm suspend 冻结整个应用。
 *
 * 每条命令执行后用 `cmd appops get` / 返回码读回校验；失败写入 PermFailStore，
 * 上层据此把该应用该权限回退到白区并提示失败原因。
 */
object PolicyEngine {
    /** 低于该值的 UID 是系统共享 uid（1000/1001/... ），netpolicy 会直接抛 IllegalArgumentException。 */
    private const val SHARED_UID_LIMIT = 10000

    fun apply(ctx: Context, app: AppInfo, zones: PermZones, log: ((String) -> Unit)? = null): OpResult {
        if (isProtected(app)) {
            log?.invoke("受保护系统组件，跳过所有权限改动")
            return OpResult(true, "受保护，已跳过")
        }
        val fails = ArrayList<String>()
        for (p in Perm.entries) {
            val r = applyPerm(ctx, app, p, zones.of(p), log)
            if (!r.ok) {
                fails.add("[${p.label}] ${r.detail.ifEmpty { r.message }}")
                PermFailStore.set(ctx, app.pkg, p, r.detail.ifEmpty { r.message })
            } else {
                PermFailStore.clear(ctx, app.pkg, p)
            }
        }
        syncSuspend(ctx, app, zones, fails, log)
        Logx.d("策略 ${app.pkg} 网=${zones.net.id} 定=${zones.loc.id} 录=${zones.rec.id}: 失败 ${fails.size} 项")
        log?.invoke("完成：网络=${zones.net.label} 定位=${zones.loc.label} 录屏=${zones.rec.label}")
        return if (fails.isEmpty())
            OpResult(true, "策略已应用（网=${zones.net.label} 定=${zones.loc.label} 录=${zones.rec.label}）")
        else OpResult(false, "策略部分失败 ${fails.size} 项", fails.joinToString("\n").take(1200))
    }

    /** 只应用某一个权限维度（批量转移时用，避免重复处理另外两维）。 */
    fun applyPerm(
        ctx: Context,
        app: AppInfo,
        perm: Perm,
        zone: Zone,
        log: ((String) -> Unit)? = null
    ): OpResult {
        if (!Shell.available()) return OpResult(false, "Shizuku 不可用")
        if (app.uid <= 0) return OpResult(false, "应用未安装")
        if (isProtected(app)) return OpResult(true, "受保护，已跳过")
        val fails = ArrayList<String>()
        when (perm) {
            Perm.NET -> applyNet(app, zone, fails, log)
            Perm.LOC -> applyLoc(ctx, app, zone, fails, log)
            Perm.REC -> applyRec(app, zone, fails, log)
        }
        val res = if (fails.isEmpty()) OpResult(true, "${perm.label}=${zone.label} 已应用")
        else OpResult(false, "${perm.label}=${zone.label} 失败", fails.joinToString("\n"))
        if (res.ok) PermFailStore.clear(ctx, app.pkg, perm)
        else PermFailStore.set(ctx, app.pkg, perm, res.detail)
        Logx.d("策略 ${app.pkg} ${perm.label}=${zone.id}: 失败 ${fails.size} 项")
        return res
    }

    /** 三个维度都黑 → 冻结应用；否则确保解除冻结。 */
    fun syncSuspend(
        ctx: Context,
        app: AppInfo,
        zones: PermZones,
        fails: MutableList<String>? = null,
        log: ((String) -> Unit)? = null
    ) {
        if (app.uid <= 0) return
        // 网络黑区 = 冻结应用（Android 无 root 时唯一可靠的彻底断网手段），三权全黑也走这里。
        if (zones.net.id == Zone.BLACK.id || zones.allBlack) {
            val r = run("pm suspend --user 0 ${q(app.pkg)}", log)
            if (!r.ok) {
                fails?.add("pm suspend -> ${r.summary()}")
                log?.invoke("  ! 冻结失败：${r.summary()}")
            }
        } else {
            run("pm unsuspend --user 0 ${q(app.pkg)}", log, ignoreError = true)
        }
    }

    // ---------------------------------------------------------------- 网络

    private fun applyNet(app: AppInfo, zone: Zone, fails: MutableList<String>, log: ((String) -> Unit)?) {
        val pkg = q(app.pkg)
        val shared = app.uid < SHARED_UID_LIMIT
        when (zone.id) {
            Zone.BLACK.id -> {
                if (shared) {
                    log?.invoke("系统共享 UID=${app.uid}，netpolicy 不支持，改用后台限制 + 冻结待机桶")
                } else if (!netPolicy(app.uid, add = true, log)) {
                    fails.add("netpolicy 后台断网失败（uid=${app.uid}）")
                }
                setOp(app.pkg, "RUN_IN_BACKGROUND", "ignore", fails, log)
                setOp(app.pkg, "RUN_ANY_IN_BACKGROUND", "ignore", fails, log, optional = true)
                standby(app.pkg, "restricted", fails, log, optional = true)
                run("am force-stop $pkg", log)
                log?.invoke("黑区网络：后台断网 + 禁止后台运行 + 强制停止 + 冻结（彻底断网）")
            }
            Zone.GRAY.id -> {
                if (shared) log?.invoke("系统共享 UID=${app.uid}，netpolicy 不支持，只限制后台运行")
                else if (!netPolicy(app.uid, add = true, log)) fails.add("netpolicy 后台断网失败（uid=${app.uid}）")
                setOp(app.pkg, "RUN_ANY_IN_BACKGROUND", "ignore", fails, log, optional = true)
                standby(app.pkg, "rare", fails, log, optional = true)
            }
            else -> {
                if (!shared) netPolicy(app.uid, add = false, log, ignoreError = true)
                setOp(app.pkg, "RUN_IN_BACKGROUND", "allow", fails, log)
                setOp(app.pkg, "RUN_ANY_IN_BACKGROUND", "allow", fails, log, optional = true)
                standby(app.pkg, "active", fails, log, optional = true)
            }
        }
    }

    private fun netPolicy(uid: Int, add: Boolean, log: ((String) -> Unit)?, ignoreError: Boolean = false): Boolean {
        if (uid < SHARED_UID_LIMIT) return false
        val verb = if (add) "add" else "remove"
        val r = run("cmd netpolicy $verb restrict-background-blacklist $uid", log, ignoreError)
        if (!r.ok && !ignoreError) {
            log?.invoke("  ! netpolicy：${r.summary()}")
            return false
        }
        return r.ok || ignoreError
    }

    /**
     * 批量网络策略：netpolicy 一次只接受一个 uid（多余的会被忽略），所以用一条 shell for 循环逐 uid 执行，
     * 只启动一次 Shizuku 进程，几十个 uid 也很快。
     */
    fun bulkNetPolicy(uids: Collection<Int>, add: Boolean, log: ((String) -> Unit)? = null): Int {
        val list = uids.filter { it >= SHARED_UID_LIMIT }.distinct()
        if (list.isEmpty()) return 0
        if (!Shell.available()) return list.size
        val verb = if (add) "add" else "remove"
        var failed = 0
        for (chunk in list.chunked(80)) {
            val parts = chunk.joinToString(" ")
            val cmd = "for u in $parts; do cmd netpolicy $verb restrict-background-blacklist \$u 2>&1 || echo \"FAIL \$u\"; done"
            log?.invoke("$ cmd netpolicy $verb ...（${chunk.size} 个）")
            val r = Shell.exec(cmd, 90_000)
            val bad = Regex("FAIL (\\d+)").findAll(r.out + "\n" + r.err).count()
            if (bad > 0) {
                failed += bad
                log?.invoke("   ! $bad 个 uid 失败：${r.summary().take(200)}")
            }
            if (r.timedOut) {
                failed += chunk.size
                log?.invoke("   ! netpolicy 批量执行超时")
            }
        }
        return failed
    }

    /** 批量 appops：一条 shell 循环搞定，避免为每个包启动一次 Shizuku 进程。 */
    fun bulkAppOps(pkgs: Collection<String>, op: String, mode: String, log: ((String) -> Unit)? = null): Int {
        val list = pkgs.filter { it.isNotBlank() }.distinct()
        if (list.isEmpty()) return 0
        if (!Shell.available()) return list.size
        var failed = 0
        for (chunk in list.chunked(60)) {
            val parts = chunk.joinToString(" ") { q(it) }
            val cmd = "for p in $parts; do cmd appops set \$p $op $mode 2>&1 || echo \"FAIL \$p\"; done"
            log?.invoke("$ cmd appops $op=$mode ...（${chunk.size} 个）")
            val r = Shell.exec(cmd, 90_000)
            val bad = (r.out + "\n" + r.err).lineSequence().count { it.trim().startsWith("FAIL ") }
            if (bad > 0) {
                failed += bad
                log?.invoke("   ! $bad 个失败：${r.summary().take(200)}")
            }
            if (r.timedOut) failed += chunk.size
        }
        return failed
    }

    private fun standby(pkg: String, bucket: String, fails: MutableList<String>, log: ((String) -> Unit)?, optional: Boolean) {
        val r = run("am set-standby-bucket ${q(pkg)} $bucket", log)
        if (!r.ok && !optional) {
            fails.add("待机桶 $bucket 设置失败：${r.summary()}")
            log?.invoke("  ! ${r.summary()}")
        }
    }

    /** 轻量网络策略：只设置 appops（系统共享 UID 无法进 netpolicy，批量预设时用这个提速）。 */
    fun leanNet(ctx: Context, app: AppInfo, zone: Zone, log: ((String) -> Unit)? = null): OpResult {
        if (!Shell.available()) return OpResult(false, "Shizuku 不可用")
        val fails = ArrayList<String>()
        when (zone.id) {
            Zone.BLACK.id -> {
                setOp(app.pkg, "RUN_IN_BACKGROUND", "ignore", fails, log, verify = false)
                run("am force-stop ${q(app.pkg)}", log)
            }
            Zone.GRAY.id -> setOp(app.pkg, "RUN_ANY_IN_BACKGROUND", "ignore", fails, log, verify = false)
            else -> {
                setOp(app.pkg, "RUN_IN_BACKGROUND", "allow", fails, log, verify = false)
                setOp(app.pkg, "RUN_ANY_IN_BACKGROUND", "allow", fails, log, optional = true, verify = false)
            }
        }
        return if (fails.isEmpty()) OpResult(true, "网络=${zone.label}")
        else OpResult(false, "网络设置失败", fails.joinToString("\n"))
    }

    // ---------------------------------------------------------------- 定位

    private fun applyLoc(ctx: Context, app: AppInfo, zone: Zone, fails: MutableList<String>, log: ((String) -> Unit)?) {
        when (zone.id) {
            Zone.BLACK.id -> {
                revoke(app.pkg, "android.permission.ACCESS_FINE_LOCATION", log)
                revoke(app.pkg, "android.permission.ACCESS_COARSE_LOCATION", log)
                setOp(app.pkg, "FINE_LOCATION", "ignore", fails, log)
                setOp(app.pkg, "COARSE_LOCATION", "ignore", fails, log)
                setUidMode(app.uid, "FINE_LOCATION", "ignore", log)
                setUidMode(app.uid, "COARSE_LOCATION", "ignore", log)
            }
            Zone.GRAY.id -> {
                setOp(app.pkg, "FINE_LOCATION", "foreground", fails, log)
                setOp(app.pkg, "COARSE_LOCATION", "foreground", fails, log)
                setUidMode(app.uid, "FINE_LOCATION", "foreground", log)
                setUidMode(app.uid, "COARSE_LOCATION", "foreground", log)
            }
            else -> {
                grant(app.pkg, "android.permission.ACCESS_FINE_LOCATION", log)
                grant(app.pkg, "android.permission.ACCESS_COARSE_LOCATION", log)
                if (SettingsStore.flag(ctx, SettingsStore.ANTI_SATELLITE, false)) {
                    setOp(app.pkg, "FINE_LOCATION", "ignore", fails, log)
                    setOp(app.pkg, "COARSE_LOCATION", "allow", fails, log)
                    setUidMode(app.uid, "FINE_LOCATION", "ignore", log)
                    setUidMode(app.uid, "COARSE_LOCATION", "allow", log)
                    log?.invoke("白区定位：已按「屏蔽卫星」只保留网络粗定位")
                } else {
                    setOp(app.pkg, "FINE_LOCATION", "allow", fails, log)
                    setOp(app.pkg, "COARSE_LOCATION", "allow", fails, log)
                    // 旧版本可能把 UID 模式设成了 foreground/ignore，这里一并复位，否则白区应用在后台仍拿不到定位
                    setUidMode(app.uid, "FINE_LOCATION", "allow", log)
                    setUidMode(app.uid, "COARSE_LOCATION", "allow", log)
                }
            }
        }
    }

    /** 设置整个 UID 的 appops 模式（只对独立应用 uid≥10000 生效，共享 sys uid 不动）。 */
    private fun setUidMode(uid: Int, op: String, mode: String, log: ((String) -> Unit)?) {
        if (uid < SHARED_UID_LIMIT) return
        val r = run("cmd appops set --uid $uid $op $mode", log, ignoreError = true)
        if (!r.ok) log?.invoke("  (--uid $op 忽略：${r.summary().take(80)})")
    }

    private fun grant(pkg: String, perm: String, log: ((String) -> Unit)?) {
        // 系统固定权限会抛 SecurityException（已默认授予），忽略即可；用户应用缺少运行时授权时这里补上。
        val r = run("pm grant ${q(pkg)} $perm", log)
        if (!r.ok) log?.invoke("  (pm grant 忽略：${r.summary().take(120)})")
    }

    private fun revoke(pkg: String, perm: String, log: ((String) -> Unit)?) {
        val r = run("pm revoke ${q(pkg)} $perm", log)
        if (!r.ok) log?.invoke("  (pm revoke 忽略：${r.summary().take(120)})")
    }

    // ---------------------------------------------------------------- 录屏

    private fun applyRec(app: AppInfo, zone: Zone, fails: MutableList<String>, log: ((String) -> Unit)?) {
        if (zone.id == Zone.BLACK.id) setOp(app.pkg, "PROJECT_MEDIA", "deny", fails, log)
        else setOp(app.pkg, "PROJECT_MEDIA", "allow", fails, log, optional = true)
    }

    // ---------------------------------------------------------------- 执行 + 校验

    /** 设置 appops 并读回校验；读回值与期望不符时计入失败。 */
    private fun setOp(
        pkg: String,
        op: String,
        mode: String,
        fails: MutableList<String>,
        log: ((String) -> Unit)?,
        optional: Boolean = false,
        verify: Boolean = true
    ) {
        val r = run("cmd appops set ${q(pkg)} $op $mode", log)
        if (!r.ok) {
            if (optional) log?.invoke("  (忽略 $op=$mode：${r.summary().take(120)})")
            else {
                fails.add("$op=$mode 设置失败：${r.summary()}")
                log?.invoke("  ! $op=$mode 设置失败：${r.summary().take(160)}")
            }
            return
        }
        if (!verify) return
        val g = Shell.exec("cmd appops get ${q(pkg)} $op", 12_000)
        val text = (g.out + " " + g.err).lowercase()
        val got = when {
            text.contains(mode.lowercase()) -> true
            mode == "allow" && text.contains("no operations") -> true
            else -> false
        }
        if (!got) {
            val msg = "$op 期望 $mode，读回「${g.summary().take(120)}」"
            if (optional) log?.invoke("  (未确认 $msg)")
            else {
                fails.add("未生效：$msg")
                log?.invoke("  ! 未生效：$msg")
            }
        }
    }

    private fun run(
        cmd: String,
        log: ((String) -> Unit)?,
        ignoreError: Boolean = false
    ): mai.solium.shitsendblackbox.core.CmdResult {
        log?.invoke("$ $cmd")
        val r = Shell.exec(cmd, 20_000)
        if (!r.ok) log?.invoke("  ${if (ignoreError) "(忽略)" else "!"} ${r.summary().take(200)}")
        return r
    }

    private fun isProtected(app: AppInfo): Boolean =
        Scanner.isProtectedDelete(app.pkg) || Scanner.isCritical(app.pkg)

    private fun q(s: String): String = "'" + s.replace("'", "") + "'"
}