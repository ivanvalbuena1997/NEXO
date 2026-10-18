package com.nexo.vita

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.HealthConnectFeatures
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val es: Locale = Locale.forLanguageTag("es-ES")
    private val fmtHora = DateTimeFormatter.ofPattern("HH:mm")
    private val fmtDia = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", es)

    private lateinit var hud: Hud
    private lateinit var contenido: LinearLayout

    private var sdkStatus: Int = HealthConnectClient.SDK_UNAVAILABLE
    private var client: HealthConnectClient? = null
    private var concedidos: Set<String> = emptySet()
    private var segundoPlanoDisponible: Boolean? = null
    private var dias: List<Dia> = emptyList()
    private var ultimaLectura: String? = null
    private var errorLectura: String? = null
    private val documentosAbiertos = mutableSetOf<LocalDate>()
    private var trabajo: Job? = null

    private val pedirPermisos = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { actualizar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hud = Hud(this)

        contenido = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(hud.dp(16), hud.dp(12), hud.dp(16), hud.dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Hud.BG)
            isFillViewport = true
            addView(contenido)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        // Al volver de Ajustes o de Health Connect, los permisos pueden haber cambiado.
        actualizar()
    }

    private fun actualizar() {
        if (trabajo?.isActive == true) return
        trabajo = lifecycleScope.launch {
            sdkStatus = HealthConnectClient.getSdkStatus(this@MainActivity)
            client = if (sdkStatus == HealthConnectClient.SDK_AVAILABLE)
                HealthConnectClient.getOrCreate(this@MainActivity) else null

            val c = client
            if (c == null) {
                concedidos = emptySet()
                dias = emptyList()
                pintar()
                return@launch
            }

            val lector = LectorHealthConnect(c)
            try {
                concedidos = lector.permisosConcedidos()
                segundoPlanoDisponible = c.features.getFeatureStatus(
                    HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
                ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

                if (concedidos.any { it in LectorHealthConnect.PERMISOS }) {
                    dias = lector.leerDias(7, concedidos)
                    errorLectura = null
                }
                ultimaLectura = LocalTime.now(ZONA_ESPANA).format(fmtHora)
            } catch (e: Exception) {
                errorLectura = "${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}"
                ultimaLectura = LocalTime.now(ZONA_ESPANA).format(fmtHora)
            }
            pintar()
        }
    }

    // ---------------------------------------------------------------- pintado

    private fun pintar() {
        contenido.removeAllViews()
        contenido.addView(cabecera())
        contenido.addView(tarjetaEstado())
        if (client != null && concedidos.any { it in LectorHealthConnect.PERMISOS }) {
            contenido.addView(tarjetaLectura())
            dias.forEach { contenido.addView(tarjetaDia(it)) }
        }
    }

    private fun cabecera(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, hud.dp(4), 0, hud.dp(16))
        addView(hud.texto("NEXO", 22f, Hud.TEXT, hud.orbitron700, 0.16f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(hud.texto("v${BuildConfig.VERSION_NAME}", 13f, Hud.DIM, hud.rajdhani600).apply {
            background = hud.fondo(Hud.BG, Hud.LINE, 999)
            setPadding(hud.dp(10), hud.dp(4), hud.dp(10), hud.dp(4))
        })
    }

    private fun tarjetaEstado(): View = hud.tarjeta().apply {
        addView(hud.etiqueta("Estado"))

        val (txtHc, colHc) = when (sdkStatus) {
            HealthConnectClient.SDK_AVAILABLE -> "Disponible" to Hud.OK
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Requiere actualización" to Hud.WARN
            else -> "No disponible" to Hud.BAD
        }
        addView(hud.fila("Health Connect", txtHc, colHc))

        if (client != null) {
            addView(filaPermiso("Permiso de pasos", LectorHealthConnect.PERMISO_PASOS))
            addView(filaPermiso("Permiso de sueño", LectorHealthConnect.PERMISO_SUENO))
            val (txtBg, colBg) = when (segundoPlanoDisponible) {
                true -> "Disponible" to Hud.OK
                false -> "No disponible" to Hud.WARN
                null -> "—" to Hud.MUTE
            }
            addView(hud.fila("Lectura en segundo plano", txtBg, colBg))
            addView(hud.texto(
                "La lectura en segundo plano se usará a partir de la 0.3, para la sincronización diaria.",
                13f, Hud.MUTE,
            ).apply { setPadding(0, hud.dp(2), 0, 0) })

            if (!concedidos.containsAll(LectorHealthConnect.PERMISOS)) {
                addView(hud.boton("Conceder permisos") {
                    pedirPermisos.launch(LectorHealthConnect.PERMISOS)
                })
            }
            addView(hud.boton("Abrir Health Connect", principal = false) { abrirHealthConnect() })
        }
    }

    private fun filaPermiso(clave: String, permiso: String): View {
        val ok = permiso in concedidos
        return hud.fila(clave, if (ok) "Concedido" else "Sin conceder", if (ok) Hud.OK else Hud.WARN)
    }

    private fun tarjetaLectura(): View = hud.tarjeta().apply {
        addView(hud.etiqueta("Lectura de Health Connect"))
        addView(hud.texto(
            "Solo lectura: NEXO 0.1 no escribe nada en Firestore. Compara estos datos con los de Mi Fitness.",
            14f, Hud.DIM,
        ))
        addView(hud.fila("Última lectura", ultimaLectura ?: "—"))
        errorLectura?.let {
            addView(hud.texto("Error: $it", 14f, Hud.BAD).apply { setPadding(0, hud.dp(6), 0, 0) })
        }
        addView(hud.boton("Leer ahora") { actualizar() })
    }

    private fun tarjetaDia(dia: Dia): View = hud.tarjeta().apply {
        // Cabecera del día
        val hoy = LocalDate.now(ZONA_ESPANA)
        val marca = when (dia.fecha) {
            hoy -> "HOY"
            hoy.minusDays(1) -> "AYER"
            else -> null
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(hud.texto(
                dia.fecha.format(fmtDia).replaceFirstChar { it.titlecase(es) },
                17f, Hud.TEXT, hud.rajdhani700,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (marca != null) addView(hud.etiqueta(marca))
        })

        // Pasos
        addView(hud.separador())
        addView(hud.etiqueta("Pasos", Hud.DIM))
        val total = dia.pasos.total
        if (LectorHealthConnect.PERMISO_PASOS !in concedidos) {
            addView(hud.texto("Sin permiso", 15f, Hud.WARN))
        } else if (total == null) {
            addView(hud.texto("Sin datos en Health Connect (no se escribiría nada).", 15f, Hud.MUTE))
        } else {
            addView(hud.cifra(miles(total)))
            if (dia.pasos.origenesAgregado.isNotEmpty()) {
                addView(hud.texto(
                    "Fuentes del total: " + dia.pasos.origenesAgregado.joinToString(", ") { nombreApp(it) },
                    13f, Hud.DIM,
                ))
            }
        }
        if (dia.pasos.desglose.isNotEmpty()) {
            addView(hud.texto("Registros por app, sin deduplicar:", 13f, Hud.MUTE).apply {
                setPadding(0, hud.dp(6), 0, 0)
            })
            dia.pasos.desglose.entries.sortedByDescending { it.value }.forEach { (pkg, n) ->
                addView(hud.texto("· ${nombreApp(pkg)}: ${miles(n)}", 13f, Hud.MUTE))
            }
        }

        // Sueño
        addView(hud.separador())
        addView(hud.etiqueta("Sueño (noche que termina este día)", Hud.DIM))
        val sueno = dia.sueno
        val principal = sueno?.principal
        if (LectorHealthConnect.PERMISO_SUENO !in concedidos) {
            addView(hud.texto("Sin permiso", 15f, Hud.WARN))
        } else if (sueno == null || principal == null) {
            addView(hud.texto("Sin datos en Health Connect (no se escribiría nada).", 15f, Hud.MUTE))
        } else {
            addView(hud.cifra(duracion(sueno.min)))
            addView(hud.texto(
                "${principal.inicio.format(fmtHora)} → ${principal.fin.format(fmtHora)}" +
                    if (sueno.sesiones.size > 1) "  ·  ${sueno.sesiones.size} sesiones" else "",
                15f, Hud.TEXT, hud.rajdhani600,
            ))
            val f = sueno.fases
            if (f != null) {
                addView(hud.fila("Profundo", "${f.profundo} min"))
                addView(hud.fila("Ligero", "${f.ligero} min"))
                addView(hud.fila("REM", "${f.rem} min"))
                addView(hud.fila("Despierto", "${f.despierto} min"))
            } else {
                addView(hud.texto("Sin fases: Health Connect solo trae la sesión.", 14f, Hud.WARN))
            }
            addView(hud.texto("Sesiones que cuentan:", 13f, Hud.MUTE).apply { setPadding(0, hud.dp(6), 0, 0) })
            sueno.sesiones.forEach { addView(hud.texto(lineaSesion(it), 13f, Hud.MUTE)) }
            if (sueno.descartadas.isNotEmpty()) {
                addView(hud.texto(
                    "Descartadas por solaparse con una versión más reciente:",
                    13f, Hud.WARN,
                ).apply { setPadding(0, hud.dp(6), 0, 0) })
                sueno.descartadas.forEach { addView(hud.texto(lineaSesion(it), 13f, Hud.MUTE)) }
            }
        }

        // Documento que se escribiría (0.2)
        val abierto = dia.fecha in documentosAbiertos
        addView(hud.boton(if (abierto) "Ocultar documento" else "Ver documento para V.I.T.A.", principal = false) {
            if (!documentosAbiertos.remove(dia.fecha)) documentosAbiertos.add(dia.fecha)
            pintar()
        })
        if (abierto) {
            addView(hud.mono(Contrato.documento(dia).toString(2)).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = hud.dp(10) }
            })
        }
    }

    // ---------------------------------------------------------------- utilidades

    private fun abrirHealthConnect() {
        try {
            startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No se pudo abrir Health Connect", Toast.LENGTH_LONG).show()
        }
    }

    private val nombres = mutableMapOf<String, String>()

    /** Nombre visible de la app que escribió el dato, con su paquete. */
    private fun nombreApp(pkg: String): String = nombres.getOrPut(pkg) {
        val etiqueta = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        if (etiqueta != null && etiqueta != pkg) "$etiqueta ($pkg)" else pkg
    }

    private val fmtModif = DateTimeFormatter.ofPattern("d/M HH:mm")

    /** Una línea de diagnóstico por sesión: horario, fases propias, app y modificación. */
    private fun lineaSesion(s: SesionSueno): String {
        val detalle = if (s.conFases) buildString {
            append("profundo ${aMinutos(s.segundosDe(Fase.PROFUNDO))}")
            append(" · ligero ${aMinutos(s.segundosDe(Fase.LIGERO))}")
            append(" · REM ${aMinutos(s.segundosDe(Fase.REM))}")
            append(" · despierto ${aMinutos(s.segundosDe(Fase.DESPIERTO))}")
            val sinDetalle = aMinutos(s.segundosDe(Fase.DORMIDO_SIN_DETALLE))
            val desconocido = aMinutos(s.segundosDe(Fase.DESCONOCIDO))
            if (sinDetalle > 0) append(" · sin detalle $sinDetalle")
            if (desconocido > 0) append(" · desconocido $desconocido")
            append(" min (${s.tramos.size} tramos)")
        } else "sin fases"
        val modif = s.modificado.atZone(ZONA_ESPANA).format(fmtModif)
        return "· ${s.inicio.format(fmtHora)}–${s.fin.format(fmtHora)} " +
            "(${duracion(aMinutos(s.duracionSeg))}) · $detalle · ${nombreApp(s.origen)} · modificada $modif"
    }

    private fun miles(n: Long): String = String.format(es, "%,d", n)

    private fun duracion(min: Long): String = "${min / 60} h ${min % 60} min"
}
