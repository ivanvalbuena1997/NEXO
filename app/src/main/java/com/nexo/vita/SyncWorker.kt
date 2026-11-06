package com.nexo.vita

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nexo.vita.drive.SubidaCopia
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Pasada automática en segundo plano (la lanza la alarma, varias veces al día):
 * 1. sincroniza pasos y sueño de ayer y hoy;
 * 2. justo después, si está activada, sube la copia de V.I.T.A. a Drive:
 *    - en la pasada de la última hora del día (con los datos más completos);
 *    - o, como red de seguridad, en cualquier pasada si no hay copia correcta desde antes de ayer.
 * Si algo falla por un motivo pasajero, se reintenta (la sincronización es idempotente y la copia
 * ya subida en este trabajo no se repite). Cada intento queda en el [Historial].
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

        val ultimaDelDia = inputData.getBoolean(Programador.DATO_ULTIMA_DEL_DIA, true)
        val copiaAtrasada = !copiaDesde(applicationContext, LocalDate.now(ZONA_ESPANA).minusDays(1))
        val yaEnEsteTrabajo = runAttemptCount > 0 && copiaDesdeInicioTrabajo(applicationContext)
        var copia = Historial.Paso.OMITIDA
        var copiaTexto = when {
            !copiaDiariaActiva(applicationContext) -> "Copia diaria desactivada."
            yaEnEsteTrabajo -> "Ya subida en un intento anterior."
            else -> "Se sube en la última pasada del día."
        }
        if (copiaDiariaActiva(applicationContext) && !yaEnEsteTrabajo && (ultimaDelDia || copiaAtrasada)) {
            val c = SubidaCopia(applicationContext).subir(SubidaCopia.Origen.AUTOMATICA)
            copia = if (c.ok) Historial.Paso.OK else Historial.Paso.FALLO
            copiaTexto = c.texto
            if (!c.ok && c.reintentable) reintentar = true
        }

        if (runAttemptCount == 0) marcarInicioTrabajo(applicationContext, inicio)
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

        /** ¿Hay una copia correcta con fecha [desde] o posterior? */
        fun copiaDesde(ctx: Context, desde: LocalDate): Boolean {
            val u = SubidaCopia.ultimo(ctx) ?: return false
            return u.ok && !u.cuando.withZoneSameInstant(ZONA_ESPANA).toLocalDate().isBefore(desde)
        }

        private const val K_INICIO = "trabajo_inicio"

        private fun marcarInicioTrabajo(ctx: Context, t: ZonedDateTime) {
            ctx.getSharedPreferences("nexo", Context.MODE_PRIVATE).edit().putString(K_INICIO, t.toString()).commit()
        }

        /** En un reintento: ¿ya se subió bien la copia desde el primer intento de este trabajo? */
        private fun copiaDesdeInicioTrabajo(ctx: Context): Boolean {
            val ini = ctx.getSharedPreferences("nexo", Context.MODE_PRIVATE).getString(K_INICIO, null)
                ?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() } ?: return false
            val u = SubidaCopia.ultimo(ctx) ?: return false
            return u.ok && !u.cuando.isBefore(ini)
        }
    }
}
