package com.nexo.vita.drive

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.nexo.vita.BuildConfig
import com.nexo.vita.ZONA_ESPANA
import com.nexo.vita.isoLocal
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Sube la copia de V.I.T.A. a la carpeta «V.I.T.A.» de Drive.
 * Firestore: solo lectura, salvo `app.drive.ultimaSubidaNexo` en ajustes/principal (update con ruta de campo).
 * No crea carpetas ni toca el buzón.
 */
class SubidaCopia(private val ctx: Context) {

    enum class Origen(val texto: String) { MANUAL("manual"), AUTOMATICA("automática") }

    data class Resultado(
        val ok: Boolean,
        val cuando: ZonedDateTime,
        val texto: String,
        val origen: Origen,
        val reintentable: Boolean = false,
        /** Falta conceder el permiso de Drive (solo se puede con NEXO abierta). */
        val faltaPermiso: Boolean = false,
    )

    /** [token]: si ya se tiene (subida manual); si no, se pide a Google sin interacción. */
    suspend fun subir(origen: Origen, token: String? = null): Resultado {
        val ahora = ZonedDateTime.now(ZONA_ESPANA)
        fun fallo(t: String, reintentable: Boolean = false, faltaPermiso: Boolean = false) =
            Resultado(false, ahora, t, origen, reintentable, faltaPermiso)

        var tokenUsado: String? = null
        val r = try {
            val usuario = FirebaseAuth.getInstance().currentUser
                ?: return guardar(fallo("No has iniciado sesión."))

            // 1. Firestore (solo lectura). Una copia incompleta no se sube.
            val l = Respaldo.leerFirestore(usuario.uid)
            if (l.ajustes == null) return guardar(fallo("No se pudo leer ajustes/principal.", reintentable = true))
            if (l.errores.isNotEmpty()) {
                return guardar(fallo(
                    "No se pudo leer todo Firestore; no se sube una copia incompleta. " +
                        l.errores.entries.joinToString("; ") { "${it.key}: ${it.value}" },
                    reintentable = true,
                ))
            }

            // 2. Permiso de Drive.
            val t = token ?: when (val e = PermisoDrive.pedir(ctx, usuario.email)) {
                is PermisoDrive.Estado.Token -> e.valor
                is PermisoDrive.Estado.NecesitaPermiso -> return guardar(fallo(
                    "Google pide volver a conceder el permiso de Drive. Abre NEXO y pulsa «Subir copia ahora».",
                    faltaPermiso = true,
                ))
            }
            tokenUsado = t
            val api = DriveApi(t)

            // 3. Carpeta existente (nunca se crea otra) y copia actual.
            val estado = Respaldo.comprobarDrive(api, l.carpetaId, l.ultimaId)
            val carpeta = estado.carpeta!!.id

            // 4. Comprobación de seguridad frente a la copia que ya hay.
            val conteos = l.colecciones.mapValues { it.value.size }
            val caidas = CopiaVita.caidasSospechosas(estado.conteosUltima, conteos)
            if (caidas.isNotEmpty()) {
                return guardar(fallo(
                    "No se sube: hay muchos menos datos que en la copia de Drive (${caidas.joinToString("; ")}). " +
                        "Si los has borrado a propósito, guarda una copia desde V.I.T.A."
                ))
            }

            // 5. Montar la copia con el formato de V.I.T.A.
            val texto = Json.escribir(CopiaVita.montar(
                versionApp = "nexo-${BuildConfig.VERSION_NAME}",
                ahora = Instant.now(),
                documentacion = estado.documentacion,
                ajustes = l.ajustes,
                compra = l.compra,
                colecciones = l.colecciones,
            ))

            // 6. Subir: la última y la del día.
            val fecha = LocalDate.now(ZONA_ESPANA).toString()
            val nombreDia = CopiaVita.nombreFechada(fecha)
            if (estado.ultima != null) api.actualizar(estado.ultima.id, texto)
            else api.crear(carpeta, CopiaVita.ULTIMA, texto)

            val delDia = estado.archivos.firstOrNull { it.nombre == nombreDia }
            val idDia = if (delDia != null) { api.actualizar(delDia.id, texto); delDia.id }
            else api.crear(carpeta, nombreDia, texto)

            // 7. Dejar solo las 8 fechadas más recientes.
            val nombres = estado.archivos.map { it.id to it.nombre }.filter { it.first != idDia } + (idDia to nombreDia)
            val sobran = CopiaVita.sobrantes(nombres)
            val noBorradas = mutableListOf<String>()
            sobran.forEach { (id, nombre) ->
                try { api.borrar(id) } catch (e: Exception) { noBorradas += nombre }
            }

            // 8. Marcar en Ajustes de V.I.T.A. (solo ese campo).
            val ajustes = FirebaseFirestore.getInstance()
                .collection("users").document(usuario.uid).collection("ajustes").document("principal")
            withTimeout(30_000) { ajustes.update("app.drive.ultimaSubidaNexo", fecha).await() }

            val kb = (texto.toByteArray(Charsets.UTF_8).size + 512) / 1024
            Resultado(
                true, ahora,
                buildString {
                    append("Copia subida ($kb KB): ${CopiaVita.ULTIMA} y $nombreDia.")
                    if (sobran.size > noBorradas.size) append(" Borradas ${sobran.size - noBorradas.size} antiguas.")
                    if (noBorradas.isNotEmpty()) append(" No se pudieron borrar: ${noBorradas.joinToString()}.")
                    if (estado.documentacion == null) append(" Sin «documentacion» (no se pudo leer de la copia anterior).")
                },
                origen,
            )
        } catch (e: Respaldo.CarpetaNoVisible) {
            fallo(e.message ?: "No se ve la carpeta «V.I.T.A.».")
        } catch (e: DriveApi.ErrorDrive) {
            if (e.codigo == 401) tokenUsado?.let { PermisoDrive.olvidar(ctx, it) }
            fallo(e.message ?: "Error de Drive", reintentable = e.codigo == 401 || e.codigo == 429 || e.codigo >= 500)
        } catch (e: TimeoutCancellationException) {
            fallo("Sin respuesta de Firestore. ¿Hay conexión?", reintentable = true)
        } catch (e: java.io.IOException) {
            fallo("Sin conexión con Drive: ${e.message ?: "sin detalle"}", reintentable = true)
        } catch (e: Exception) {
            fallo("${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}", reintentable = true)
        }
        return guardar(r)
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
        private const val K_CUANDO = "copia_cuando"
        private const val K_OK = "copia_ok"
        private const val K_TEXTO = "copia_texto"
        private const val K_ORIGEN = "copia_origen"

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
