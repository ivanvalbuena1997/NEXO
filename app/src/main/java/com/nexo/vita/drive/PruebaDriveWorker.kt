package com.nexo.vita.drive

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.nexo.vita.ZONA_ESPANA
import com.nexo.vita.isoLocal
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Prueba (0.4.0): con NEXO cerrada, ¿da Google el token de Drive sin interacción?
 * Solo pide el token y mira la carpeta; no escribe nada.
 */
class PruebaDriveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val texto = try {
            val usuario = FirebaseAuth.getInstance().currentUser
            if (usuario == null) "No has iniciado sesión en NEXO."
            else when (val e = PermisoDrive.pedir(applicationContext, usuario.email)) {
                is PermisoDrive.Estado.NecesitaPermiso ->
                    "Google pidió interacción: en segundo plano NO se puede obtener el permiso."
                is PermisoDrive.Estado.Token -> {
                    val carpeta = Respaldo.leerFirestore(usuario.uid).carpetaId
                    val visible = carpeta?.let { DriveApi(e.valor).obtener(it) }
                    "Token obtenido sin interacción. Carpeta «V.I.T.A.»: " +
                        when {
                            carpeta == null -> "sin id en Ajustes."
                            visible == null -> "NO visible."
                            else -> "visible («${visible.nombre}»)."
                        }
                }
            }
        } catch (e: Exception) {
            "Error: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}"
        }
        guardar(applicationContext, texto)
        return Result.success()
    }

    companion object {
        private const val K_TEXTO = "drive_prueba_texto"
        private const val K_CUANDO = "drive_prueba_cuando"

        fun encolar(ctx: Context) {
            val p = OneTimeWorkRequestBuilder<PruebaDriveWorker>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("prueba_drive", ExistingWorkPolicy.REPLACE, p)
            guardar(ctx, "Programada: se ejecutará dentro de 1 minuto aproximadamente.")
        }

        private fun guardar(ctx: Context, texto: String) {
            ctx.getSharedPreferences("nexo", Context.MODE_PRIVATE).edit()
                .putString(K_TEXTO, texto)
                .putString(K_CUANDO, isoLocal(ZonedDateTime.now(ZONA_ESPANA)))
                .commit()
        }

        fun ultimo(ctx: Context): Pair<String, String>? {
            val p = ctx.getSharedPreferences("nexo", Context.MODE_PRIVATE)
            val t = p.getString(K_TEXTO, null) ?: return null
            return (p.getString(K_CUANDO, "") ?: "") to t
        }
    }
}
