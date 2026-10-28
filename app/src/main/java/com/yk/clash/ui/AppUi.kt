package com.yk.clash.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.Shape
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout
import android.widget.TextView

class AppUi(val context: Context) {
    val dark = when (context.getSharedPreferences("interface", Context.MODE_PRIVATE).getString("theme", "system")) {
        "dark" -> true
        "light" -> false
        else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    val page = color("#F0F2EE", "#121510")
    val card = color("#FCFDF9", "#252923")
    val block = color("#E9EDE5", "#33392F")
    val inset = color("#DEE4D8", "#424B3C")
    val ink = color("#22291F", "#F1F5EC")
    val muted = color("#737C6C", "#A7B09F")
    val primary = color("#4E6B3C", "#B0D397")
    val onPrimary = color("#FFFFFF", "#1D3612")
    val good = color("#39774C", "#97D5A2")
    val warning = color("#A36C20", "#E5BF78")
    val danger = color("#B24A45", "#F2A7A0")

    private fun color(light: String, dark: String) = Color.parseColor(if (this.dark) dark else light)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun text(value: String, size: Float = 14f, color: Int = ink, medium: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    fun surface(color: Int = card, radius: Int = 24): Drawable = ShapeDrawable(ContinuousShape(dp(radius).toFloat())).apply { paint.color = color }
    fun touch(color: Int = card, radius: Int = 20): Drawable = RippleDrawable(
        ColorStateList.valueOf(if (dark) 0x20FFFFFF else 0x15000000), surface(color, radius), surface(Color.WHITE, radius)
    )
    fun panel(radius: Int = 24) = column().apply {
        background = surface(card, radius); setPadding(dp(20), dp(18), dp(20), dp(18))
    }
    fun button(label: String, filled: Boolean = false, action: () -> Unit): TextView = text(
        label, 14f, if (filled) onPrimary else primary, true
    ).apply {
        gravity = Gravity.CENTER; minHeight = dp(46); minWidth = dp(64)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        paintButton(this, filled)
        isClickable = true; isFocusable = true; setOnClickListener { action() }
    }
    fun paintButton(button: TextView, selected: Boolean) {
        button.isSelected = selected
        button.setTextColor(if (selected) onPrimary else primary)
        button.background = touch(if (selected) primary else block, 18)
    }
    fun iconButton(glyph: Glyph, description: String, filled: Boolean = false, action: () -> Unit): View = GlyphView(context, glyph,
        if (filled) onPrimary else primary).apply {
        contentDescription = description; minimumWidth = dp(44); minimumHeight = dp(44)
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = touch(if (filled) primary else block, 17)
        isClickable = true; isFocusable = true; setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }
    fun backButton(action: () -> Unit): View = iconButton(Glyph.BACK, "返回上一页", action = action).apply {
        background = touch(Color.TRANSPARENT, 17)
    }
    fun styleInput(input: EditText) = input.apply {
        textSize = 14f; setTextColor(ink); setHintTextColor(muted)
        backgroundTintList = null
        background = surface(block, 16)
        setPadding(dp(14), dp(12), dp(14), dp(12)); minimumHeight = dp(48)
        setTextCursorDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(primary); setSize(dp(2), dp(24))
        })
        highlightColor = (primary and 0x00ffffff) or 0x40000000
        setOnFocusChangeListener { _, focused -> background = surface(if (focused) inset else block, 16) }
    }
    fun searchField(hintText: String, initial: String = "", changed: (String) -> Unit): View {
        val input = EditText(context).apply {
            hint = hintText; isSingleLine = true; textSize = 14f
            setTextColor(ink); setHintTextColor(muted); background = null
            setPadding(dp(8), dp(10), dp(4), dp(10)); minimumHeight = dp(48)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setText(initial)
        }
        val clear = iconButton(Glyph.CLOSE, "清空搜索") { input.text.clear() }.apply {
            visibility = if (initial.isEmpty()) View.GONE else View.VISIBLE; background = touch(Color.TRANSPARENT, 14)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                changed(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        return row().apply {
            background = surface(block, 16); setPadding(dp(12), 0, dp(4), 0)
            addView(icon(Glyph.SEARCH, muted, 20))
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(clear)
        }
    }
    fun choiceDialog(title: String, labels: List<String>, details: List<String>, selected: Int, choose: (Int) -> Unit) {
        lateinit var dialog: AlertDialog
        val body = column().apply { setPadding(dp(20), dp(20), dp(20), dp(8)) }
        body.addView(text(title, 21f, ink, true)); body.addView(gap(18))
        labels.forEachIndexed { index, label ->
            val checked = index == selected
            val entry = row().apply {
                background = touch(if (checked) primary else block, 18)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(column().apply {
                    addView(text(label, 15f, if (checked) onPrimary else ink, true))
                    details.getOrNull(index)?.takeIf { it.isNotBlank() }?.let {
                        addView(gap(6)); addView(text(it, 12f, if (checked) onPrimary else muted))
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (checked) addView(icon(Glyph.CHECK, onPrimary, 22))
                isSelected = checked; isClickable = true; isFocusable = true
                contentDescription = "$label，${if (checked) "已选择" else "未选择"}"
                setOnClickListener { dialog.dismiss(); choose(index) }
            }
            body.addView(entry, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        dialog = AlertDialog.Builder(context).setView(ScrollView(context).apply { addView(body) })
            .setNegativeButton("取消", null).create()
        dialog.show(); styleDialog(dialog)
    }
    fun icon(glyph: Glyph, color: Int = primary, size: Int = 22) = GlyphView(context, glyph, color).apply {
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }
    fun gap(height: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    fun badge(label: String, foreground: Int = primary) = text(label, 11f, foreground, true).apply {
        background = surface(block, 9); setPadding(dp(8), dp(5), dp(8), dp(5))
    }
    fun styleDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(surface(card, 28))
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.apply {
                isAllCaps = false; setTextColor(if (which == AlertDialog.BUTTON_POSITIVE) onPrimary else primary)
                background = touch(if (which == AlertDialog.BUTTON_POSITIVE) primary else block, 15)
                minHeight = dp(42); minimumHeight = dp(42); stateListAnimator = null
                setPadding(dp(18), dp(8), dp(18), dp(8))
            }
        }
    }
    fun empty(title: String, detail: String, glyph: Glyph = Glyph.PROXIES) = column().apply {
        gravity = Gravity.CENTER; setPadding(dp(24), dp(48), dp(24), dp(48))
        addView(icon(glyph, muted, 40)); addView(gap(18))
        addView(text(title, 18f, ink, true).apply { gravity = Gravity.CENTER }); addView(gap(8))
        addView(text(detail, 13f, muted).apply { gravity = Gravity.CENTER })
    }
}

private class ContinuousShape(private val radius: Float) : Shape() {
    private val path = Path()
    override fun onResize(width: Float, height: Float) {
        val r = radius.coerceAtMost(minOf(width, height) / 2)
        val c = r * 0.22f
        path.reset()
        path.moveTo(r, 0f); path.lineTo(width - r, 0f)
        path.cubicTo(width - c, 0f, width, c, width, r)
        path.lineTo(width, height - r); path.cubicTo(width, height - c, width - c, height, width - r, height)
        path.lineTo(r, height); path.cubicTo(c, height, 0f, height - c, 0f, height - r)
        path.lineTo(0f, r); path.cubicTo(0f, c, c, 0f, r, 0f); path.close()
    }
    override fun draw(canvas: Canvas, paint: Paint) { canvas.drawPath(path, paint) }
    @Suppress("DEPRECATION")
    override fun getOutline(outline: Outline) {
        if (path.isEmpty) outline.setEmpty()
        else if (android.os.Build.VERSION.SDK_INT >= 30) outline.setPath(path)
        else outline.setConvexPath(path)
    }
}
