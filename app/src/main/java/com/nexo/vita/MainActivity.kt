package com.nexo.vita

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.nexo.vita.drive.CopiaVita
import com.nexo.vita.drive.DriveApi
import com.nexo.vita.drive.Json
import com.nexo.vita.drive.PermisoDrive
import com.nexo.vita.drive.PruebaDriveWorker
import com.nexo.vita.drive.Respaldo
import com.nexo.vita.drive.SubidaCopia
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
    private lateinit var scroll: ScrollView
    private lateinit var cabeceraFija: LinearLayout
    private lateinit var barra: LinearLayout

    enum class Pestana(val titulo: String) { ESTADO("Estado"), AJUSTES("Ajustes"), DIAGNOSTICO("Diagnóstico") }
    private var pestana = Pestana.ESTADO

    private var sdkStatus: Int = HealthConnectClient.SDK_UNAVAILABLE
    private var client: HealthConnectClient? = null
    private var concedidos: Set<String> = emptySet()
    private var segundoPlanoDisponible: Boolean? = null
    private var dias: List<Dia> = emptyList()
    private var ultimaLectura: String? = null
    private var errorLectura: String? = null
    private val documentosAbiertos = mutableSetOf<LocalDate>()
    private var trabajo: Job? = null

    private lateinit var cuenta: Cuenta
    private var mensajeCuenta: String? = null
    private var sincronizando = false
    private var ultimaSync: Sincronizador.Resultado? = null
    private val fmtSync = DateTimeFormatter.ofPattern("EEE d/M 'a las' HH:mm", es)

    // ---- Copia en Drive (0.4): diagnóstico de solo lectura
    private var lecturaFs: Respaldo.Lectura? = null
    private var tamanoCopia: Int? = null
    private var estadoDrive: Respaldo.EstadoDrive? = null
    private var errorDrive: String? = null
    private var ocupadoCopia: String? = null

    private var ultimaCopia: SubidaCopia.Resultado? = null
    /** Qué hacer al volver de la pantalla de permiso de Google (true = concedido). */
    private var trasPermisoDrive: ((Boolean) -> Unit)? = null

    private val pedirPermisoDrive = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r ->
        val seguir = trasPermisoDrive
        trasPermisoDrive = null
        seguir?.invoke(r.resultCode == RESULT_OK)
    }

    private val pedirPermisos = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { actualizar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hud = Hud(this)
        cuenta = Cuenta(this)
        ultimaSync = Sincronizador.ultimo(this)

        pestana = savedInstanceState?.getString("pestana")?.let { runCatching { Pestana.valueOf(it) }.getOrNull() }
            ?: Pestana.ESTADO

        cabeceraFija = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(hud.dp(16), hud.dp(10), hud.dp(16), hud.dp(10))
        }
        contenido = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(hud.dp(16), hud.dp(4), hud.dp(16), hud.dp(24))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(contenido)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Hud.SURF)
        }
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = RejillaHud(hud.dp(1).coerceAtLeast(1).toFloat())
            addView(cabeceraFija)
            addView(View(context).apply {
                setBackgroundColor(Hud.LINE)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hud.dp(1))
            })
            addView(scroll)
            addView(View(context).apply {
                setBackgroundColor(Hud.LINE)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hud.dp(1))
            })
            addView(barra)
        }
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(raiz)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pestana", pestana.name)
    }

    override fun onResume() {
        super.onResume()
        ultimaSync = Sincronizador.ultimo(this) // puede haberla hecho la sincronización automática
        ultimaCopia = SubidaCopia.ultimo(this)
        if (Programador.activa(this)) Programador.reprogramar(this)
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
        val y = scroll.scrollY
        pintarCabecera()
        pintarBarra()
        contenido.removeAllViews()
        when (pestana) {
            Pestana.ESTADO -> {
                contenido.addView(tarjetaResumen())
                contenido.addView(tarjetaHistorial())
            }
            Pestana.AJUSTES -> {
                contenido.addView(tarjetaCuenta())
                contenido.addView(tarjetaEstado())
                if (client != null && cuenta.usuario != null) contenido.addView(tarjetaProgramacion())
                contenido.addView(tarjetaBateria())
            }
            Pestana.DIAGNOSTICO -> {
                if (cuenta.usuario != null) contenido.addView(tarjetaDiagnosticoCopia())
                if (client != null && concedidos.any { it in LectorHealthConnect.PERMISOS }) {
                    contenido.addView(tarjetaLectura())
                    dias.forEach { contenido.addView(tarjetaDia(it)) }
                }
            }
        }
        scroll.post { scroll.scrollTo(0, y) }
    }

    // ---------------------------------------------------------------- estado global

    enum class Nivel(val texto: String, val color: Int) {
        BIEN("En orden", Hud.OK), REVISAR("Revisar", Hud.WARN), FALLO("Atención", Hud.BAD)
    }

    /** Estado global y motivos (los fallos primero). */
    private fun estadoGlobal(): Pair<Nivel, List<Pair<Nivel, String>>> {
        val m = mutableListOf<Pair<Nivel, String>>()
        val ahora = java.time.ZonedDateTime.now(ZONA_ESPANA)
        if (cuenta.usuario == null) m += Nivel.FALLO to "Sin iniciar sesión con Google."
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) m += Nivel.FALLO to "Health Connect no disponible."
        else {
            if (!concedidos.containsAll(LectorHealthConnect.PERMISOS)) m += Nivel.FALLO to "Faltan permisos de pasos o sueño."
            if (segundoPlanoDisponible == true && LectorHealthConnect.PERMISO_SEGUNDO_PLANO !in concedidos)
                m += Nivel.FALLO to "Falta el permiso de lectura en segundo plano."
        }
        if (ultimaSync?.ok == false) m += Nivel.FALLO to "La última sincronización falló."
        if (SyncWorker.copiaDiariaActiva(this) && ultimaCopia?.ok == false) m += Nivel.FALLO to "La última copia en Drive falló."

        val programada = Programador.activa(this)
        if (!programada) m += Nivel.REVISAR to "La sincronización diaria está desactivada."
        else {
            if (!Programador.puedeExacta(this)) m += Nivel.REVISAR to "Sin «Alarmas y recordatorios»: puede retrasarse."
            val ultimaAuto = Historial.leer(this).firstOrNull()?.cuando
            val desde = ultimaAuto ?: ultimaSync?.cuando
            if (desde != null && java.time.Duration.between(desde, ahora).toHours() >= 36)
                m += Nivel.REVISAR to "Más de 36 h sin ejecución automática."
        }
        if (!ignoraOptimizacionBateria()) m += Nivel.REVISAR to "Optimización de batería activa para NEXO."

        val nivel = when {
            m.any { it.first == Nivel.FALLO } -> Nivel.FALLO
            m.isNotEmpty() -> Nivel.REVISAR
            else -> Nivel.BIEN
        }
        return nivel to m.sortedBy { it.first != Nivel.FALLO }
    }

    private var pulso: ObjectAnimator? = null

    private fun pintarCabecera() {
        cabeceraFija.removeAllViews()
        cabeceraFija.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(hud.dp(40), hud.dp(40)).apply { marginEnd = hud.dp(6) }
        })
        cabeceraFija.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(hud.texto("NEXO", 20f, Hud.TEXT, hud.orbitron700, 0.18f).apply {
                setShadowLayer(hud.dp(8).toFloat(), 0f, 0f, android.graphics.Color.argb(120, 63, 224, 255))
            })
            addView(hud.texto("ENLACE V.I.T.A. · v${BuildConfig.VERSION_NAME}", 11f, Hud.MUTE, hud.rajdhani600, 0.12f))
        })
        val (nivel, _) = estadoGlobal()
        val punto = View(this).apply {
            background = hud.fondo(nivel.color, nivel.color, 999)
            layoutParams = LinearLayout.LayoutParams(hud.dp(8), hud.dp(8)).apply { marginEnd = hud.dp(6) }
        }
        cabeceraFija.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = hud.fondo(Hud.BG, nivel.color, 999)
            setPadding(hud.dp(10), hud.dp(4), hud.dp(10), hud.dp(4))
            isClickable = true
            setOnClickListener { pestana = Pestana.ESTADO; pintar() }
            addView(punto)
            addView(hud.texto(nivel.texto.uppercase(), 11f, nivel.color, hud.rajdhani700, 0.12f))
        })
        pulso?.cancel()
        pulso = ObjectAnimator.ofFloat(punto, View.ALPHA, 1f, 0.25f).apply {
            duration = 1200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun pintarBarra() {
        barra.removeAllViews()
        Pestana.values().forEach { p ->
            val activa = p == pestana
            barra.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, hud.dp(56), 1f)
                isClickable = true
                setOnClickListener { if (pestana != p) { pestana = p; scroll.scrollTo(0, 0); pintar(); scroll.scrollTo(0, 0) } }
                addView(View(context).apply {
                    setBackgroundColor(if (activa) Hud.ACC else android.graphics.Color.TRANSPARENT)
                    layoutParams = LinearLayout.LayoutParams(hud.dp(36), hud.dp(2))
                })
                addView(hud.texto(p.titulo.uppercase(), 11f, if (activa) Hud.ACC else Hud.MUTE, hud.orbitron500, 0.1f).apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
                    )
                    if (activa) setShadowLayer(hud.dp(6).toFloat(), 0f, 0f, android.graphics.Color.argb(150, 63, 224, 255))
                })
            })
        }
    }

    // ---------------------------------------------------------------- pestaña Estado

    private fun fecha(z: java.time.ZonedDateTime) = z.format(fmtSync).replaceFirstChar { it.titlecase(es) }

    private fun tarjetaResumen(): View = hud.tarjeta().apply {
        val (nivel, motivos) = estadoGlobal()
        addView(hud.etiqueta("Estado"))
        addView(hud.texto(nivel.texto.uppercase(), 26f, nivel.color, hud.orbitron700, 0.08f).apply {
            setShadowLayer(hud.dp(10).toFloat(), 0f, 0f, nivel.color)
            setPadding(0, hud.dp(4), 0, hud.dp(4))
        })
        if (motivos.isEmpty()) addView(hud.texto("Todo listo: NEXO sincroniza y guarda la copia cada día.", 14f, Hud.DIM))
        motivos.forEach { (n, t) -> addView(hud.texto("· $t", 14f, n.color)) }
        if (motivos.isNotEmpty()) addView(hud.boton("Revisar ajustes", principal = false) { pestana = Pestana.AJUSTES; pintar() })

        addView(hud.separador())
        val s = ultimaSync
        addView(hud.fila("Última sincronización",
            s?.let { fecha(it.cuando) + " (${it.origen.texto})" } ?: "Nunca",
            when (s?.ok) { true -> Hud.OK; false -> Hud.BAD; null -> Hud.MUTE }))
        if (s != null && !s.ok) addView(hud.texto(s.texto, 13f, Hud.BAD))
        val c = ultimaCopia
        addView(hud.fila("Última copia en Drive",
            c?.let { fecha(it.cuando) + " (${it.origen.texto})" } ?: "Nunca",
            when (c?.ok) { true -> Hud.OK; false -> Hud.BAD; null -> Hud.MUTE }))
        if (c != null && !c.ok) addView(hud.texto(c.texto, 13f, Hud.BAD))
        addView(hud.fila("Próxima ejecución",
            if (Programador.activa(this@MainActivity)) Programador.proxima(this@MainActivity)?.let { fecha(it) } ?: "—"
            else "Desactivada",
            if (Programador.activa(this@MainActivity)) Hud.TEXT else Hud.WARN))

        if (cuenta.usuario != null) {
            val listo = client != null && concedidos.containsAll(LectorHealthConnect.PERMISOS)
            addView(hud.boton(if (sincronizando) "Sincronizando…" else "Sincronizar ahora") {
                if (!sincronizando && listo) sincronizar()
            }.apply { alpha = if (listo && !sincronizando) 1f else 0.5f })
            addView(hud.boton(if (ocupadoCopia == "subir") "Subiendo…" else "Subir copia ahora", principal = false) {
                if (ocupadoCopia == null) subirCopiaAhora(reintento = false)
            })
        } else {
            addView(hud.boton("Iniciar sesión con Google") { iniciarSesion() })
        }
    }

    private fun tarjetaHistorial(): View = hud.tarjeta().apply {
        addView(hud.etiqueta("Historial automático"))
        val h = Historial.leer(this@MainActivity)
        if (h.isEmpty()) {
            addView(hud.texto("Aún no hay ejecuciones automáticas registradas (se guardan desde la 0.5).", 14f, Hud.MUTE))
            return@apply
        }
        fun marca(p: Historial.Paso) = when (p) { Historial.Paso.OK -> "✓"; Historial.Paso.FALLO -> "✗"; Historial.Paso.OMITIDA -> "–" }
        fun color(p: Historial.Paso) = when (p) { Historial.Paso.OK -> Hud.OK; Historial.Paso.FALLO -> Hud.BAD; Historial.Paso.OMITIDA -> Hud.MUTE }
        h.forEach { e ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, hud.dp(4), 0, hud.dp(2))
                addView(hud.texto(fecha(e.cuando) + if (e.intento > 0) " · reintento ${e.intento}" else "", 14f, Hud.DIM).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(hud.texto("SYNC ${marca(e.sync)}", 12f, color(e.sync), hud.rajdhani700, 0.08f).apply {
                    setPadding(hud.dp(8), 0, hud.dp(8), 0)
                })
                addView(hud.texto("COPIA ${marca(e.copia)}", 12f, color(e.copia), hud.rajdhani700, 0.08f))
            })
            if (e.sync == Historial.Paso.FALLO) addView(hud.texto("  ${e.syncTexto}", 12f, Hud.BAD))
            if (e.copia == Historial.Paso.FALLO) addView(hud.texto("  ${e.copiaTexto}", 12f, Hud.BAD))
        }
    }

    // ---------------------------------------------------------------- pestaña Ajustes

    private fun tarjetaCuenta(): View = hud.tarjeta().apply {
        addView(hud.etiqueta("Cuenta de V.I.T.A."))
        val usuario = cuenta.usuario
        addView(hud.fila("Cuenta", usuario?.email ?: "Sin iniciar sesión", if (usuario != null) Hud.OK else Hud.WARN))
        mensajeCuenta?.let { addView(hud.texto(it, 14f, Hud.BAD)) }
        if (usuario == null) addView(hud.boton("Iniciar sesión con Google") { iniciarSesion() })
        else addView(hud.boton("Cerrar sesión", principal = false) { cerrarSesion() })
    }

    private fun ignoraOptimizacionBateria(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun hecho(clave: String) = getSharedPreferences("nexo", MODE_PRIVATE).getBoolean(clave, false)
    private fun marcar(clave: String, v: Boolean) =
        getSharedPreferences("nexo", MODE_PRIVATE).edit().putBoolean(clave, v).apply()

    private fun tarjetaBateria(): View = hud.tarjeta().apply {
        addView(hud.etiqueta("Batería y segundo plano (HyperOS)"))
        addView(hud.texto(
            "Para que la tarea diaria salte con el móvil en reposo. HyperOS no deja comprobar los dos últimos " +
                "puntos: márcalos tú cuando estén hechos.",
            13f, Hud.MUTE,
        ))

        val opt = ignoraOptimizacionBateria()
        addView(hud.separador())
        addView(hud.fila("1 · Optimización de batería de Android", if (opt) "Desactivada" else "Activa", if (opt) Hud.OK else Hud.WARN))
        if (!opt) addView(hud.boton("Quitar optimización", principal = false) {
            abrir(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })

        addView(hud.separador())
        val ahorro = hecho("guia_ahorro")
        addView(hud.fila("2 · Ahorro de batería «Sin restricciones»", if (ahorro) "Hecho" else "Pendiente", if (ahorro) Hud.OK else Hud.WARN))
        val auto = hecho("guia_inicio")
        addView(hud.fila("3 · Inicio automático", if (auto) "Hecho" else "Pendiente", if (auto) Hud.OK else Hud.WARN))
        addView(hud.texto("Los dos están en la ficha de NEXO (Ajustes → Aplicaciones → NEXO).", 13f, Hud.MUTE))
        addView(hud.boton("Abrir la ficha de NEXO", principal = false) {
            abrir(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        addView(hud.boton(if (ahorro) "Desmarcar el punto 2" else "Marcar el punto 2 como hecho", principal = false) {
            marcar("guia_ahorro", !ahorro); pintar()
        })
        addView(hud.boton(if (auto) "Desmarcar el punto 3" else "Marcar el punto 3 como hecho", principal = false) {
            marcar("guia_inicio", !auto); pintar()
        })

        addView(hud.separador())
        val bloq = hecho("guia_bloqueo")
        addView(hud.fila("4 · Bloquear NEXO en recientes", if (bloq) "Hecho" else "Opcional", if (bloq) Hud.OK else Hud.MUTE))
        addView(hud.texto(
            "En la vista de apps recientes, mantén pulsada la tarjeta de NEXO y toca el candado. " +
                "Así «limpiar todo» no la cierra.",
            13f, Hud.MUTE,
        ))
        addView(hud.boton(if (bloq) "Desmarcar el punto 4" else "Marcar el punto 4 como hecho", principal = false) {
            marcar("guia_bloqueo", !bloq); pintar()
        })
    }

    private fun tarjetaEstado(): View = hud.tarjeta().apply {
        addView(hud.etiqueta("Health Connect"))

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
            addView(hud.fila("Lectura en segundo plano (móvil)", txtBg, colBg))
            if (segundoPlanoDisponible == true) {
                addView(filaPermiso("Permiso de segundo plano", LectorHealthConnect.PERMISO_SEGUNDO_PLANO))
            }

            if (!concedidos.containsAll(permisosAPedir)) {
                addView(hud.boton("Conceder permisos") { pedirPermisos.launch(permisosAPedir) })
            }
            addView(hud.boton("Abrir Health Connect", principal = false) { abrirHealthConnect() })
        }
    }

    private fun hhmm(h: java.time.LocalTime) = String.format(es, "%02d:%02d", h.hour, h.minute)

    private fun tarjetaProgramacion(): View = hud.tarjeta().apply {
        addView(hud.etiqueta("Sincronización automática"))
        val activa = Programador.activa(this@MainActivity)
        val horas = Programador.horas(this@MainActivity)
        addView(hud.fila("Estado", if (activa) "Activada" else "Desactivada", if (activa) Hud.OK else Hud.MUTE))
        if (activa) {
            Programador.proxima(this@MainActivity)?.let {
                addView(hud.fila("Próxima", it.format(fmtSync).replaceFirstChar { c -> c.titlecase(es) }))
            }
        }

        // Horas (hasta 6)
        addView(hud.separador())
        addView(hud.etiqueta("Horas · ${horas.size} de ${Programador.MAX_HORAS}", Hud.DIM))
        horas.forEach { h ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, hud.dp(4), 0, hud.dp(4))
                addView(hud.texto(hhmm(h), 20f, Hud.TEXT, hud.orbitron500, 0.06f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (h == horas.last()) addView(hud.pildora("copia", Hud.ACC).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = hud.dp(10) }
                })
                if (horas.size > 1) addView(hud.texto("QUITAR", 12f, Hud.BAD, hud.rajdhani700, 0.1f).apply {
                    setPadding(hud.dp(10), hud.dp(8), hud.dp(4), hud.dp(8))
                    isClickable = true
                    setOnClickListener {
                        Programador.guardarHoras(this@MainActivity, horas - h); pintar()
                    }
                })
            })
        }
        if (horas.size < Programador.MAX_HORAS) {
            addView(hud.boton("Añadir hora", principal = false) { anadirHora(horas) })
        }

        addView(hud.boton(if (activa) "Desactivar" else "Activar") {
            Programador.activar(this@MainActivity, !activa)
            pintar()
        })

        // Permisos necesarios
        addView(hud.separador())
        val exacta = Programador.puedeExacta(this@MainActivity)
        addView(hud.fila(
            "Alarmas y recordatorios",
            if (exacta) "Permitido" else "No permitido",
            if (exacta) Hud.OK else Hud.WARN,
        ))
        val bg = LectorHealthConnect.PERMISO_SEGUNDO_PLANO in concedidos
        addView(hud.fila("Lectura en segundo plano", if (bg) "Concedida" else "Sin conceder", if (bg) Hud.OK else Hud.WARN))
        val avisos = buildList {
            if (!exacta) add("Sin «Alarmas y recordatorios» las pasadas pueden retrasarse.")
            if (!bg) add("Sin el permiso de segundo plano, la sincronización automática fallará.")
        }
        avisos.forEach { addView(hud.texto(it, 13f, Hud.WARN)) }
        if (!exacta && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addView(hud.boton("Permitir alarmas y recordatorios", principal = false) {
                abrir(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            })
        }

        // Copia
        addView(hud.separador())
        val diaria = SyncWorker.copiaDiariaActiva(this@MainActivity)
        addView(hud.fila(
            "Copia en Drive",
            if (diaria) "En la pasada de las ${hhmm(horas.last())}" else "Desactivada",
            if (diaria) Hud.OK else Hud.MUTE,
        ))
        addView(hud.boton(if (diaria) "Desactivar copia diaria" else "Activar copia diaria", principal = false) {
            SyncWorker.activarCopiaDiaria(this@MainActivity, !diaria); pintar()
        })
        addView(hud.texto(
            "En cada hora escribe ayer y hoy en V.I.T.A. (sin tocar lo corregido a mano). La copia en la " +
                "carpeta «V.I.T.A.» de Drive se sube una vez al día, en la última pasada; si un día falla, " +
                "la sube la siguiente pasada. Deja las 8 copias fechadas más recientes.",
            13f, Hud.MUTE,
        ).apply { setPadding(0, hud.dp(6), 0, 0) })
    }

    private fun anadirHora(horas: List<java.time.LocalTime>) {
        val base = horas.lastOrNull()?.plusHours(1) ?: java.time.LocalTime.of(12, 0)
        TimePickerDialog(this, { _, h, m ->
            val nueva = java.time.LocalTime.of(h, m)
            if (nueva in horas) Toast.makeText(this, "Esa hora ya está en la lista", Toast.LENGTH_SHORT).show()
            else Programador.guardarHoras(this, horas + nueva)
            pintar()
        }, base.hour, base.minute, true).show()
    }

    private fun abrir(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No se pudo abrir el ajuste", Toast.LENGTH_LONG).show()
        }
    }

    private fun tarjetaDiagnosticoCopia(): View = hud.tarjeta().apply {
        addView(hud.etiqueta("Copia en Drive · comprobaciones"))
        addView(hud.texto("Solo leen: no suben ni cambian nada.", 13f, Hud.MUTE))
        ultimaCopia?.let { addView(hud.texto("Última copia: ${it.texto}", 13f, if (it.ok) Hud.DIM else Hud.BAD)) }
        addView(hud.separador())
        addView(hud.etiqueta("Firestore", Hud.DIM))
        val l = lecturaFs
        if (l == null) {
            addView(hud.texto("Sin leer.", 14f, Hud.MUTE))
        } else {
            addView(hud.fila("ajustes/principal", if (l.ajustes != null) "Sí" else "No", if (l.ajustes != null) Hud.OK else Hud.BAD))
            addView(hud.fila("compra/actual", if (l.compra != null) "Sí" else "No", Hud.TEXT))
            CopiaVita.COLECCIONES.forEach { c ->
                val err = l.errores[c]
                if (err != null) addView(hud.fila(c, "Error", Hud.BAD))
                else addView(hud.fila(c, "${l.colecciones[c]?.size ?: 0} documentos"))
            }
            l.errores.forEach { (c, m) -> addView(hud.texto("$c: $m", 13f, Hud.BAD)) }
            addView(hud.fila(
                "Tipos especiales de Firestore",
                if (l.especiales.isEmpty()) "Ninguno" else "${l.especiales.size}",
                if (l.especiales.isEmpty()) Hud.OK else Hud.WARN,
            ))
            l.especiales.take(5).forEach { addView(hud.texto("· $it", 12f, Hud.WARN)) }
            tamanoCopia?.let { addView(hud.fila("Tamaño de la copia", String.format(es, "%,d KB", (it + 512) / 1024))) }
        }
        addView(hud.boton(if (ocupadoCopia == "fs") "Leyendo…" else "Leer V.I.T.A. (Firestore)", principal = false) {
            if (ocupadoCopia == null) leerFirestoreCopia()
        })

        // Drive
        addView(hud.separador())
        addView(hud.etiqueta("Google Drive", Hud.DIM))
        val d = estadoDrive
        errorDrive?.let { addView(hud.texto(it, 14f, Hud.BAD)) }
        if (d != null) {
            addView(hud.fila("Carpeta", "«${d.carpeta?.nombre}» visible", Hud.OK))
            addView(hud.fila("Archivos en la carpeta", "${d.archivos.size}"))
            addView(hud.fila(CopiaVita.ULTIMA, if (d.ultima != null) "Encontrada" else "No está",
                if (d.ultima != null) Hud.OK else Hud.WARN))
            d.versionUltima?.let { addView(hud.fila("Hecha por", it)) }
            d.exportadoUltima?.let { addView(hud.fila("Exportada", it.take(16).replace('T', ' ') + " UTC")) }
            addView(hud.fila("«documentacion»", if (d.documentacion != null) "Se puede copiar" else "No disponible",
                if (d.documentacion != null) Hud.OK else Hud.WARN))
            addView(hud.fila("Copias fechadas", "${d.fechadas.size}"))
            d.avisoUltima?.let { addView(hud.texto(it, 13f, Hud.WARN)) }
        } else if (errorDrive == null) {
            addView(hud.texto("Sin comprobar.", 14f, Hud.MUTE))
        }
        addView(hud.boton(if (ocupadoCopia == "drive") "Comprobando…" else "Comprobar Drive", principal = false) {
            if (ocupadoCopia == null) comprobarDrive(reintento = false)
        })

        // Permiso en segundo plano
        addView(hud.separador())
        addView(hud.etiqueta("Permiso en segundo plano", Hud.DIM))
        val prueba = PruebaDriveWorker.ultimo(this@MainActivity)
        if (prueba == null) addView(hud.texto("Sin probar.", 14f, Hud.MUTE))
        else {
            val (cuando, texto) = prueba
            addView(hud.texto(cuando.take(16).replace('T', ' '), 13f, Hud.MUTE))
            addView(hud.texto(texto, 14f, if (texto.startsWith("Token")) Hud.OK else Hud.DIM))
        }
        addView(hud.boton("Probar en segundo plano", principal = false) {
            PruebaDriveWorker.encolar(this@MainActivity); pintar()
        })
        addView(hud.texto(
            "Concede antes el permiso con «Comprobar Drive». Después pulsa este botón, cierra NEXO " +
                "(también desde recientes) y vuelve a abrirla pasados 2 minutos.",
            13f, Hud.MUTE,
        ))
    }

    private fun subirCopiaAhora(reintento: Boolean) {
        val usuario = cuenta.usuario ?: return
        ocupadoCopia = "subir"; pintar()
        lifecycleScope.launch {
            try {
                when (val e = PermisoDrive.pedir(this@MainActivity, usuario.email)) {
                    is PermisoDrive.Estado.NecesitaPermiso -> {
                        if (!reintento) {
                            trasPermisoDrive = { ok ->
                                if (ok) subirCopiaAhora(reintento = true)
                                else { ocupadoCopia = null; pintar() }
                            }
                            pedirPermisoDrive.launch(IntentSenderRequest.Builder(e.intent.intentSender).build())
                            return@launch
                        }
                        ultimaCopia = SubidaCopia(this@MainActivity).subir(SubidaCopia.Origen.MANUAL) // dejará el error
                    }
                    is PermisoDrive.Estado.Token ->
                        ultimaCopia = SubidaCopia(this@MainActivity).subir(SubidaCopia.Origen.MANUAL, e.valor)
                }
            } catch (ex: Exception) {
                errorDrive = "${ex.javaClass.simpleName}: ${ex.message ?: "sin detalle"}"
            }
            ocupadoCopia = null
            pintar()
        }
    }

    private fun leerFirestoreCopia() {
        val uid = cuenta.usuario?.uid ?: return
        ocupadoCopia = "fs"; pintar()
        lifecycleScope.launch {
            val l = Respaldo.leerFirestore(uid)
            lecturaFs = l
            tamanoCopia = if (l.ajustes != null) Json.escribir(
                CopiaVita.montar("nexo-${BuildConfig.VERSION_NAME}", java.time.Instant.now(), null,
                    l.ajustes, l.compra, l.colecciones)
            ).toByteArray(Charsets.UTF_8).size else null
            ocupadoCopia = null
            pintar()
        }
    }

    private fun comprobarDrive(reintento: Boolean) {
        val usuario = cuenta.usuario ?: return
        ocupadoCopia = "drive"; errorDrive = null; pintar()
        lifecycleScope.launch {
            try {
                when (val e = PermisoDrive.pedir(this@MainActivity, usuario.email)) {
                    is PermisoDrive.Estado.NecesitaPermiso -> {
                        if (reintento) {
                            errorDrive = "Google sigue pidiendo permiso después de concederlo."
                        } else {
                            trasPermisoDrive = { ok ->
                                if (ok) comprobarDrive(reintento = true)
                                else { ocupadoCopia = null; errorDrive = "No se concedió el permiso de Drive."; pintar() }
                            }
                            pedirPermisoDrive.launch(IntentSenderRequest.Builder(e.intent.intentSender).build())
                            return@launch // sigue al volver de la pantalla de Google
                        }
                    }
                    is PermisoDrive.Estado.Token -> {
                        val l = lecturaFs ?: Respaldo.leerFirestore(usuario.uid).also { lecturaFs = it }
                        estadoDrive = Respaldo.comprobarDrive(DriveApi(e.valor), l.carpetaId, l.ultimaId)
                    }
                }
            } catch (ex: Respaldo.CarpetaNoVisible) {
                estadoDrive = null; errorDrive = ex.message
            } catch (ex: Exception) {
                estadoDrive = null; errorDrive = "${ex.javaClass.simpleName}: ${ex.message ?: "sin detalle"}"
            }
            ocupadoCopia = null
            pintar()
        }
    }

    private fun iniciarSesion() {
        lifecycleScope.launch {
            mensajeCuenta = cuenta.iniciarSesion()
            pintar()
        }
    }

    private fun cerrarSesion() {
        lifecycleScope.launch {
            cuenta.cerrarSesion()
            mensajeCuenta = null
            pintar()
        }
    }

    private fun sincronizar() {
        val c = client ?: return
        sincronizando = true
        pintar()
        lifecycleScope.launch {
            ultimaSync = Sincronizador(this@MainActivity).sincronizar(c, Sincronizador.Origen.MANUAL)
            sincronizando = false
            actualizar() // vuelve a leer y repinta
        }
    }

    /** Permisos que se piden: pasos, sueño y, si el móvil lo admite, lectura en segundo plano. */
    private val permisosAPedir: Set<String>
        get() = if (segundoPlanoDisponible == true)
            LectorHealthConnect.PERMISOS + LectorHealthConnect.PERMISO_SEGUNDO_PLANO
        else LectorHealthConnect.PERMISOS

    private fun filaPermiso(clave: String, permiso: String): View {
        val ok = permiso in concedidos
        return hud.fila(clave, if (ok) "Concedido" else "Sin conceder", if (ok) Hud.OK else Hud.WARN)
    }

    private fun tarjetaLectura(): View = hud.tarjeta().apply {
        addView(hud.etiqueta("Lectura de Health Connect"))
        addView(hud.texto(
            "Últimos 7 días tal como están en Health Connect. «Leer ahora» solo lee.",
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
