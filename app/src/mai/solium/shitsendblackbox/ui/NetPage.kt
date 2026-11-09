package mai.solium.shitsendblackbox.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import mai.solium.shitsendblackbox.core.Logx
import mai.solium.shitsendblackbox.core.Shell
import mai.solium.shitsendblackbox.data.BlocklistStore
import mai.solium.shitsendblackbox.data.SettingsStore
import mai.solium.shitsendblackbox.network.BlockVpn
import mai.solium.shitsendblackbox.network.NetMon

/** 第 3 页：DNS 污染 + IP 屏蔽（VpnService 分流实现）。 */
class NetPage(private val act: Activity) : Page {
    companion object {
        const val REQ_VPN = 7001
        const val REQ_REPORT = 7002
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var toggle: android.widget.Button
    private lateinit var groupsBox: LinearLayout
    private lateinit var monStatus: TextView
    private lateinit var monBtn: android.widget.Button
    private lateinit var fullChk: android.widget.CheckBox
    private var pendingMonitor = false
    private var lastReport = ""
    private val tick = object : Runnable {
        override fun run() {
            refreshMon()
            if (NetMon.active) main.postDelayed(this, 1000)
        }
    }

    override fun view(): View {
        val scroll = ScrollView(act)
        root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 24))
        scroll.addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(Ui.column(act,
            Ui.text(act, "网络", 22f, Ui.TEXT, true),
            Ui.text(act, "把已知回传域名解析到 127.0.0.1，并丢弃回传 IP 的流量", 12f, Ui.SUB)
        ))

        status = Ui.text(act, "状态：读取中…", 14f, Ui.TEXT, true)
        fullChk = CheckBox(act).apply {
            text = "全隧道模式（实验）：接管全部流量 + 读 TLS SNI，可监控/屏蔽 App 自带 DoH"
            textSize = 12f
            setTextColor(Ui.SUB)
            isChecked = SettingsStore.flag(act, SettingsStore.FULL_TUNNEL, false)
            setOnCheckedChangeListener { _, v ->
                SettingsStore.setFlag(act, SettingsStore.FULL_TUNNEL, v)
                if (BlockVpn.running) {
                    android.widget.Toast.makeText(act, "正在按新模式重启屏蔽…", android.widget.Toast.LENGTH_SHORT).show()
                    restartIfRunning()
                }
                refresh()
            }
        }
        toggle = Ui.button(act, "开启屏蔽") { toggleVpn() }
        val testBtn = Ui.ghostButton(act, "测试解析") { testDns() }
        val testIpHint = Ui.ghostButton(act, "测试 IP") { testIp() }
        root.addView(Ui.card(act, status, Ui.space(act, 8),
            Ui.row(act, toggle, Ui.space(act, 6), testBtn, Ui.space(act, 6), testIpHint),
            Ui.space(act, 6),
            Ui.text(act, "原理：VPN 只接管 DNS 与黑名单 IP，不改默认路由；首次开启需系统授权。", 11f, Ui.SUB),
            Ui.space(act, 6),
            fullChk,
            Ui.space(act, 6),
            Ui.row(act, Ui.ghostButton(act, "隧道自测") { tunnelTest() })))

        monStatus = Ui.text(act, "监控模式：已关闭", 13f, Ui.TEXT, true)
        monBtn = Ui.button(act, "开始监控") { toggleMonitor() }
        root.addView(Ui.card(act,
            Ui.label(act, "监控模式"),
            Ui.space(act, 6),
            monStatus,
            Ui.space(act, 4),
            Ui.text(act, "开启后记录所有经过本机 VPN 的 DNS 查询（谁在解析哪些域名）；再次点击停止并生成分析报告，" +
                "可一键把可疑回传域名加入屏蔽。需要 VPN 保持开启。", 11f, Ui.SUB),
            Ui.space(act, 8),
            Ui.row(act, monBtn)
        ))

