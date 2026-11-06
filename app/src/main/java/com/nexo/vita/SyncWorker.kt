package com.nexo.vita

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nexo.vita.drive.SubidaCopia
import java.time.LocalDate

/**
 * Tarea diaria en segundo plano (la lanza la alarma):
 * 1. sincroniza pasos y sueño de ayer y hoy;
 * 2. justo después, sube la copia de V.I.T.A. a Drive (si está activada y no se ha subido ya hoy).
 * Si alguna falla por un motivo pasajero, se reintenta todo (la sincronización es idempotente
 * y la copia no se repite si ya se subió hoy).
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        var reintentar = false

        if (HealthConnectClient.getSdkStatus(applicationContext) == HealthConnectClient.SDK_AVAILABLE) {
            val client = HealthConnectClient.getOrCreate(applicationContext)
            val r = Sincronizador(applicationContext).sincronizar(client, Sincronizador.Origen.AUTOMATICA)
            if (!r.ok && r.reintentable) reintentar = true
        }

        if (copiaDiariaActiva(applicationContext) && !copiaHechaHoy(applicationContext)) {
            val c = SubidaCopia(applicationContext).subir(SubidaCopia.Origen.AUTOMATICA)
            if (!c.ok && c.reintentable) reintentar = true
        }

        return if (reintentar && runAttemptCount < MAX_REINTENTOS) Result.retry() else Result.success()
    }

    companion object {
        private const val MAX_REINTENTOS = 3
        private const val K_COPIA_DIARIA = "copia_diaria"

        fun copiaDiariaActiva(ctx: Context) =
            ctx.getSharedPreferences("nexo", Context.MODE_PRIVATE).getBoolean(K_COPIA_DIARIA, true)

        fun activarCopiaDiaria(ctx: Context, activa: Boolean) {
            ctx.getSharedPreferences("nexo", Context.MODE_PRIVATE).edit().putBoolean(K_COPIA_DIARIA, activa).commit()
        }

        fun copiaHechaHoy(ctx: Context): Boolean {
            val u = SubidaCopia.ultimo(ctx) ?: return false
            return u.ok && u.cuando.withZoneSameInstant(ZONA_ESPANA).toLocalDate() == LocalDate.now(ZONA_ESPANA)
        }
    }
}
