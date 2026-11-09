package mai.solium.shitsendblackbox.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import mai.solium.shitsendblackbox.core.Logx
import mai.solium.shitsendblackbox.data.AppInfo
import mai.solium.shitsendblackbox.data.Perm
import mai.solium.shitsendblackbox.data.PermFailStore
import mai.solium.shitsendblackbox.data.PermZoneStore
import mai.solium.shitsendblackbox.data.PermZones
import mai.solium.shitsendblackbox.data.PresetStore
import mai.solium.shitsendblackbox.data.Scanner
import mai.solium.shitsendblackbox.data.SettingsStore
import mai.solium.shitsendblackbox.data.Zone
import mai.solium.shitsendblackbox.data.ZoneSet
import mai.solium.shitsendblackbox.permission.PolicyEngine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 第 2 页：黑/灰/白 × 网络/定位/录屏 的 3×3 权限矩阵。
 * 每个应用在每一种权限上独立分区，格子内可多选批量移动；每次转移都有进度条与失败原因。
 * 右上角「刷新」手动重扫（避免每次 onResume 都全量扫描导致卡顿）。
 */
class PermPage(private val act: Activity) : Page {
    companion object {
        private const val REQ_EXPORT = 9101
        private const val REQ_IMPORT = 9102
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var grid: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView
    private var apps: List<AppInfo> = emptyList()
    private var query: String = ""
    @Volatile private var busy = false

    override fun view(): View {
        val scroll = ScrollView(act)
        root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 24))
        scroll.addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 标题 + 右上角 刷新/预设
        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(Ui.text(act, "权限", 22f, Ui.TEXT, true))
        head.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(Ui.ghostButton(act, "刷新") { rescan() })
        head.addView(View(act), LinearLayout.LayoutParams(Ui.dp(act, 6), 1))
        head.addView(Ui.ghostButton(act, "预设") { showPresetMenu() })
        root.addView(head)
        root.addView(Ui.text(act, "每个应用的 网络/定位/录屏 可分别放入黑/灰/白区", 12f, Ui.SUB))

        val anti = CheckBox(act)
        anti.text = "白区屏蔽卫星定位（仅保留网络粗定位，可能让签到类应用定位失败）"
        anti.textSize = 12f
        anti.setTextColor(Ui.TEXT)
        anti.isChecked = SettingsStore.flag(act, SettingsStore.ANTI_SATELLITE, false)
        anti.setOnCheckedChangeListener { _, v ->
            SettingsStore.setFlag(act, SettingsStore.ANTI_SATELLITE, v)
            Toast.makeText(act, if (v) "已开启：之后设置的白区定位只保留粗定位" else "已关闭：白区允许精确定位", Toast.LENGTH_SHORT).show()
            render()
        }
        root.addView(Ui.card(act, anti))

