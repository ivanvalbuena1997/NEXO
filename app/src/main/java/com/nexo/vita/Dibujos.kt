package com.nexo.vita

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Tarjeta HUD: fondo redondeado con borde fino y esquinas de marco en cian, como V.I.T.A. */
class MarcoHud(
    private val dp: Float,
    private val fondo: Int = Hud.SURF,
    private val borde: Int = Hud.LINE,
    private val esquina: Int = Hud.ACC,
) : Drawable() {
    private val pFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fondo }
    private val pBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp; color = borde }
    private val pEsquina = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = esquina; strokeCap = Paint.Cap.SQUARE
    }
    private val r = RectF()
    private val camino = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        val m = dp / 2
        r.set(b.left + m, b.top + m, b.right - m, b.bottom - m)
        val radio = 14 * dp
        c.drawRoundRect(r, radio, radio, pFondo)
        c.drawRoundRect(r, radio, radio, pBorde)
        // Esquinas: dos «L» (arriba izquierda y abajo derecha), separadas del borde.
        val l = 16 * dp
        val i = 5 * dp
        camino.reset()
        camino.moveTo(r.left + i, r.top + i + l); camino.lineTo(r.left + i, r.top + i); camino.lineTo(r.left + i + l, r.top + i)
        camino.moveTo(r.right - i, r.bottom - i - l); camino.lineTo(r.right - i, r.bottom - i); camino.lineTo(r.right - i - l, r.bottom - i)
        pEsquina.alpha = 200
        c.drawPath(camino, pEsquina)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Fondo de pantalla: negro HUD con una rejilla cian muy tenue. */
class RejillaHud(private val dp: Float) : Drawable() {
    private val pLinea = Paint().apply { color = Hud.ACC; alpha = 14; strokeWidth = dp }

    override fun draw(c: Canvas) {
        val b = bounds
        c.drawColor(Hud.BG)
        val paso = 28 * dp
        var x = b.left.toFloat()
        while (x < b.right) { c.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), pLinea); x += paso }
        var y = b.top.toFloat()
        while (y < b.bottom) { c.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, pLinea); y += paso }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
