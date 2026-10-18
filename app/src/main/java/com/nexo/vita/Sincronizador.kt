package com.nexo.vita

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.time.ZonedDateTime

/** Lee AYER y HOY de Health Connect y los escribe en Firestore según el [Contrato]. */
class Sincronizador(private val ctx: Context) {

    enum class Origen(val texto: String) { MANUAL("manual"), AUTOMATICA("automática") }

    data class Resultado(
        val ok: Boolean,
        val cuando: ZonedDateTime,
        val texto: String,
        val origen: Origen = Origen.MANUAL,
        /** Fallo pasajero (red, Firestore) que merece reintentar; no se guarda. */
        val reintentable: Boolean = false,
    )

    suspend fun sincronizar(client: HealthConnectClient, origen: Origen): Resultado {
        val ahora = ZonedDateTime.now(ZONA_ESPANA)
        fun fallo(texto: String, reintentable: Boolean = false) =
            Resultado(false, ahora, texto, origen, reintentable)

        val res = try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
                ?: return guardar(fallo("No has iniciado sesión."))

            val lector = LectorHealthConnect(client)
            val concedidos = lector.permisosConcedidos()
            if (!concedidos.containsAll(LectorHealthConnect.PERMISOS)) {
                return guardar(fallo("Faltan permisos de Health Connect (pasos y sueño)."))
            }
            if (origen == Origen.AUTOMATICA && LectorHealthConnect.PERMISO_SEGUNDO_PLANO !in concedidos) {
                return guardar(fallo("Falta el permiso de Health Connect para leer en segundo plano."))
            }

            val dias = lector.leerDias(2, concedidos) // [hoy, ayer]
            val db = FirebaseFirestore.getInstance()
            val coleccion = db.collection("users").document(uid).collection("dias")

            // Leer del servidor (no de la caché) para respetar de verdad lo marcado como «manual».
            val planes = dias.map { dia ->
                val ref = coleccion.document(dia.fecha.toString())
                val snap = withTimeout(TIEMPO_MAX_MS) { ref.get(Source.SERVER).await() }
                Triple(dia, ref, Contrato.plan(dia, snap.data, ahora))
            }

            val lote = db.batch()
            planes.filter { it.third.escribe }.forEach { (_, ref, plan) ->
                if (plan.borrarSueno) {
                    lote.set(ref, mapOf("sueno" to FieldValue.delete()), SetOptions.merge())
                }
                lote.set(ref, plan.datos, SetOptions.merge())
            }
            if (planes.any { it.third.escribe }) {
                withTimeout(TIEMPO_MAX_MS) { lote.commit().await() }
            }

            val hoy = ahora.toLocalDate()
            val texto = planes.joinToString("\n") { (dia, _, plan) ->
                val nombre = if (dia.fecha == hoy) "Hoy" else "Ayer"
                "$nombre: pasos ${describir(plan.pasos)}, sueño ${describir(plan.sueno)}"
            }
            Resultado(true, ahora, texto, origen)
        } catch (e: TimeoutCancellationException) {
            fallo("Sin respuesta de Firestore en ${TIEMPO_MAX_MS / 1000} s. ¿Hay conexión?", reintentable = true)
        } catch (e: SecurityException) {
            fallo("Health Connect denegó la lectura: ${e.message ?: "sin detalle"}")
        } catch (e: Exception) {
            fallo("${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}", reintentable = true)
        }
        return guardar(res)
    }

    private fun describir(e: Contrato.Estado) = when (e) {
        Contrato.Estado.ESCRITO -> "guardado"
        Contrato.Estado.MANUAL -> "a mano en V.I.T.A. (no se toca)"
        Contrato.Estado.SIN_DATOS -> "sin datos"
    }

    private fun guardar(r: Resultado): Resultado {
        prefs(ctx).edit()
            .putString(K_CUANDO, isoLocal(r.cuando))
            .putBoolean(K_OK, r.ok)
            .putString(K_TEXTO, r.texto)
            .putString(K_ORIGEN, r.origen.name)
            .commit()
        return r
    }

    companion object {
        private const val TIEMPO_MAX_MS = 30_000L
        private const val K_CUANDO = "sync_cuando"
        private const val K_OK = "sync_ok"
        private const val K_TEXTO = "sync_texto"
        private const val K_ORIGEN = "sync_origen"

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("nexo", Context.MODE_PRIVATE)

        fun ultimo(ctx: Context): Resultado? {
            val p = prefs(ctx)
            val cuando = p.getString(K_CUANDO, null) ?: return null
            return Resultado(
                p.getBoolean(K_OK, false),
                ZonedDateTime.parse(cuando),
                p.getString(K_TEXTO, "") ?: "",
                runCatching { Origen.valueOf(p.getString(K_ORIGEN, null) ?: "") }.getOrDefault(Origen.MANUAL),
            )
        }
    }
}