        val search = EditText(act)
        search.hint = "搜索包名 / 应用名（全体应用）"
        search.textSize = 13f
        search.setSingleLine(true)
        search.inputType = InputType.TYPE_CLASS_TEXT
        search.setTextColor(Ui.TEXT)
        search.setHintTextColor(Ui.SUB)
        search.setBackgroundColor(Ui.CARD2)
        search.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8))
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString() ?: ""
                render()
            }
        })
        root.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(act, 10) })

        grid = LinearLayout(act)
        grid.orientation = LinearLayout.VERTICAL
        root.addView(Ui.card(act, grid))

        status = Ui.text(act, "加载中…", 12f, Ui.SUB)
        root.addView(status)

        listBox = LinearLayout(act)
        listBox.orientation = LinearLayout.VERTICAL
        root.addView(listBox)
        return scroll
    }

    /** onResume / 切页时只重绘，不重新扫描；首次进入或手动点「刷新」才扫描。 */
    override fun refresh() {
        if (apps.isEmpty()) rescan() else render()
    }

    /** 手动刷新：重新读取应用列表（Shizuku/系统安装变化后使用）。 */
    private fun rescan() {
        if (busy) return
        busy = true
        status.text = "正在读取应用列表…"
        Thread {
            val list = try { Scanner.scan(act) } catch (t: Throwable) {
                Logx.e("权限页扫描失败", t); emptyList()
            }
            main.post {
                apps = list
                busy = false
                render()
            }
        }.start()
    }

    private fun render() {
        grid.removeAllViews()
        listBox.removeAllViews()
        val map = PermZoneStore.raw(act)
        val fails = PermFailStore.all(act)

        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(labelCell("分区"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f))
        for (p in Perm.entries) head.addView(labelCell(p.label), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        grid.addView(head)

        val antiSat = SettingsStore.flag(act, SettingsStore.ANTI_SATELLITE, false)
        for (zone in Zone.ALL) {
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            val zc = labelCell(zone.label)
            zc.setTextColor(zoneColor(zone))
            row.addView(zc, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f))
            for (p in Perm.entries) {
                val n = PermZoneStore.count(apps, map, p, zone)
                val txt = ruleText(p, zone) + " ($n)" + if (p == Perm.LOC && zone == Zone.WHITE && antiSat) "*" else ""
                row.addView(cell(p, zone, txt), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(act, 8)
            })
        }

        status.text = "共 ${apps.size} 个应用 · 点格子多选批量分区 · 右上角「刷新」重新扫描" +
            (if (antiSat) " · * 白区仅网络粗定位" else "")

        val shown = if (query.isNotBlank()) Scanner.searchAll(apps, query) else apps
        listBox.addView(Ui.text(act, "全部应用（点击权限标签可单独切换）", 13f, Ui.SUB, true)
            .also { it.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 6)) })
        for (a in shown.sortedBy { it.label }) {
            val z = PermZoneStore.zonesOf(map, a)
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val col = LinearLayout(act)
            col.orientation = LinearLayout.VERTICAL
            col.addView(Ui.text(act, a.label, 14f, Ui.TEXT, true))
            col.addView(Ui.text(act, a.pkg, 10f, Ui.SUB))
            val failText = Perm.entries.mapNotNull { p -> fails[a.pkg + "|" + p.id]?.let { "${p.label}：${it.lineSequence().firstOrNull().orEmpty()}" } }
            if (failText.isNotEmpty()) {
                col.addView(Ui.text(act, "上次失败 · " + failText.joinToString("；").take(90), 10f, Ui.ACCENT))
            }
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val chips = LinearLayout(act)
            chips.orientation = LinearLayout.HORIZONTAL
            if (isProtected(a)) {
                val chip = Ui.text(act, "受保护·不可改", 10f, Ui.SUB, true)
                chip.setPadding(Ui.dp(act, 6), Ui.dp(act, 3), Ui.dp(act, 6), Ui.dp(act, 3))
                chip.background = Ui.rounded(Ui.CARD2, Ui.dp(act, 6))
                chips.addView(chip)
                row.setOnClickListener { explainProtected(a) }
            } else {
                for (p in Perm.entries) {
                    val zz = z.of(p)
                    val chip = Ui.text(act, p.label + "·" + zz.label, 10f, zoneColor(zz), true)
                    chip.setPadding(Ui.dp(act, 6), Ui.dp(act, 3), Ui.dp(act, 6), Ui.dp(act, 3))
                    chip.background = Ui.rounded(zoneBg(zz), Ui.dp(act, 6))
                    chip.setOnClickListener { chooseZone(a, p) }
                    chips.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(act, 4) })
                }
                row.setOnClickListener { chooseAppPerm(a) }
            }
            row.addView(chips)
            listBox.addView(Ui.card(act, row))
        }
    }

    private fun ruleText(p: Perm, z: Zone): String = when (p) {
        Perm.NET -> when (z.id) {
            Zone.BLACK.id -> "禁止(后台断网+冻结)"
            Zone.GRAY.id -> "仅前台"
            else -> "允许"
        }
        Perm.LOC -> when (z.id) {
            Zone.BLACK.id -> "禁止"
            Zone.GRAY.id -> "仅前台"
            else -> "允许"
        }
        Perm.REC -> if (z.id == Zone.BLACK.id) "禁止" else "允许"
    }

    /** 点某个权限时的说明：必须跟着权限走，不能再用通用的「禁止一切权限与联网」。 */
    private fun permDesc(p: Perm, z: Zone): String = when (p) {
        Perm.NET -> when (z.id) {
            Zone.BLACK.id -> "禁止联网（后台断网、禁止后台运行、强制停止）"
            Zone.GRAY.id -> "仅前台可联网（切到后台就断网）"
            else -> "允许联网（前台后台都放行）"
        }
        Perm.LOC -> when (z.id) {
            Zone.BLACK.id -> "禁止定位（前台后台都拒绝）"
            Zone.GRAY.id -> "仅前台可定位（切到后台就拒绝）"
            else -> if (SettingsStore.flag(act, SettingsStore.ANTI_SATELLITE, false))
                "允许定位（屏蔽卫星，仅网络粗定位）" else "允许定位（含精确定位）"
        }
        Perm.REC -> when (z.id) {
            Zone.BLACK.id -> "禁止录屏 / 投屏采集"
            Zone.GRAY.id -> "允许录屏（前台使用）"
            else -> "允许录屏 / 投屏"
        }
    }

    private fun labelCell(s: String): TextView {
        val t = Ui.text(act, s, 11f, Ui.SUB, false)
        t.gravity = Gravity.CENTER
        t.setPadding(Ui.dp(act, 4), Ui.dp(act, 4), Ui.dp(act, 4), Ui.dp(act, 4))
        return t
    }

    private fun cell(p: Perm, zone: Zone, s: String): TextView {
        val t = Ui.text(act, s, 11f, zoneColor(zone), true)
        t.gravity = Gravity.CENTER
        t.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 12))
        t.background = Ui.rounded(zoneBg(zone), Ui.dp(act, 8))
        t.setOnClickListener { openCell(p, zone) }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(Ui.dp(act, 3), 0, Ui.dp(act, 3), 0)
        t.layoutParams = lp
        return t
    }

    private fun zoneColor(z: Zone): Int = when (z.id) {
        "black" -> Ui.ACCENT
        "gray" -> Ui.AMBER
        else -> Ui.GREEN
    }

    private fun zoneBg(z: Zone): Int = when (z.id) {
        "black" -> 0xFFFDECEC.toInt()
        "gray" -> 0xFFFFF4E0.toInt()
        else -> 0xFFE8F5E9.toInt()
    }

    /** 关键系统组件（定位服务、网络位置服务等）不允许改权限，避免把系统基础功能改坏。 */
    private fun isProtected(a: AppInfo): Boolean =
        Scanner.isProtectedDelete(a.pkg) || Scanner.isCritical(a.pkg)

    private fun explainProtected(a: AppInfo) {
        AlertDialog.Builder(act).setTitle("受保护：${a.label}")
            .setMessage("${a.pkg} 是系统关键组件，改动它的权限会导致定位/网络等基础功能异常，因此本应用不对它做任何权限改动。")
            .setPositiveButton("知道了", null).show()
    }

    /** 格子弹窗：多选应用，批量加入该权限的该分区；取消勾选的回到白区。 */
    private fun openCell(perm: Perm, zone: Zone) {
        val map = PermZoneStore.raw(act)
        val inCell = apps.filter { PermZoneStore.zonesOf(map, it).of(perm).id == zone.id }
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(act)
        val list = LinearLayout(act)
        list.orientation = LinearLayout.VERTICAL
        val checks = LinkedHashMap<String, CheckBox>()
        val pre = inCell.map { it.pkg }.toHashSet()
        val base = (if (query.isNotBlank()) Scanner.searchAll(apps, query) else apps).filter { !isProtected(it) }
        for (a in base.sortedBy { it.label }) {
            val cb = CheckBox(act)
            cb.text = "${a.label}  (${a.pkg})"
            cb.textSize = 12f
            cb.isChecked = a.pkg in pre
            checks[a.pkg] = cb
            list.addView(cb)
        }
        scroll.addView(list)
        box.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 360)))
        val dlg = AlertDialog.Builder(act)
            .setTitle("${zone.label} · ${perm.label}｜${permDesc(perm, zone)}（多选）")
            .setView(box)
            .setPositiveButton("保存并应用") { _, _ ->
                val plan = ArrayList<Triple<AppInfo, PermZones, PermZones>>()
                for (a in base) {
                    val checked = checks[a.pkg]?.isChecked == true
                    val old = PermZoneStore.zonesOf(act, a)
                    val want = when {
                        checked -> old.with(perm, zone)
                        a.pkg in pre -> old.with(perm, Zone.WHITE) // 取消勾选 → 回到绿区
                        else -> old
                    }
                    if (want != old) plan.add(Triple(a, old, want))
                }
                if (plan.isEmpty()) Toast.makeText(act, "没有变化", Toast.LENGTH_SHORT).show()
                else runApply("${perm.label} → ${zone.label}", plan, setOf(perm))
            }
            .setNeutralButton("全选", null)
            .setNegativeButton("取消", null)
            .create()
        dlg.show()
        // 自己接管「全选」，避免默认点击后关闭对话框
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { checks.values.forEach { it.isChecked = true } }
    }

    /** 点应用行：先选要改的权限，再选该权限的分区，避免一次性改掉三项。 */
    private fun chooseAppPerm(a: AppInfo) {
        if (isProtected(a)) return explainProtected(a)
        val zz = PermZoneStore.zonesOf(act, a)
        val items = arrayOf(
            "网络 · ${zz.net.label}",
            "定位 · ${zz.loc.label}",
            "录屏 · ${zz.rec.label}"
        )
        AlertDialog.Builder(act)
            .setTitle("${a.label} · 选择要修改的权限")
            .setItems(items) { _, which ->
                chooseZone(a, Perm.entries[which])
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun chooseZone(a: AppInfo, p: Perm) {
        if (isProtected(a)) return explainProtected(a)
        val cur = PermZoneStore.zonesOf(act, a).of(p)
        val labels = Zone.ALL.map { "${it.label} · ${permDesc(p, it)}${if (it.id == cur.id) "（当前）" else ""}" }.toTypedArray()
        AlertDialog.Builder(act)
            .setTitle("${a.label} · ${p.label}")
            .setItems(labels) { _, which ->
                val z = Zone.ALL[which]
                val old = PermZoneStore.zonesOf(act, a)
                if (old.of(p) == z) return@setItems
                runApply("${a.label} · ${p.label} → ${z.label}", listOf(Triple(a, old, old.with(p, z))), setOf(p))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------ 批量应用（带进度、失败回退与原因）

    private fun runApply(title: String, plan: List<Triple<AppInfo, PermZones, PermZones>>, perms: Set<Perm>, force: Boolean = false) {
        if (busy) return
        val work = plan.filter { !isProtected(it.first) }
        if (work.isEmpty()) {
            Toast.makeText(act, "没有可修改的应用（系统关键组件已跳过）", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        val cancel = AtomicBoolean(false)

        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = work.size
        bar.progress = 0
        val logView = TextView(act)
        logView.textSize = 10f
        logView.setTextColor(Ui.SUB)
        logView.typeface = android.graphics.Typeface.MONOSPACE
        val logScroll = ScrollView(act)
        logScroll.addView(logView)
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(act, 10), Ui.dp(act, 6), Ui.dp(act, 10), Ui.dp(act, 6))
        box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 6)))
        box.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 200)).apply { topMargin = Ui.dp(act, 8) })
        val dlg = AlertDialog.Builder(act)
            .setTitle(title)
            .setView(box)
            .setCancelable(false)
            .setNeutralButton("停止", null)
            .setNegativeButton("关闭", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { cancel.set(true) }
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false

        fun append(s: String) {
            main.post {
                logView.append(s + "\n")
                if (logView.lineCount > 400) {
                    val t = logView.text.toString()
                    logView.text = t.substring(t.length / 2)
                }
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }

        Thread {
            val fails = ArrayList<String>()
            var okApps = 0
            var idx = 0
            for ((a, old, want) in work) {
                if (cancel.get()) break
                idx++
                val changed = if (force) perms.toList() else perms.filter { want.of(it).id != old.of(it).id }
                for (p in changed) PermZoneStore.set(act, a.pkg, p, want.of(p))
                var appOk = true
                for (p in changed) {
                    append("· ${a.label} ${p.label} → ${want.of(p).label}")
                    val r = try {
                        PolicyEngine.applyPerm(act, a, p, want.of(p)) { line -> append("   $line") }
                    } catch (t: Throwable) {
                        Logx.w("策略异常 ${a.pkg}: ${t.message}")
                        null
                    }
                    if (r == null || !r.ok) {
                        appOk = false
                        PermZoneStore.set(act, a.pkg, p, Zone.WHITE) // 失败回退绿区
                        val reason = r?.detail?.lineSequence()?.firstOrNull { it.isNotBlank() }
                            ?: r?.message ?: "异常"
                        fails.add("${a.label}(${a.pkg}) · ${p.label}：${reason.take(140)}")
                        append("   ✗ 失败，已回退绿区：${reason.take(140)}")
                    }
                }
                // 网络黑区会冻结应用（无 root 时唯一可靠的彻底断网），离开黑区要及时解冻
                if (want.net.id != old.net.id || want.allBlack != old.allBlack) {
                    try { PolicyEngine.syncSuspend(act, a, PermZoneStore.zonesOf(act, a)) } catch (_: Throwable) {}
                }
                if (appOk) okApps++
                val prog = idx
                main.post { bar.progress = prog }
            }
            val cancelled = cancel.get()
            main.post {
                busy = false
                bar.progress = work.size
                dlg.setTitle(if (fails.isEmpty()) "✓ $title" else "⚠ $title · 失败 ${fails.size} 项")
                append(
                    if (cancelled) "\n已停止：成功 $okApps / ${plan.size}"
                    else if (fails.isEmpty()) "\n✓ 全部成功（$okApps 个应用）"
                    else "\n完成：成功 $okApps / ${plan.size}，失败 ${fails.size} 项（已回退绿区）"
                )
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
                render()
            }
        }.start()
    }

    // ------------------------------------------------------------------ 预设

    private fun showPresetMenu() {
        if (apps.isEmpty()) {
            Toast.makeText(act, "应用列表还在加载，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        val items = arrayOf(
            "内置：只保留微信/QQ/支付宝/学习通联网",
            "全部放行（三个权限全回绿区）",
            "重设定位权限（修复白区/签到类应用无法定位）",
            "导出当前分区到文件…",
            "从文件导入分区…"
        )
        AlertDialog.Builder(act)
            .setTitle("预设")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> confirmPreset(PresetStore.builtin()[0])
                    1 -> confirmPreset(ZoneSet("全部放行", "网络/定位/录屏全部回到白区（绿区）。", Perm.entries.toSet(), PermZones(Zone.WHITE, Zone.WHITE, Zone.WHITE), emptyMap()))
                    2 -> fixLocation()
                    3 -> doExport()
                    4 -> doImport()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 按当前分区强制重设一遍定位（白区会 pm grant FINE/COARSE），用于修复“白区应用无法定位”。 */
    private fun fixLocation() {
        val plan = apps.filter {
            !isProtected(it) && it.permissions.any { p ->
                p.contains("ACCESS_FINE_LOCATION") || p.contains("ACCESS_COARSE_LOCATION")
            }
        }.map { a ->
            val z = PermZoneStore.zonesOf(act, a)
            Triple(a, z, z)
        }
        if (plan.isEmpty()) {
            Toast.makeText(act, "应用列表还在加载，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        runApply("重设定位权限", plan, setOf(Perm.LOC), force = true)
    }

    private fun confirmPreset(set: ZoneSet) {
        AlertDialog.Builder(act)
            .setTitle(set.name)
            .setMessage(set.desc + "\n\n注意：系统关键组件会被跳过；系统共享 UID 的应用只能用「限制后台运行」代替断网，可能影响推送/更新。")
            .setPositiveButton("应用") { _, _ -> applyPreset(set) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyPreset(set: ZoneSet) {
        val plan = ArrayList<Triple<AppInfo, PermZones, PermZones>>()
        var skipped = 0
        for (a in apps) {
            if (isProtected(a)) { skipped++; continue }
            val old = PermZoneStore.zonesOf(act, a)
            val want = set.target(old, a.pkg, a)
            if (want != old) plan.add(Triple(a, old, want))
        }
        if (plan.isEmpty()) {
            Toast.makeText(act, "当前分区已经符合该预设", Toast.LENGTH_SHORT).show()
            return
        }
        if (set.perms == setOf(Perm.NET)) runNetPreset(set, plan)
        else runApply("应用预设：${set.name}", plan, set.perms)
    }

    /** 网络专用预设：按 UID 批量设置 netpolicy，再把系统共享 UID 的应用逐个限制后台运行。 */
    private fun runNetPreset(set: ZoneSet, plan: List<Triple<AppInfo, PermZones, PermZones>>) {
        if (busy) return
        busy = true
        val cancel = AtomicBoolean(false)

        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = plan.size
        val logView = TextView(act)
        logView.textSize = 10f
        logView.setTextColor(Ui.SUB)
        logView.typeface = android.graphics.Typeface.MONOSPACE
        val logScroll = ScrollView(act)
        logScroll.addView(logView)
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(act, 10), Ui.dp(act, 6), Ui.dp(act, 10), Ui.dp(act, 6))
        box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 6)))
        box.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 200)).apply { topMargin = Ui.dp(act, 8) })
        val dlg = AlertDialog.Builder(act).setTitle(set.name).setView(box).setCancelable(false)
            .setNeutralButton("停止", null).setNegativeButton("关闭", null).create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { cancel.set(true) }
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false

        fun append(s: String) = main.post {
            logView.append(s + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }

        Thread {
            val fails = ArrayList<String>()
            for ((a, _, want) in plan) PermZoneStore.set(act, a.pkg, Perm.NET, want.net)
            val gray = plan.filter { it.third.net.id == Zone.GRAY.id }
            val black = plan.filter { it.third.net.id == Zone.BLACK.id }
            val white = plan.filter { it.third.net.id == Zone.WHITE.id }
            append("· 灰区 ${gray.size} · 黑区 ${black.size} · 白区 ${white.size}")

            // 普通 UID：netpolicy 逐 uid 批量 + appops 用一条 shell 循环批量设置（这才是「联网限制」真正生效的部分）。
            if (gray.isNotEmpty()) {
                append("· 灰区 ${gray.size} 个：限制后台联网 + 禁止后台运行")
                val bad = PolicyEngine.bulkNetPolicy(gray.map { it.first.uid }, add = true) { line -> append("   $line") }
                if (bad > 0) fails.add("netpolicy 批量失败 $bad 个（多为系统共享 UID）")
                PolicyEngine.bulkAppOps(gray.map { it.first.pkg }, "RUN_ANY_IN_BACKGROUND", "ignore") { line -> append("   $line") }
            }
            if (black.isNotEmpty()) {
                append("· 黑区 ${black.size} 个：断后台联网 + 冻结应用")
                val bad = PolicyEngine.bulkNetPolicy(black.map { it.first.uid }, add = true) { line -> append("   $line") }
                if (bad > 0) fails.add("netpolicy 批量失败 $bad 个（多为系统共享 UID）")
                PolicyEngine.bulkAppOps(black.map { it.first.pkg }, "RUN_IN_BACKGROUND", "ignore") { line -> append("   $line") }
                for ((a, _, _) in black) {
                    try { PolicyEngine.syncSuspend(act, a, PermZoneStore.zonesOf(act, a)) } catch (_: Throwable) {}
                }
            }
            if (white.isNotEmpty()) {
                append("· 白区 ${white.size} 个：解除后台联网限制")
                PolicyEngine.bulkNetPolicy(white.map { it.first.uid }, add = false) { line -> append("   $line") }
                PolicyEngine.bulkAppOps(white.map { it.first.pkg }, "RUN_IN_BACKGROUND", "allow") { line -> append("   $line") }
                PolicyEngine.bulkAppOps(white.map { it.first.pkg }, "RUN_ANY_IN_BACKGROUND", "allow") { line -> append("   $line") }
            }
            // 从黑区移到灰/白的应用要解冻，否则仍处于冻结状态
            for ((a, old, _) in plan) {
                if (old.net.id == Zone.BLACK.id && PermZoneStore.zonesOf(act, a).net.id != Zone.BLACK.id) {
                    try { PolicyEngine.syncSuspend(act, a, PermZoneStore.zonesOf(act, a)) } catch (_: Throwable) {}
                }
            }
            main.post { bar.progress = plan.size * 7 / 10 }

            // 系统共享 UID（<10000）无法进 netpolicy，只能逐个限制后台运行
            val sys = plan.filter { it.first.uid in 1..9999 }
            if (sys.isNotEmpty()) append("· 系统共享 UID 应用 ${sys.size} 个，逐个设置后台运行限制…")
            var done = 0
            for ((a, _, want) in sys) {
                if (cancel.get()) break
                val r = try {
                    PolicyEngine.leanNet(act, a, want.net) { line -> append("   $line") }
                } catch (t: Throwable) {
                    Logx.w("leanNet 异常 ${a.pkg}: ${t.message}"); null
                }
                if (r == null || !r.ok) {
                    PermZoneStore.set(act, a.pkg, Perm.NET, Zone.WHITE)
                    fails.add("${a.label}(${a.pkg}) · 网络：${r?.detail?.take(120) ?: "异常"}")
                }
                done++
                val p = plan.size * 7 / 10 + (done * plan.size * 3 / 10 / sys.size.coerceAtLeast(1))
                main.post { bar.progress = p }
            }
            val cancelled = cancel.get()
            main.post {
                busy = false
                bar.progress = plan.size
                dlg.setTitle(if (fails.isEmpty()) "✓ ${set.name}" else "⚠ ${set.name} · 失败 ${fails.size} 项")
                append(
                    if (cancelled) "\n已停止"
                    else if (fails.isEmpty()) "\n✓ 预设已应用（${plan.size} 个应用）"
                    else "\n完成：${plan.size - fails.size} 个成功，${fails.size} 项失败（已回退绿区）"
                )
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
                render()
            }
        }.start()
    }

    // ------------------------------------------------------------------ 导入 / 导出

    private fun doExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "shitsendblackbox-zones-" + System.currentTimeMillis() + ".json")
        }
        try {
            act.startActivityForResult(intent, REQ_EXPORT)
        } catch (t: Throwable) {
            Toast.makeText(act, "无法打开保存面板：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun doImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        try {
            act.startActivityForResult(intent, REQ_IMPORT)
        } catch (t: Throwable) {
            Toast.makeText(act, "无法打开文件选择器：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT -> Thread {
                val ok = try {
                    val text = PresetStore.exportJson(act, apps)
                    act.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    true
                } catch (t: Throwable) {
                    Logx.w("导出失败: ${t.message}"); false
                }
                main.post {
                    Toast.makeText(act, if (ok) "已导出当前分区" else "导出失败", Toast.LENGTH_SHORT).show()
                }
            }.start()
            REQ_IMPORT -> Thread {
                val text = try {
                    act.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (t: Throwable) {
                    Logx.w("读取导入文件失败: ${t.message}"); null
                }
                val set = text?.let { PresetStore.parse(it) }
                main.post {
                    if (set == null) Toast.makeText(act, "文件格式不正确", Toast.LENGTH_LONG).show()
                    else confirmPreset(set)
                }
            }.start()
        }
    }
}