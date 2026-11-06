package com.nexo.vita

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nexo.vita.drive.SubidaCopia
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Tarea diaria en segundo plano (la lanza la alarma):
 * 1. sincroniza pasos y sueño de ayer y hoy;
 * 2. justo después, sube la copia de V.I.T.A. a Drive (si está activada y no se ha subido ya hoy).
 * Si alguna falla por un motivo pasajero, se reintenta todo (la sincronización es idempotente
 * y la copia no se repite si ya se subió hoy). Cada intento queda en el [Historial].
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        var reintentar = false
        val inicio = ZonedDateTime.now(ZONA_ESPANA)

        var sync = Historial.Paso.FALLO
        var syncTexto = "Health Connect no disponible."
        if (HealthConnectClient.getSdkStatus(applicationContext) == HealthConnectClient.SDK_AVAILABLE) {
            val client = HealthConnectClient.getOrCreate(applicationContext)
            val r = Sincronizador(applicationContext).sincronizar(client, Sincronizador.Origen.AUTOMATICA)
            sync = if (r.ok) Historial.Paso.OK else Historial.Paso.FALLO
            syncTexto = r.texto
            if (!r.ok && r.reintentable) reintentar = true
        }

        var copia = Historial.Paso.OMITIDA
        var copiaTexto = when {
            !copiaDiariaActiva(applicationContext) -> "Copia diaria desactivada."
            else -> "Ya había copia de hoy."
        }
        if (copiaDiariaActiva(applicationContext) && !copiaHechaHoy(applicationContext)) {
            val c = SubidaCopia(applicationContext).subir(SubidaCopia.Origen.AUTOMATICA)
            copia = if (c.ok) Historial.Paso.OK else Historial.Paso.FALLO
            copiaTexto = c.texto
            if (!c.ok && c.reintentable) reintentar = true
        }

        Historial.registrar(
            applicationContext,
            Historial.Entrada(inicio, sync, syncTexto, copia, copiaTexto, runAttemptCount),
        )
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
