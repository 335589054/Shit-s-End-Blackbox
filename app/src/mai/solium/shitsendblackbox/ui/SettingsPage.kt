package mai.solium.shitsendblackbox.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import mai.solium.shitsendblackbox.core.Logx
import mai.solium.shitsendblackbox.core.Shell
import mai.solium.shitsendblackbox.core.ShizukuGate

class SettingsPage(private val act: Activity) : Page {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var stateLine: TextView
    private lateinit var detailLine: TextView
    private lateinit var diagOut: TextView
    private lateinit var logView: TextView

    override fun view(): View {
        val scroll = ScrollView(act)
        scroll.setBackgroundColor(Ui.BG)
        root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 24))
        scroll.addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val head = Ui.column(act,
            Ui.text(act, "设置", 22f, Ui.TEXT, true),
            Ui.text(act, "Shizuku 授权、诊断与日志", 13f, Ui.SUB)
        )
        root.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(act, 14) })

        // Shizuku 卡片
        stateLine = Ui.text(act, "状态：检查中…", 15f, Ui.TEXT, true)
        detailLine = Ui.text(act, "", 12f, Ui.SUB)
        val permBtn = Ui.button(act, "申请 Shizuku 授权") {
            if (!ShizukuGate.requestPermission()) {
                Toast.makeText(act, "Shizuku 未运行，请先在 Shizuku 应用中启动服务", Toast.LENGTH_LONG).show()
            }
        }
        val refreshBtn = Ui.ghostButton(act, "刷新状态") { refresh() }
        val btnRow = Ui.row(act, permBtn, Ui.space(act, 8), refreshBtn)
        root.addView(Ui.card(act,
            Ui.label(act, "SHIZUKU"),
            Ui.space(act, 6),
            stateLine,
            detailLine,
            Ui.space(act, 10),
            btnRow
        ))

        // 诊断
        diagOut = Ui.text(act, "点击下方按钮执行环境自检", 12f, Ui.SUB)
        diagOut.setPadding(Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10))
        diagOut.setBackgroundColor(0xFFF7F7F8.toInt())
        val diagBtn = Ui.button(act, "运行诊断") { runDiag() }
        root.addView(Ui.card(act,
            Ui.label(act, "环境诊断"),
            Ui.space(act, 6),
            diagOut,
            Ui.space(act, 8),
            diagBtn
        ))

        // 日志
        logView = Ui.text(act, "", 11f, Ui.SUB)
        logView.setBackgroundColor(0xFFF7F7F8.toInt())
        logView.setPadding(Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10))
        val clearBtn = Ui.ghostButton(act, "清空日志") { Logx.clear(); refresh() }
        val copyBtn = Ui.ghostButton(act, "复制日志") {
            val cm = act.getSystemService(Activity.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", Logx.dump()))
            Toast.makeText(act, "已复制", Toast.LENGTH_SHORT).show()
        }
        root.addView(Ui.card(act,
            Ui.label(act, "运行日志"),
            Ui.space(act, 6),
            logView,
            Ui.space(act, 8),
            Ui.row(act, clearBtn, Ui.space(act, 8), copyBtn)
        ))

        // 关于
        root.addView(Ui.card(act,
            Ui.label(act, "关于"),
            Ui.space(act, 6),
            Ui.text(act, "史终黑盒 v1.0", 14f, Ui.TEXT),
            Ui.text(act, "仅通过 Shizuku 提权，运行时不使用 root。\n本工具会修改系统组件状态，请确认风险后再操作。", 12f, Ui.SUB)
        ))
        return scroll
    }

    override fun refresh() {
        val st = ShizukuGate.state(act)
        val color = when (st) {
            ShizukuGate.State.READY -> Ui.GREEN
            ShizukuGate.State.NO_PERMISSION -> Ui.AMBER
            else -> Ui.ACCENT
        }
        stateLine.text = "状态：${st.label}"
        stateLine.setTextColor(color)
        val ver = ShizukuGate.version()
        val uid = ShizukuGate.serverUid()
        detailLine.text = "Shizuku 版本：${if (ver >= 0) ver else "?"}   服务 UID：${if (uid >= 0) uid else "?"}   SELinux：${ShizukuGate.selinux()}"
        logView.text = Logx.dump().lines().takeLast(14).joinToString("\n").ifEmpty { "（暂无日志）" }
    }

    private fun runDiag() {
        diagOut.text = "执行中…"
        Thread {
            val sb = StringBuilder()
            val r = Shell.exec("id; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.cpu.abi; pm list packages | wc -l")
            sb.append("exit=").append(r.code).append('\n')
            sb.append(r.out.trim()).append('\n')
            if (r.err.isNotBlank()) sb.append("err: ").append(r.err.trim()).append('\n')
            val path = Shell.exec("pm path com.mobiletools.systemhelper")
            sb.append("systemhelper: ").append(path.out.trim().ifEmpty { path.err.trim() })
            main.post {
                diagOut.text = sb.toString()
                refresh()
            }
        }.start()
    }
}
