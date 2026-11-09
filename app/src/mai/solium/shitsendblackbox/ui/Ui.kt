package mai.solium.shitsendblackbox.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 纯 framework 控件的小工具集，避免引入 AndroidX。 */
object Ui {
    const val BG = 0xFFF4F5F7.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val CARD2 = 0xFFEDEFF2.toInt()
    const val TEXT = 0xFF111111.toInt()
    const val SUB = 0xFF666666.toInt()
    const val ACCENT = 0xFFD32F2F.toInt()
    const val GREEN = 0xFF2E7D32.toInt()
    const val AMBER = 0xFFB26A00.toInt()
    const val LINE = 0xFFE3E5E8.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun text(ctx: Context, s: CharSequence, size: Float = 15f, color: Int = TEXT, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        return t
    }

    fun button(ctx: Context, label: String, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = label
        b.textSize = 14f
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.background = rounded(ACCENT, dp(ctx, 10))
        b.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
        b.setOnClickListener { onClick() }
        return b
    }

    fun ghostButton(ctx: Context, label: String, onClick: () -> Unit): Button {
        val b = button(ctx, label, onClick)
        b.setTextColor(TEXT)
        b.background = rounded(0xFFEDEFF2.toInt(), dp(ctx, 10))
        return b
    }

    fun rounded(color: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radius.toFloat()
        return g
    }

    fun card(ctx: Context, vararg children: View): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        l.background = rounded(CARD, dp(ctx, 14))
        l.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(ctx, 12)
        l.layoutParams = lp
        children.forEach { l.addView(it) }
        return l
    }

    fun row(ctx: Context, vararg children: View): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        children.forEach { l.addView(it) }
        return l
    }

    fun column(ctx: Context, vararg children: View): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        children.forEach { l.addView(it) }
        return l
    }

    fun divider(ctx: Context): View {
        val v = View(ctx)
        v.setBackgroundColor(LINE)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1))
        lp.topMargin = dp(ctx, 8)
        lp.bottomMargin = dp(ctx, 8)
        v.layoutParams = lp
        return v
    }

    fun space(ctx: Context, h: Int): View {
        val v = View(ctx)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(ctx, h))
        return v
    }

    fun label(ctx: Context, s: String): TextView = text(ctx, s, 12f, SUB)
}
