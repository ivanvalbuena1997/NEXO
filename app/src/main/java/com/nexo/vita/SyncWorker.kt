package com.nexo.vita

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Sincronización automática en segundo plano (la lanza la alarma diaria). */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) {
            return Result.failure()
        }
        val client = HealthConnectClient.getOrCreate(applicationContext)
        val r = Sincronizador(applicationContext).sincronizar(client, Sincronizador.Origen.AUTOMATICA)
        return when {
            r.ok -> Result.success()
            r.reintentable && runAttemptCount < MAX_REINTENTOS -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        private const val MAX_REINTENTOS = 3
    }
}
