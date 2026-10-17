package com.nexo.vita

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Pantalla que Health Connect abre desde «Política de privacidad» / uso de permisos. */
class PermissionsRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hud = Hud(this)

        val contenido = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(hud.dp(16), hud.dp(16), hud.dp(16), hud.dp(32))
            addView(hud.texto("NEXO", 22f, Hud.TEXT, hud.orbitron700, 0.16f).apply {
                setPadding(0, 0, 0, hud.dp(16))
            })
            addView(hud.tarjeta().apply {
                addView(hud.etiqueta("Uso de tus datos de salud"))
                addView(hud.texto(
                    "NEXO lee de Health Connect tus pasos y tu sueño (con sus fases) para llevarlos " +
                        "a tu cuenta de V.I.T.A. No lee ningún otro dato, no escribe en Health Connect " +
                        "y no comparte nada con terceros.\n\n" +
                        "En la versión 0.1 los datos solo se muestran en pantalla. A partir de la 0.2 se " +
                        "guardarán en tu base de datos de Firebase (proyecto de V.I.T.A.), accesible solo " +
                        "con tu cuenta de Google.\n\n" +
                        "Puedes retirar los permisos cuando quieras en Health Connect.",
                    15f, Hud.DIM,
                ))
                addView(hud.boton("Entendido") { finish() })
            })
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Hud.BG)
            addView(contenido)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(scroll)
    }
}
