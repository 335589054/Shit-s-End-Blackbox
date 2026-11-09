package mai.solium.shitsendblackbox

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import mai.solium.shitsendblackbox.core.Logx
import mai.solium.shitsendblackbox.core.ShizukuGate
import mai.solium.shitsendblackbox.ui.NetPage
import mai.solium.shitsendblackbox.ui.Page
import mai.solium.shitsendblackbox.ui.PermPage
import mai.solium.shitsendblackbox.ui.ScanPage
import mai.solium.shitsendblackbox.ui.SettingsPage
import mai.solium.shitsendblackbox.ui.Ui
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private lateinit var tabs: Array<TextView>
    private lateinit var pages: Array<Page>
    private lateinit var statusSpacer: View
    private var current = 0

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        runOnUiThread {
            Logx.d("Shizuku 授权结果: $grant")
            pages[current].refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logx.init(this)
        Logx.d("应用启动，Shizuku=${ShizukuGate.state(this)}")
        try {
            Shizuku.addRequestPermissionResultListener(permListener)
        } catch (_: Throwable) {
        }
        val root = buildUi()
        setContentView(root)
        applyEdgeToEdge(root)
        select(0)
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::pages.isInitialized) pages[current].refresh()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::pages.isInitialized) pages.forEach { it.onActivityResult(requestCode, resultCode, data) }
    }

    /** Android 15+ 强制 edge-to-edge：手动处理状态栏/挖孔与底部导航栏 insets。 */
    private fun applyEdgeToEdge(root: View) {
        if (Build.VERSION.SDK_INT < 30) return
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val lp = statusSpacer.layoutParams
            if (lp.height != i.top) {
                lp.height = i.top
                statusSpacer.layoutParams = lp
            }
            content.setPadding(0, 0, 0, i.bottom)
            v.onApplyWindowInsets(insets)
        }
        root.requestApplyInsets()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)

        // 状态栏 / 挖孔区域占位：高度由 window insets 决定，颜色与标题栏一致
        statusSpacer = View(this)
        statusSpacer.setBackgroundColor(0xFF111111.toInt())
        root.addView(statusSpacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))

        // 顶部标题
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setBackgroundColor(0xFF111111.toInt())
        header.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 12))
        val title = Ui.text(this, "史终黑盒", 20f, Color.WHITE, true)
        val sub = Ui.text(this, "  · 隐私自保", 12f, 0xFF9AA0A6.toInt())
        header.addView(title)
        header.addView(sub)
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 标签栏
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(Color.WHITE)
        val labels = arrayOf("清理", "权限", "网络", "设置")
        tabs = Array(labels.size) { i ->
            val t = Ui.text(this, labels[i], 15f, Ui.SUB, false)
            t.gravity = Gravity.CENTER
            t.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 10))
            t.setOnClickListener { select(i) }
            bar.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            t
        }
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        pages = arrayOf(ScanPage(this), PermPage(this), NetPage(this), SettingsPage(this))
        return root
    }

    private fun select(i: Int) {
        current = i
        tabs.forEachIndexed { idx, t ->
            t.setTextColor(if (idx == i) Ui.ACCENT else Ui.SUB)
            t.setTypeface(t.typeface, if (idx == i) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        content.removeAllViews()
        content.addView(pages[i].view())
        pages[i].refresh()
    }
}
