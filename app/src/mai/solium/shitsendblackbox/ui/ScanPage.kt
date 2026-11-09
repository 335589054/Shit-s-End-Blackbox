package mai.solium.shitsendblackbox.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import mai.solium.shitsendblackbox.cleaner.Cleaner
import mai.solium.shitsendblackbox.core.Logx
import mai.solium.shitsendblackbox.core.Shell
import mai.solium.shitsendblackbox.data.AppInfo
import mai.solium.shitsendblackbox.data.BackupStore
import mai.solium.shitsendblackbox.data.ManualStore
import mai.solium.shitsendblackbox.data.OpResult
import mai.solium.shitsendblackbox.data.OpsStore
import mai.solium.shitsendblackbox.data.Scanner

/**
 * 第 1 页：扫描 + 清理。
 * 首页只显示每个应用的当前状态（放行 / 隔离 / 顶替）；点开后才出现操作按钮，
 * 执行时弹出带进度条和实时日志的对话框。
 */
class ScanPage(private val act: Activity) : Page {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView
    private var apps: List<AppInfo> = emptyList()
    private var query: String = ""
    @Volatile private var busy = false

    override fun view(): View {
        val scroll = ScrollView(act)
        scroll.setBackgroundColor(Ui.BG)
        root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 24))
        scroll.addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(Ui.column(act,
            Ui.text(act, "清理", 22f, Ui.TEXT, true),
            Ui.text(act, "扫描运营商/厂商预装的疑似隐私组件，卸载并放置同名占位包", 12f, Ui.SUB)
        ), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(act, 12) })

        status = Ui.text(act, "尚未扫描", 12f, Ui.SUB)
        val scanBtn = Ui.button(act, "开始扫描") { doScan() }
        val manualBtn = Ui.ghostButton(act, "添加包名") { addManual() }
        root.addView(Ui.card(act,
            Ui.row(act, scanBtn, Ui.space(act, 8), manualBtn),
            Ui.space(act, 10),
            status
        ))

        val search = EditText(act)
        search.hint = "搜索包名 / 应用名（仅可疑清单，可手动添加）"
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

        listBox = LinearLayout(act)
        listBox.orientation = LinearLayout.VERTICAL
        root.addView(listBox)
        return scroll
    }

    private fun doScan() {
        if (busy) return
        busy = true
        status.text = "扫描中…"
        Thread {
            val res = try { Scanner.scan(act) } catch (t: Throwable) {
                Logx.e("扫描失败", t); emptyList()
            }
            apps = res
            main.post { busy = false; render() }
        }.start()
    }

    override fun refresh() {
        if (apps.isEmpty()) doScan() else render()
    }

    /** 已装版本 == 占位版本，或备份记录了占位版本 → 顶替；deleted → 已删除；isolated → 隔离；未安装 → 已卸载；否则放行。 */
    private fun stateOf(a: AppInfo): Pair<String, Int> {
        if (!a.installed) return "已卸载" to Ui.SUB
        val ver = try { Cleaner.installedVersion(act, a.pkg) } catch (_: Throwable) { -1L }
        if (ver == Cleaner.STUB_VERSION) return "顶替" to Ui.ACCENT
        when (OpsStore.status(act, a.pkg)) {
            "isolated" -> return "隔离" to Ui.AMBER
            "deleted" -> return "已删除" to Ui.AMBER
        }
        return "放行" to Ui.GREEN
    }

    /** 强制常显组件：以实时安装状态为准，未安装时合成“已卸载”卡片。 */
    private fun forcedCards(): List<AppInfo> {
        val out = ArrayList<AppInfo>()
        for ((pkg, label) in Scanner.FORCED_SHOW) {
            val live = try { Cleaner.installedVersion(act, pkg) > 0 } catch (_: Throwable) { false }
            val existing = apps.firstOrNull { it.pkg == pkg }
            when {
                live && existing != null -> out.add(existing)
                live -> {
                    val pi = try { act.packageManager.getPackageInfo(pkg, 0) } catch (_: Throwable) { null }
                    val ai = pi?.applicationInfo
                    val nm = try {
                        if (ai != null) act.packageManager.getApplicationLabel(ai).toString() else label
                    } catch (_: Throwable) { label }
                    out.add(
                        AppInfo(pkg, nm, true, pi?.versionName ?: "", pi?.longVersionCode ?: 0L,
                            ai?.uid ?: 0, true, false, emptyList(), 90,
                            listOf("运营商预装组件（默认强制显示）"))
                    )
                }
                else -> out.add(
                    AppInfo(pkg, label, true, "已卸载", 0L, -1, true, false, emptyList(), 100,
                        listOf("运营商预装组件（默认强制显示）· 当前未安装"))
                )
            }
        }
        return out
    }

    private fun requireInstalled(a: AppInfo): Boolean {
        if (a.installed) return true
        Toast.makeText(act, "${a.label} 当前未安装，无需此操作", Toast.LENGTH_SHORT).show()
        return false
    }

    /** 手动添加包名：只接受本机已安装的包，加入清理列表。 */
    private fun addManual() {
        val input = EditText(act)
        input.hint = "例如 com.miui.analytics"
        input.textSize = 13f
        input.setSingleLine(true)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setTextColor(Ui.TEXT)
        input.setHintTextColor(Ui.SUB)
        input.setBackgroundColor(Ui.CARD2)
        input.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8))
        AlertDialog.Builder(act)
            .setTitle("手动添加包名")
            .setMessage("加入清理列表；仅接受本机已安装的应用。")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val pkg = input.text.toString().trim()
                if (pkg.isEmpty()) return@setPositiveButton
                val exists = try { act.packageManager.getPackageInfo(pkg, 0); true } catch (_: Throwable) { false }
                if (!exists) {
                    Toast.makeText(act, "未安装：$pkg", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                ManualStore.add(act, pkg)
                Toast.makeText(act, "已加入：$pkg", Toast.LENGTH_SHORT).show()
                doScan()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun render() {
        listBox.removeAllViews()
        val manual = ManualStore.all(act)
        val forcedPkgs = Scanner.FORCED_SHOW.keys
        val forced = forcedCards()
        val all = apps.filter { it.pkg !in forcedPkgs } + forced
        val known = all.filter { it.pkg in Scanner.KNOWN_SUSPICIOUS || it.pkg in manual || it.pkg in Scanner.FORCED_SHOW }
        val base = if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            known.filter { it.pkg.lowercase().contains(q) || it.label.lowercase().contains(q) }
        } else known
        val shown = base.sortedWith(compareByDescending<AppInfo> { it.risk })
        status.text = "共 ${apps.size} 个应用 · 可疑 ${known.size} 个" +
            (if (forced.isNotEmpty()) "（含未安装 ${forced.size}）" else "") +
            (if (manual.isNotEmpty()) "（含手动 ${manual.size}）" else "") +
            (if (query.isNotBlank()) " · 搜索命中 ${shown.size} 个" else "") +
            (if (Shell.available()) "" else "（Shizuku 不可用，仅能查看）")
        if (shown.isEmpty()) {
            listBox.addView(Ui.card(act, Ui.text(act, "没有匹配项，试试换个关键词、点扫描或手动添加包名", 13f, Ui.SUB)))
            return
        }
        for (a in shown) listBox.addView(card(a))
    }

    private fun card(a: AppInfo): LinearLayout {
        val (state, color) = stateOf(a)
        val titleRow = Ui.row(act,
            Ui.text(act, a.label, 16f, Ui.TEXT, true),
            Ui.space(act, 8),
            chip(state, color)
        )
        val line1 = Ui.text(act, a.pkg, 11f, Ui.SUB)
        val line2 = Ui.text(
            act,
            if (!a.installed) "未安装 · 风险 ${a.risk}"
            else (if (a.system) "系统" else "用户") + " · v${a.versionName} · 风险 ${a.risk}",
            11f, Ui.SUB
        )
        val reason = Ui.text(act, a.reasons.joinToString(" / ").ifEmpty { "无命中特征" }, 11f, Ui.SUB)

        val replaceBtn = Ui.button(act, "卸载+顶替") { confirmReplace(a) }
        val isolateBtn = Ui.ghostButton(act, "隔离") { confirmIsolate(a) }
        val restoreBtn = Ui.ghostButton(act, "恢复") { confirmRestore(a) }
        val detailBtn = Ui.ghostButton(act, "详情") { showDetail(a) }
        val actions = Ui.column(act,
            Ui.row(act, replaceBtn, Ui.space(act, 6), isolateBtn),
            Ui.space(act, 6),
            Ui.row(act, restoreBtn, Ui.space(act, 6), detailBtn)
        )
        if (a.pkg in ManualStore.all(act)) {
            val rmBtn = Ui.ghostButton(act, "移出清理列表") {
                ManualStore.remove(act, a.pkg)
                Toast.makeText(act, "已移出：${a.pkg}", Toast.LENGTH_SHORT).show()
                render()
            }
            actions.addView(Ui.space(act, 6))
            actions.addView(Ui.row(act, rmBtn))
        }
        actions.visibility = View.GONE
        val detail = Ui.text(act, "点击展开操作", 11f, Ui.SUB)

        val c = Ui.card(act, titleRow, Ui.space(act, 4), line1, Ui.space(act, 2), line2, Ui.space(act, 4), reason, Ui.space(act, 6), detail, Ui.space(act, 8), actions)
        var open = false
        c.setOnClickListener {
            open = !open
            actions.visibility = if (open) View.VISIBLE else View.GONE
            detail.text = if (open) "收起" else "点击展开操作"
        }
        return c
    }

    private fun chip(text: String, color: Int): TextView {
        val tv = Ui.text(act, text, 11f, color, true)
        tv.setBackgroundColor(Ui.CARD2)
        tv.setPadding(Ui.dp(act, 8), Ui.dp(act, 3), Ui.dp(act, 8), Ui.dp(act, 3))
        return tv
    }

    private fun showDetail(a: AppInfo) {
        val msg = StringBuilder()
        msg.append("包名: ${a.pkg}\n系统应用: ${a.system}\n版本: ${a.versionName} (${a.versionCode})\nUID: ${a.uid}\n")
        msg.append("图标: ${if (a.hasLauncher) "有" else "无"}\n")
        msg.append("风险: ${a.risk}\n\n命中原因:\n")
        a.reasons.forEach { msg.append(" · ").append(it).append('\n') }
        msg.append("\n权限(").append(a.permissions.size).append("):\n")
        a.permissions.filter {
            it.contains("LOCATION") || it.contains("PHONE_STATE") || it.contains("SMS") ||
                it.contains("INTERNET") || it.contains("RECORD") || it.contains("CAMERA") ||
                it.contains("ACCOUNTS") || it.contains("QUERY_ALL")
        }.forEach { msg.append(" · ").append(it.substringAfterLast('.')).append('\n') }
        AlertDialog.Builder(act).setTitle(a.label + " · " + a.pkg).setMessage(msg.toString())
            .setPositiveButton("关闭", null).show()
    }

    private fun confirmReplace(a: AppInfo) {
        if (!requireInstalled(a)) return
        if (Scanner.isCritical(a.pkg)) return protected(a)
        if (Scanner.isProtectedDelete(a.pkg)) {
            AlertDialog.Builder(act).setTitle("已保护：${a.label}")
                .setMessage("${a.pkg} 负责定位等基础功能，卸载会导致系统功能异常，因此只允许「隔离」。")
                .setPositiveButton("知道了", null).show()
            return
        }
        AlertDialog.Builder(act)
            .setTitle("卸载并顶替 ${a.label}？")
            .setMessage(
                "1) 备份原安装包到应用外部目录（删除前立即执行）\n" +
                    "2) app_process 反射调用 deletePackageAsUser（绕过 pm uninstall 拦截）\n" +
                    "3) 安装同包名、versionCode ${Cleaner.STUB_VERSION}、异签名的空包阻止系统更新再生\n\n" +
                    "占位包装不上时：保持删除状态，不自动恢复原包（备份保留，可随时点「恢复」找回）。"
            )
            .setPositiveButton("确认执行") { _, _ -> runOp("卸载并顶替 ${a.label}") { log -> Cleaner.replace(act, a, log) } }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmIsolate(a: AppInfo) {
        if (!requireInstalled(a)) return
        if (Scanner.isCritical(a.pkg)) return protected(a)
        AlertDialog.Builder(act)
            .setTitle("隔离 ${a.label}？")
            .setMessage("网络/定位/录屏全部禁用，撤销敏感权限，强制停止并冻结该应用。")
            .setPositiveButton("隔离") { _, _ -> runOp("隔离 ${a.label}") { log -> Cleaner.isolate(act, a, log) } }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmRestore(a: AppInfo) {
        AlertDialog.Builder(act)
            .setTitle("恢复 ${a.label}？")
            .setMessage("移除占位包（如存在），安装备份的原始安装包；无备份时尝试 install-existing。")
            .setPositiveButton("恢复") { _, _ -> runOp("恢复 ${a.label}") { log -> Cleaner.restore(act, a, log) } }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun protected(a: AppInfo) {
        AlertDialog.Builder(act).setTitle("受保护的关键组件")
            .setMessage("${a.label}（${a.pkg}）属于系统关键组件，本应用拒绝对其执行删除/隔离。")
            .setPositiveButton("知道了", null).show()
    }

    /** 执行操作：不可取消的进度对话框 + 实时日志。 */
    private fun runOp(title: String, block: ((String) -> Unit) -> OpResult) {
        if (busy) return
        busy = true
        val logView = TextView(act)
        logView.textSize = 11f
        logView.setTextColor(Ui.SUB)
        logView.typeface = Typeface.MONOSPACE
        logView.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8))
        val logScroll = ScrollView(act)
        logScroll.addView(logView)
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal)
        bar.isIndeterminate = true
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(act, 8), Ui.dp(act, 4), Ui.dp(act, 8), Ui.dp(act, 4))
        box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 6)))
        box.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 300)).apply { topMargin = Ui.dp(act, 10) })
        val dlg = AlertDialog.Builder(act).setTitle(title).setView(box)
            .setNegativeButton("关闭", null).setCancelable(false).create()
        dlg.show()

        fun append(s: String) {
            main.post {
                logView.append(s + "\n")
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }

        Thread {
            val r = try {
                block { line -> append("· $line") }
            } catch (t: Throwable) {
                Logx.e("操作异常", t); OpResult(false, "异常: ${t.message}")
            }
            main.post {
                busy = false
                bar.visibility = View.GONE
                logView.append((if (r.ok) "\n✓ " else "\n✗ ") + r.message + (if (r.detail.isNotEmpty()) "\n" + r.detail else ""))
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                status.text = r.message
                refresh()
            }
        }.start()
    }
}
