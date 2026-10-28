package com.yk.clash.ui

import android.annotation.SuppressLint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

enum class Glyph { BACK, DASHBOARD, PROXIES, PROFILE, CONNECTIONS, SETTINGS, POWER, SPEED, CHECK, CHEVRON, MORE, REFRESH, PLUS, UPLOAD, DOWNLOAD, MEMORY, SEARCH, CLOSE, SHIELD, ARROW }

@SuppressLint("ViewConstructor")
class GlyphView(context: Context, private val glyph: Glyph, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@GlyphView.color; style = Paint.Style.STROKE; strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        val side = minOf(w, h).toFloat()
        canvas.save(); canvas.translate(paddingLeft + (w - side) / 2, paddingTop + (h - side) / 2); canvas.scale(side / 24, side / 24)
        fun line(x: Float, y: Float, xx: Float, yy: Float) = canvas.drawLine(x, y, xx, yy, paint)
        fun stroke(vararg points: Float) {
            path.reset(); path.moveTo(points[0], points[1]); var i = 2
            while (i < points.size) { path.lineTo(points[i], points[i + 1]); i += 2 }; canvas.drawPath(path, paint)
        }
        when (glyph) {
            Glyph.BACK -> { stroke(11f, 5f, 4f, 12f, 11f, 19f); line(4f, 12f, 20f, 12f) }
            Glyph.DASHBOARD -> {
                canvas.drawRoundRect(3f, 3f, 10f, 12f, 2f, 2f, paint); canvas.drawRoundRect(14f, 3f, 21f, 8f, 2f, 2f, paint)
                canvas.drawRoundRect(3f, 16f, 10f, 21f, 2f, 2f, paint); canvas.drawRoundRect(14f, 12f, 21f, 21f, 2f, 2f, paint)
            }
            Glyph.PROXIES -> { stroke(4f, 7f, 20f, 7f, 16f, 3f); stroke(20f, 17f, 4f, 17f, 8f, 21f) }
            Glyph.PROFILE -> { stroke(14f, 3f, 5f, 3f, 5f, 21f, 19f, 21f, 19f, 8f, 14f, 3f, 14f, 8f, 19f, 8f); line(9f, 12f, 15f, 12f); line(9f, 16f, 15f, 16f) }
            Glyph.CONNECTIONS -> { canvas.drawCircle(5f, 6f, 2f, paint); canvas.drawCircle(19f, 18f, 2f, paint); stroke(7f, 6f, 16f, 6f, 19f, 9f, 19f, 16f); stroke(17f, 18f, 8f, 18f, 5f, 15f, 5f, 8f) }
            Glyph.SETTINGS -> { canvas.drawCircle(12f, 12f, 4f, paint); canvas.drawCircle(12f, 12f, 9f, paint); line(12f, 1f, 12f, 3f); line(12f, 21f, 12f, 23f); line(1f, 12f, 3f, 12f); line(21f, 12f, 23f, 12f) }
            Glyph.POWER -> { canvas.drawArc(4f, 4f, 20f, 20f, -55f, 290f, false, paint); line(12f, 2f, 12f, 11f) }
            Glyph.SPEED -> { canvas.drawArc(3f, 4f, 21f, 22f, 145f, 250f, false, paint); line(12f, 13f, 17f, 8f); canvas.drawCircle(12f, 13f, 1f, paint) }
            Glyph.CHECK -> stroke(5f, 12f, 10f, 17f, 19f, 7f)
            Glyph.CHEVRON -> stroke(9f, 5f, 16f, 12f, 9f, 19f)
            Glyph.MORE -> { canvas.drawCircle(5f, 12f, 1f, paint); canvas.drawCircle(12f, 12f, 1f, paint); canvas.drawCircle(19f, 12f, 1f, paint) }
            Glyph.REFRESH -> { canvas.drawArc(4f, 4f, 20f, 20f, 45f, 285f, false, paint); stroke(20f, 3f, 20f, 9f, 14f, 9f) }
            Glyph.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Glyph.UPLOAD -> { stroke(6f, 10f, 12f, 4f, 18f, 10f); line(12f, 4f, 12f, 19f) }
            Glyph.DOWNLOAD -> { stroke(6f, 14f, 12f, 20f, 18f, 14f); line(12f, 5f, 12f, 20f) }
            Glyph.MEMORY -> { canvas.drawRoundRect(6f, 6f, 18f, 18f, 2f, 2f, paint); canvas.drawRect(9f, 9f, 15f, 15f, paint); for (i in listOf(8f, 12f, 16f)) { line(i, 3f, i, 6f); line(i, 18f, i, 21f); line(3f, i, 6f, i); line(18f, i, 21f, i) } }
            Glyph.SEARCH -> { canvas.drawCircle(10f, 10f, 6f, paint); line(15f, 15f, 21f, 21f) }
            Glyph.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Glyph.SHIELD -> stroke(12f, 2f, 21f, 6f, 20f, 15f, 17f, 19f, 12f, 22f, 7f, 19f, 4f, 15f, 3f, 6f, 12f, 2f)
            Glyph.ARROW -> { line(4f, 12f, 20f, 12f); stroke(14f, 6f, 20f, 12f, 14f, 18f) }
        }
        canvas.restore()
    }
}