        groupsBox = LinearLayout(act)
        groupsBox.orientation = LinearLayout.VERTICAL
        root.addView(groupsBox)

        val addBtn = Ui.button(act, "添加域名") { addDomain() }
        val addIpBtn = Ui.ghostButton(act, "添加 IP") { addIp() }
        val upBtn = Ui.ghostButton(act, "上游 DNS") { editUpstream() }
        root.addView(Ui.card(act,
            Ui.label(act, "手动维护"),
            Ui.space(act, 8),
            Ui.row(act, addBtn, Ui.space(act, 8), addIpBtn, Ui.space(act, 8), upBtn)
        ))

        root.addView(Ui.card(act,
            Ui.label(act, "注意"),
            Ui.space(act, 4),
            Ui.text(act, "· 部分应用使用 DoH/DoT 或硬编码 IP，可绕过 DNS 污染，需要额外 IP 规则\n" +
                "· 关闭 VPN 后规则立即失效\n" +
                "· 本功能只影响本机流量，不修改 hosts 文件", 11f, Ui.SUB)
        ))
        return scroll
    }

    override fun refresh() {
        status.text = "状态：" + if (BlockVpn.running) "运行中" else "已关闭"
        status.setTextColor(if (BlockVpn.running) Ui.GREEN else Ui.SUB)
        toggle.text = if (BlockVpn.running) "关闭屏蔽" else "开启屏蔽"
        if (BlockVpn.running && SettingsStore.flag(act, SettingsStore.FULL_TUNNEL, false)) {
            status.text = "状态：运行中（全隧道）"
        }
        renderMon()
        renderGroups()
    }

    private fun renderMon() {
        if (NetMon.active) {
            monBtn.text = "停止并生成报告"
            monStatus.setTextColor(Ui.GREEN)
            monStatus.text = "监控中 · 已记录 ${NetMon.domainCount()} 个域名 / ${NetMon.queryCount()} 次查询"
        } else {
            monBtn.text = "开始监控"
            monStatus.setTextColor(Ui.SUB)
            monStatus.text = "监控模式：已关闭"
        }
    }

    private fun refreshMon() {
        if (!NetMon.active) return
        monStatus.text = "监控中 · 已记录 ${NetMon.domainCount()} 个域名 / ${NetMon.queryCount()} 次查询"
    }

    private fun toggleMonitor() {
        if (NetMon.active) {
            NetMon.stop()
            val report = try { NetMon.buildReport(act) } catch (t: Throwable) {
                Logx.w("报告生成失败: ${t.message}"); ""
            }
            lastReport = report
            refresh()
            showReport(report)
            return
        }
        if (NetMon.domainCount() > 0) NetMon.cancel()
        if (BlockVpn.running) {
            startMonitor()
            return
        }
        // 监控依赖 DNS 代理，VPN 未开先申请授权
        pendingMonitor = true
        val prepare = VpnService.prepare(act)
        if (prepare != null) {
            Toast.makeText(act, "监控需要 VPN，请允许连接请求", Toast.LENGTH_SHORT).show()
            act.startActivityForResult(prepare, REQ_VPN)
        } else {
            BlockVpn.start(act)
            main.postDelayed({ startMonitor() }, 800)
        }
    }

    private fun startMonitor() {
        if (!BlockVpn.running) {
            Toast.makeText(act, "VPN 尚未就绪，请稍后再点一次", Toast.LENGTH_SHORT).show()
            pendingMonitor = false
            return
        }
        pendingMonitor = false
        NetMon.start()
        Toast.makeText(act, "监控已开启，正常使用手机即可；再次点击停止并导出报告", Toast.LENGTH_LONG).show()
        refresh()
        main.postDelayed(tick, 1000)
    }

    private fun showReport(report: String) {
        if (report.isBlank()) {
            Toast.makeText(act, "没有记录到 DNS 查询", Toast.LENGTH_SHORT).show()
            return
        }
        val tv = TextView(act)
        tv.textSize = 11f
        tv.setTextColor(Ui.TEXT)
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.text = report
        val scroll = ScrollView(act)
        scroll.addView(tv)
        val suspects = try { NetMon.suspectedDomains(act) } catch (_: Throwable) { emptyList() }
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8))
        box.addView(Ui.text(act, NetMon.summary(act), 12f, Ui.SUB, true))
        box.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 320)).apply { topMargin = Ui.dp(act, 8) })

        val dlg = AlertDialog.Builder(act)
            .setTitle("网络监控报告")
            .setView(box)
            .setPositiveButton("导出报告…") { _, _ -> exportReport() }
            .setNeutralButton("屏蔽疑似域名(${suspects.size})") { _, _ ->
                if (suspects.isEmpty()) {
                    Toast.makeText(act, "没有可屏蔽的疑似域名", Toast.LENGTH_SHORT).show()
                } else {
                    for (d in suspects) BlocklistStore.addDomain(act, d)
                    restartIfRunning()
                    Toast.makeText(act, "已加入屏蔽 ${suspects.size} 个域名", Toast.LENGTH_LONG).show()
                    refresh()
                }
            }
            .setNegativeButton("关闭", null)
            .create()
        dlg.show()
    }

    private fun exportReport() {
        val text = lastReport.ifBlank { try { NetMon.buildReport(act) } catch (_: Throwable) { "" } }
        if (text.isBlank()) {
            Toast.makeText(act, "报告为空", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/markdown"
            putExtra(Intent.EXTRA_TITLE, "shitsendblackbox-netmon-$stamp.md")
        }
        try {
            act.startActivityForResult(i, REQ_REPORT)
        } catch (t: Throwable) {
            Toast.makeText(act, "无法打开保存面板：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderGroups() {
        groupsBox.removeAllViews()
        val domains = BlocklistStore.activeDomains(act)
        val ips = BlocklistStore.activeIps(act)
        groupsBox.addView(Ui.text(act, "生效中：${domains.size} 个域名 · ${ips.size} 个 IP", 12f, Ui.SUB)
            .also { it.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 8)) })

        for (g in BlocklistStore.groups(act)) {
            val cb = CheckBox(act)
            cb.isChecked = g.enabled
            cb.textSize = 13f
            cb.setTextColor(Ui.TEXT)
            cb.text = g.name
            cb.setOnCheckedChangeListener { _, v ->
                BlocklistStore.setEnabled(act, g.id, v)
                restartIfRunning()
                refresh()
            }
            val detail = Ui.text(act, "${g.domains.size} 域名 · ${g.ips.size} IP · 点击查看", 11f, Ui.SUB)
            val col = Ui.column(act, cb, detail)
            col.setOnClickListener { showGroup(g.id) }
            groupsBox.addView(Ui.card(act, col))
        }
    }

    private fun showGroup(id: String) {
        val g = BlocklistStore.groups(act).firstOrNull { it.id == id } ?: return
        val sb = StringBuilder()
        sb.append("域名：\n")
        g.domains.forEach { sb.append("  ").append(it).append('\n') }
        if (g.ips.isNotEmpty()) {
            sb.append("\nIP：\n")
            g.ips.forEach { sb.append("  ").append(it).append('\n') }
        }
        AlertDialog.Builder(act).setTitle(g.name).setMessage(sb.toString())
            .setPositiveButton("删除某个域名") { _, _ -> deleteDomain(g.domains) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun deleteDomain(domains: List<String>) {
        if (domains.isEmpty()) return
        AlertDialog.Builder(act).setTitle("选择要移除的域名")
            .setItems(domains.toTypedArray()) { _, which ->
                BlocklistStore.removeDomain(act, domains[which])
                restartIfRunning()
                refresh()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun testDns() {
        val domains = BlocklistStore.activeDomains(act)
        val probe = domains.firstOrNull { it == "dm.wo.com.cn" } ?: domains.firstOrNull()
        if (probe == null) {
            Toast.makeText(act, "没有生效中的域名", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(act, "解析中…", Toast.LENGTH_SHORT).show()
        Thread {
            // 全隧道模式下本 App 被排除在 VPN 外，用本进程的 InetAddress 测不出屏蔽效果；
            // 改由 Shizuku 以 shell 身份解析（shell 在 VPN 内，走的是真实的 VPN DNS 路径）。
            fun resolveViaShell(host: String): String? {
                val r = Shell.exec("ping -c 1 -W 1 '$host' 2>&1", 6_000)
                val m = Regex("""\(\s*([0-9a-fA-F:.]+)\s*\)""").find(r.out)
                return m?.groupValues?.get(1)
            }
            fun resolveLocal(host: String): String = try {
                java.net.InetAddress.getByName(host).hostAddress ?: "null"
            } catch (t: Throwable) {
                "ERR ${t.javaClass.simpleName}"
            }
            val shellOk = Shell.available()
            val blocked = (if (shellOk) resolveViaShell(probe) else null) ?: resolveLocal(probe)
            val allowed = (if (shellOk) resolveViaShell("example.com") else null) ?: resolveLocal("example.com")
            main.post {
                val via = if (shellOk) "（经 VPN 内 shell 解析，结果代表其他 App 的实际解析）"
                          else "（Shizuku 不可用，用本机直连解析，可能绕过 VPN）"
                AlertDialog.Builder(act).setTitle("DNS 解析测试")
                    .setMessage("$probe\n  -> $blocked\n\nexample.com\n  -> $allowed\n\n" +
                        "屏蔽域名应得到 127.0.0.1/::1；正常域名应得到公网 IP。\n$via")
                    .setPositiveButton("好", null).show()
            }
        }.start()
    }

    private fun testIp() {
        val ips = BlocklistStore.activeIps(act)
        val probe = ips.firstOrNull { ip ->
            !(ip.startsWith("10.") || ip.startsWith("127.") ||
                ip.startsWith("192.168.") || ip.matches(Regex("^172\\.(1[6-9]|2\\d|3[01])\\..*")))
        } ?: ips.firstOrNull()
        if (probe == null) {
            Toast.makeText(act, "没有生效中的 IP", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(act, "连接测试中…", Toast.LENGTH_SHORT).show()
        Thread {
            fun conn(ip: String, port: Int): String = try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(ip, port), 3000)
                    "已连接"
                }
            } catch (t: Throwable) {
                t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
            }
            val blocked = conn(probe, 443)
            val control = conn("104.20.23.154", 80)
            main.post {
                AlertDialog.Builder(act).setTitle("IP 丢包测试")
                    .setMessage("屏蔽 IP $probe:443\n  -> $blocked\n\n对照 104.20.23.154:80\n  -> $control\n\n" +
                        "屏蔽 IP 应超时（被丢包）；对照 IP 应能正常连接。")
                    .setPositiveButton("好", null).show()
            }
        }.start()
    }

    /** 全隧道自测：App 自己的未保护 socket 会被路由进 tun，能同时验证 DNS、TCP、SNI。 */
    private fun tunnelTest() {
        Toast.makeText(act, "正在通过隧道访问 example.com …", Toast.LENGTH_SHORT).show()
        Thread {
            val sb = StringBuilder()
            val t0 = System.currentTimeMillis()
            try {
                val s = java.net.Socket()
                s.connect(java.net.InetSocketAddress("example.com", 80), 10000)
                s.soTimeout = 10000
                val req = "GET / HTTP/1.0\r\nHost: example.com\r\n\r\n"
                s.getOutputStream().write(req.toByteArray())
                s.getOutputStream().flush()
                val r = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream()))
                val first = r.readLine() ?: "(空响应)"
                sb.append("HTTP 首行：").append(first).append("\n")
                var body = 0
                while (true) {
                    val line = r.readLine() ?: break
                    body += line.length + 1
                    if (body > 4000) break
                }
                sb.append("收到正文约 ").append(body).append(" 字节\n")
                s.close()
            } catch (t: Throwable) {
                sb.append("失败：").append(t.javaClass.simpleName).append(": ").append(t.message)
            }
            sb.append("\n耗时 ").append(System.currentTimeMillis() - t0).append(" ms")
            act.runOnUiThread {
                AlertDialog.Builder(act)
                    .setTitle("隧道自测")
                    .setMessage(sb.toString())
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun toggleVpn() {
        Logx.d("点击屏蔽开关 running=${BlockVpn.running}")
        if (BlockVpn.running) {
            BlockVpn.stop(act)
            SettingsStore.setFlag(act, SettingsStore.VPN_ON, false)
            main.postDelayed({ refresh() }, 400)
            return
        }
        val prepare = VpnService.prepare(act)
        if (prepare != null) {
            Toast.makeText(act, "请允许 VPN 连接请求", Toast.LENGTH_SHORT).show()
            act.startActivityForResult(prepare, REQ_VPN)
        } else {
            BlockVpn.start(act)
            main.postDelayed({ refresh() }, 600)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_VPN && resultCode == Activity.RESULT_OK) {
            BlockVpn.start(act)
            main.postDelayed({ refresh() }, 600)
            if (pendingMonitor) main.postDelayed({ startMonitor() }, 900)
        }
        if (requestCode == REQ_REPORT && resultCode == Activity.RESULT_OK && data?.data != null) {
            val uri = data.data!!
            val text = lastReport.ifBlank { NetMon.buildReport(act) }
            try {
                act.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                Toast.makeText(act, "报告已导出", Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                Toast.makeText(act, "导出失败：${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun restartIfRunning() {
        if (!BlockVpn.running) return
        BlockVpn.stop(act)
        main.postDelayed({
            try { BlockVpn.start(act) } catch (t: Throwable) { Logx.w("VPN 重启失败: ${t.message}") }
        }, 500)
    }

    private fun addDomain() {
        val e = EditText(act)
        e.hint = "例如 tracking.example.com（不要带 http://）"
        AlertDialog.Builder(act).setTitle("添加屏蔽域名").setView(e)
            .setPositiveButton("添加") { _, _ ->
                val raw = e.text.toString().trim().lowercase()
                val d = raw.removePrefix("http://").removePrefix("https://").trimEnd('/')
                if (!d.matches(Regex("^[a-z0-9][a-z0-9.\\-]{2,252}$")) || !d.contains('.')) {
                    Toast.makeText(act, "域名格式不正确", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                BlocklistStore.addDomain(act, d)
                restartIfRunning()
                Toast.makeText(act, "已添加 $d", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun addIp() {
        val e = EditText(act)
        e.hint = "例如 203.0.113.5"
        AlertDialog.Builder(act).setTitle("添加屏蔽 IP").setView(e)
            .setPositiveButton("添加") { _, _ ->
                val ip = e.text.toString().trim()
                if (!ip.matches(Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"))) {
                    Toast.makeText(act, "IP 格式不正确", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                BlocklistStore.addIp(act, ip)
                restartIfRunning()
                Toast.makeText(act, "已添加 $ip", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun editUpstream() {
        val e = EditText(act)
        e.setText(SettingsStore.string(act, SettingsStore.DNS_UPSTREAM, "223.5.5.5"))
        AlertDialog.Builder(act).setTitle("上游 DNS").setView(e)
            .setPositiveButton("保存") { _, _ ->
                val v = e.text.toString().trim()
                if (v.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) {
                    SettingsStore.setString(act, SettingsStore.DNS_UPSTREAM, v)
                    restartIfRunning()
                    Toast.makeText(act, "已保存，重启 VPN 后生效", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(act, "格式不正确", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null).show()
    }
}
