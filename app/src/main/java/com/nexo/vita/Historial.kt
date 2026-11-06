package com.nexo.vita

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

/** Historial de las últimas ejecuciones automáticas (para comprobar que salta cada día). */
object Historial {
    private const val K = "historial"
    private const val MAX = 30

    enum class Paso { OK, FALLO, OMITIDA }

    data class Entrada(
        val cuando: ZonedDateTime,
        val sync: Paso,
        val syncTexto: String,
        val copia: Paso,
        val copiaTexto: String,
        val intento: Int,
    )

    fun registrar(ctx: Context, e: Entrada) {
        val lista = leer(ctx).toMutableList()
        lista.add(0, e)
        val arr = JSONArray()
        lista.take(MAX).forEach {
            arr.put(JSONObject()
                .put("cuando", it.cuando.toString())
                .put("sync", it.sync.name).put("syncTexto", it.syncTexto)
                .put("copia", it.copia.name).put("copiaTexto", it.copiaTexto)
                .put("intento", it.intento))
        }
        prefs(ctx).edit().putString(K, arr.toString()).commit()
    }

    fun leer(ctx: Context): List<Entrada> {
        val txt = prefs(ctx).getString(K, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(txt)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entrada(
                    ZonedDateTime.parse(o.getString("cuando")),
                    Paso.valueOf(o.getString("sync")), o.optString("syncTexto"),
                    Paso.valueOf(o.getString("copia")), o.optString("copiaTexto"),
                    o.optInt("intento", 0),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nexo", Context.MODE_PRIVATE)
}
