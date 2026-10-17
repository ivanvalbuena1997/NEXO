package com.nexo.vita

import org.json.JSONObject

/**
 * Documento que NEXO escribiría en users/{uid}/dias/{AAAA-MM-DD} según el contrato
 * de datos con V.I.T.A. En la 0.1 solo se muestra: no se escribe nada.
 *
 * Reglas reflejadas aquí: sin datos → el campo no aparece (nunca 0);
 * `fases` solo si Health Connect trae fases.
 * Las reglas de escritura (merge, respetar "manual", borrar y reescribir `sueno`
 * en el mismo lote) se aplicarán en la 0.2, al escribir en Firestore.
 */
object Contrato {
    const val FUENTE = "health-connect"

    fun documento(dia: Dia): JSONObject {
        val doc = JSONObject()
        doc.put("fecha", dia.fecha.toString())

        dia.pasos.total?.let { pasos ->
            doc.put("pasos", pasos)
            doc.put("pasosFuente", FUENTE)
        }

        val sueno = dia.sueno
        val principal = sueno?.principal
        if (sueno != null && principal != null) {
            val s = JSONObject()
            s.put("min", sueno.min)
            s.put("inicio", isoLocal(principal.inicio))
            s.put("fin", isoLocal(principal.fin))
            sueno.fases?.let { f ->
                s.put("fases", JSONObject().apply {
                    put("profundo", f.profundo)
                    put("ligero", f.ligero)
                    put("rem", f.rem)
                    put("despierto", f.despierto)
                })
            }
            s.put("sesiones", sueno.sesiones.size)
            s.put("fuente", FUENTE)
            doc.put("sueno", s)
        }

        doc.put("sincronizado", "(se añade al escribir)")
        return doc
    }
}
