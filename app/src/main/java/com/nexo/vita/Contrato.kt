package com.nexo.vita

import org.json.JSONObject
import java.time.ZonedDateTime

/**
 * Contrato de datos con V.I.T.A.: documento users/{uid}/dias/{AAAA-MM-DD}.
 *
 * Reglas:
 * - Escribir SIEMPRE con merge y solo estos campos (V.I.T.A. guarda más cosas en el documento).
 * - Sin datos en Health Connect → el campo no se escribe (nunca 0).
 * - `pasosFuente == "manual"` → no tocar `pasos`; `sueno.fuente == "manual"` → no tocar `sueno`.
 * - `sueno` es un mapa: se borra y se reescribe en el mismo lote (ver [Sincronizador]).
 */
object Contrato {
    const val FUENTE = "health-connect"
    const val MANUAL = "manual"

    enum class Estado { ESCRITO, MANUAL, SIN_DATOS }

    /** Lo que hay que escribir en un día. `datos` vacío = no se escribe nada. */
    data class Plan(
        val datos: Map<String, Any>,
        val borrarSueno: Boolean,
        val pasos: Estado,
        val sueno: Estado,
    ) {
        val escribe: Boolean get() = datos.isNotEmpty()
    }

    fun mapaPasos(dia: Dia): Map<String, Any>? =
        dia.pasos.total?.let { mapOf("pasos" to it, "pasosFuente" to FUENTE) }

    fun mapaSueno(dia: Dia): Map<String, Any>? {
        val sueno = dia.sueno ?: return null
        val principal = sueno.principal ?: return null
        val m = linkedMapOf<String, Any>(
            "min" to sueno.min,
            "inicio" to isoLocal(principal.inicio),
            "fin" to isoLocal(principal.fin),
        )
        sueno.fases?.let { f ->
            m["fases"] = linkedMapOf(
                "profundo" to f.profundo,
                "ligero" to f.ligero,
                "rem" to f.rem,
                "despierto" to f.despierto,
            )
        }
        m["sesiones"] = sueno.sesiones.size
        m["fuente"] = FUENTE
        return m
    }

    /**
     * Decide qué escribir comparando lo leído de Health Connect con el documento actual
     * de Firestore ([existente], null si no existe).
     */
    fun plan(dia: Dia, existente: Map<String, Any?>?, ahora: ZonedDateTime): Plan {
        val pasosManual = existente?.get("pasosFuente") == MANUAL
        val suenoManual = (existente?.get("sueno") as? Map<*, *>)?.get("fuente") == MANUAL

        val pasos = mapaPasos(dia)
        val sueno = mapaSueno(dia)

        val estPasos = when {
            pasosManual -> Estado.MANUAL
            pasos == null -> Estado.SIN_DATOS
            else -> Estado.ESCRITO
        }
        val estSueno = when {
            suenoManual -> Estado.MANUAL
            sueno == null -> Estado.SIN_DATOS
            else -> Estado.ESCRITO
        }

        val datos = linkedMapOf<String, Any>()
        if (estPasos == Estado.ESCRITO) datos.putAll(pasos!!)
        if (estSueno == Estado.ESCRITO) datos["sueno"] = sueno!!
        if (datos.isNotEmpty()) {
            datos["fecha"] = dia.fecha.toString()
            datos["sincronizado"] = isoLocal(ahora)
        }
        return Plan(datos, borrarSueno = estSueno == Estado.ESCRITO, estPasos, estSueno)
    }

    /** Vista previa en pantalla: lo que se escribiría si no hubiera nada marcado como manual. */
    fun documento(dia: Dia): JSONObject {
        val doc = JSONObject()
        doc.put("fecha", dia.fecha.toString())
        mapaPasos(dia)?.forEach { (k, v) -> doc.put(k, v) }
        mapaSueno(dia)?.let { doc.put("sueno", JSONObject(it)) }
        doc.put("sincronizado", "(hora de la sincronización)")
        return doc
    }
}
