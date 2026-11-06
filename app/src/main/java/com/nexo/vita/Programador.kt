package com.nexo.vita

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Sincronización automática a varias horas del día (hasta [MAX_HORAS]).
 * Una única alarma exacta apunta siempre a la próxima hora de la lista; al saltar lanza un
 * trabajo corto ([SyncWorker]) y programa la siguiente. La pasada de la última hora del día
 * es la que sube la copia de V.I.T.A. a Drive.
 */
object Programador {
    const val ACCION_ALARMA = "com.nexo.vita.SINCRONIZAR"
    const val MAX_HORAS = 6
    const val DATO_ULTIMA_DEL_DIA = "ultima_del_dia"
    private const val TRABAJO = "sync_diaria"
    val HORAS_POR_DEFECTO = listOf(LocalTime.of(12, 0), LocalTime.of(17, 0), LocalTime.of(22, 0))

    private const val K_ACTIVA = "prog_activa"
    private const val K_HORAS = "prog_horas"
    private const val K_PROXIMA = "prog_proxima"
    private const val K_EXACTA = "prog_exacta"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nexo", Context.MODE_PRIVATE)

    fun activa(ctx: Context) = prefs(ctx).getBoolean(K_ACTIVA, false)

    /** Horas elegidas, ordenadas y sin repetir. */
    fun horas(ctx: Context): List<LocalTime> =
        prefs(ctx).getString(K_HORAS, null)?.let { leerHoras(it) }?.takeIf { it.isNotEmpty() } ?: HORAS_POR_DEFECTO

    fun proxima(ctx: Context): ZonedDateTime? =
        prefs(ctx).getString(K_PROXIMA, null)?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }

    fun puedeExacta(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun leerHoras(texto: String): List<LocalTime> =
        texto.split(',').mapNotNull { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }.distinct().sorted()

    fun escribirHoras(horas: List<LocalTime>): String =
        horas.distinct().sorted().joinToString(",") { "%02d:%02d".format(it.hour, it.minute) }

    /** Próxima vez que toca alguna de las [horas], estrictamente después de [ahora]. */
    fun siguiente(ahora: ZonedDateTime, horas: List<LocalTime>): ZonedDateTime =
        horas.map { h ->
            val hoy = ahora.toLocalDate().atTime(h).atZone(ahora.zone)
            if (hoy.isAfter(ahora)) hoy else ahora.toLocalDate().plusDays(1).atTime(h).atZone(ahora.zone)
        }.minOrNull() ?: error("Sin horas")

    /** ¿La pasada de [cuando] es la de la última hora del día de la lista? */
    fun esUltimaDelDia(cuando: ZonedDateTime, horas: List<LocalTime>): Boolean {
        val ultima = horas.maxOrNull() ?: return true
        return cuando.toLocalTime().withSecond(0).withNano(0) == ultima
    }

    fun activar(ctx: Context, activa: Boolean) {
        prefs(ctx).edit().putBoolean(K_ACTIVA, activa).commit()
        reprogramar(ctx)
    }

    /** Guarda la lista (máximo [MAX_HORAS]) y reprograma. */
    fun guardarHoras(ctx: Context, horas: List<LocalTime>) {
        val limpias = horas.map { it.withSecond(0).withNano(0) }.distinct().sorted().take(MAX_HORAS)
        prefs(ctx).edit().putString(K_HORAS, escribirHoras(limpias)).commit()
        reprogramar(ctx)
    }

    /** Programa (o cancela) la próxima alarma según los ajustes guardados. Idempotente. */
    @SuppressLint("ScheduleExactAlarm") // se comprueba antes con puedeExacta()
    fun reprogramar(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = intentAlarma(ctx)
        if (!activa(ctx)) {
            am.cancel(pi)
            prefs(ctx).edit().remove(K_PROXIMA).commit()
            return
        }
        val cuando = siguiente(ZonedDateTime.now(ZoneId.systemDefault()), horas(ctx))
        val ms = cuando.toInstant().toEpochMilli()
        val exacta = puedeExacta(ctx)
        if (exacta) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        } else {
            // Sin el permiso «Alarmas y recordatorios» el sistema puede retrasarla.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
        prefs(ctx).edit()
            .putString(K_PROXIMA, cuando.toString())
            .putBoolean(K_EXACTA, exacta)
            .commit()
    }

    /** Encola la pasada en segundo plano (necesita conexión). */
    fun encolarSincronizacion(ctx: Context, ultimaDelDia: Boolean) {
        val peticion = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(DATO_ULTIMA_DEL_DIA to ultimaDelDia))
            .apply {
                // En Android 12+ un trabajo urgente no necesita servicio en primer plano.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(TRABAJO, ExistingWorkPolicy.REPLACE, peticion)
    }

    private fun intentAlarma(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        0,
        Intent(ctx, AlarmaReceiver::class.java).setAction(ACCION_ALARMA),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
