package com.nexo.vita.drive

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TreeMap

/**
 * Copia de V.I.T.A. con EXACTAMENTE su formato (js/backup.js → crearCopia):
 * { app, version_app, esquema, formato, exportado, documentacion?, datos { ajustes, ejercicios,
 *   sesiones, dias, alimentos, menus, diario, planes, compra } }
 */
object CopiaVita {
    const val ESQUEMA = 1L
    const val FORMATO = 1L
    const val ULTIMA = "vita-copia-ultima.json"
    const val GUARDAR_FECHADAS = 8

    /** Colecciones de users/{uid}, en el orden de V.I.T.A. (js/db-claves.js). */
    val COLECCIONES = listOf("ejercicios", "sesiones", "dias", "alimentos", "menus", "diario", "planes")
    private val POR_FECHA = setOf("sesiones", "dias", "diario")

    private val FECHADA = Regex("""^vita-copia-(\d{4}-\d{2}-\d{2})\.json$""")
    private val ISO_JS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun nombreFechada(fecha: String) = "vita-copia-$fecha.json"

    /** Lo mismo que `new Date().toISOString()` en JavaScript. */
    fun exportado(ahora: Instant): String = ISO_JS.format(ahora)

    /**
     * Valores de Firestore → JSON «puro»: mapas con las claves ordenadas, Long y Double tal cual.
     * Los tipos especiales (Timestamp, referencias…) se cuentan en [especiales] y se pasan a texto.
     */
    fun limpiar(
        v: Any?,
        especiales: MutableList<String>,
        ruta: String = "",
        convertir: (Any) -> Any? = { it.toString() },
    ): Any? = when (v) {
        null, is String, is Boolean, is Long, is Double -> v
        is Int -> v.toLong()
        is Float -> v.toDouble()
        is Map<*, *> -> TreeMap<String, Any?>().apply {
            v.forEach { (k, x) -> put(k.toString(), limpiar(x, especiales, "$ruta.$k", convertir)) }
        }
        is List<*> -> v.mapIndexed { i, x -> limpiar(x, especiales, "$ruta[$i]", convertir) }
        else -> {
            especiales += "$ruta (${v.javaClass.simpleName})"
            convertir(v)
        }
    }

    /**
     * Monta la copia. [colecciones]: documentos (data) de cada colección en el orden de Firestore.
     * sesiones, dias y diario se ordenan por fecha, como V.I.T.A.
     */
    fun montar(
        versionApp: String,
        ahora: Instant,
        documentacion: Any?,
        ajustes: Map<String, Any?>,
        compra: Map<String, Any?>?,
        colecciones: Map<String, List<Map<String, Any?>>>,
    ): Map<String, Any?> {
        fun col(nombre: String): List<Map<String, Any?>> {
            val docs = colecciones[nombre].orEmpty()
            return if (nombre in POR_FECHA) docs.sortedBy { it["fecha"] as? String ?: "" } else docs
        }
        val datos = linkedMapOf<String, Any?>(
            "ajustes" to ajustes,
            "ejercicios" to col("ejercicios"),
            "sesiones" to col("sesiones"),
            "dias" to col("dias"),
            "alimentos" to col("alimentos"),
            "menus" to col("menus"),
            "diario" to col("diario"),
            "planes" to col("planes"),
            "compra" to compra,
        )
        val copia = linkedMapOf<String, Any?>(
            "app" to "vita",
            "version_app" to versionApp,
            "esquema" to ESQUEMA,
            "formato" to FORMATO,
            "exportado" to exportado(ahora),
        )
        if (documentacion != null) copia["documentacion"] = documentacion
        copia["datos"] = datos
        return copia
    }

    /** De una lista de nombres de la carpeta, las copias fechadas que sobran (más allá de las 8 más recientes). */
    fun sobrantes(nombres: List<Pair<String, String>>): List<Pair<String, String>> =
        nombres.filter { FECHADA.matches(it.second) }
            .sortedByDescending { it.second }
            .drop(GUARDAR_FECHADAS)

    fun esFechada(nombre: String) = FECHADA.matches(nombre)
}
