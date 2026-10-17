package com.nexo.vita

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/** Estilo HUD de V.I.T.A. (mismos colores que css/vita.css, tema oscuro). */
class Hud(private val ctx: Context) {

    companion object {
        val BG = Color.parseColor("#05080C")
        val SURF = Color.parseColor("#0B141B")
        val SURF2 = Color.parseColor("#0F1C25")
        val LINE = Color.parseColor("#1C2E38")
        val TEXT = Color.parseColor("#E6F7FB")
        val DIM = Color.parseColor("#9DBDC7")
        val MUTE = Color.parseColor("#6F8F9B")
        val ACC = Color.parseColor("#3FE0FF")
        val ACC_SOFT = Color.argb(33, 63, 224, 255)
        val ON_ACC = Color.parseColor("#03161C")
        val OK = Color.parseColor("#6EE7A8")
        val WARN = Color.parseColor("#FFB547")
        val BAD = Color.parseColor("#FF6B6B")
    }

    val orbitron500: Typeface? = ResourcesCompat.getFont(ctx, R.font.orbitron_500)
    val orbitron700: Typeface? = ResourcesCompat.getFont(ctx, R.font.orbitron_700)
    val rajdhani500: Typeface? = ResourcesCompat.getFont(ctx, R.font.rajdhani_500)
    val rajdhani600: Typeface? = ResourcesCompat.getFont(ctx, R.font.rajdhani_600)
    val rajdhani700: Typeface? = ResourcesCompat.getFont(ctx, R.font.rajdhani_700)

    fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    fun fondo(color: Int, borde: Int, radioDp: Int = 16): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radioDp).toFloat()
            setStroke(dp(1), borde)
        }

    fun texto(
        s: CharSequence,
        tam: Float = 16f,
        color: Int = TEXT,
        tipo: Typeface? = rajdhani500,
        espaciado: Float = 0f,
    ): TextView = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, tam)
        typeface = tipo
        letterSpacing = espaciado
        setLineSpacing(0f, 1.15f)
    }

    /** Etiqueta pequeña en mayúsculas, como los títulos de tarjeta de V.I.T.A. */
    fun etiqueta(s: String, color: Int = ACC): TextView =
        texto(s.uppercase(), 12f, color, rajdhani700, 0.12f)

    /** Cifra grande en Orbitron con resplandor cian. */
    fun cifra(s: String, tam: Float = 30f, color: Int = ACC): TextView =
        texto(s, tam, color, orbitron700).apply {
            setShadowLayer(dp(10).toFloat(), 0f, 0f, Color.argb(140, 63, 224, 255))
        }

    fun mono(s: String): TextView =
        texto(s, 12f, DIM, Typeface.MONOSPACE).apply {
            setTextIsSelectable(true)
            background = fondo(BG, LINE, 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

    fun tarjeta(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = fondo(SURF, LINE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) }
    }

    /** Botón principal (relleno cian) o secundario (contorno). */
    fun boton(s: String, principal: Boolean = true, alPulsar: () -> Unit): TextView =
        texto(s.uppercase(), 14f, if (principal) ON_ACC else ACC, rajdhani700, 0.1f).apply {
            gravity = Gravity.CENTER
            minHeight = dp(46)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = if (principal) fondo(ACC, ACC, 12) else fondo(ACC_SOFT, ACC, 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { alPulsar() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }

    fun separador(): View = View(ctx).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            .apply { topMargin = dp(10); bottomMargin = dp(10) }
    }

    /** Fila «etiqueta ······ valor». */
    fun fila(clave: String, valor: CharSequence, colorValor: Int = TEXT): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(texto(clave, 15f, DIM).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(texto(valor, 15f, colorValor, rajdhani700).apply { gravity = Gravity.END })
        }
}
