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
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Sincronización diaria: una alarma exacta a la hora elegida lanza un trabajo corto
 * ([SyncWorker]) y programa la del día siguiente.
 */
object Programador {
    const val ACCION_ALARMA = "com.nexo.vita.SINCRONIZAR"
    private const val TRABAJO = "sync_diaria"

    private const val K_ACTIVA = "prog_activa"
    private const val K_HORA = "prog_hora"
    private const val K_MINUTO = "prog_minuto"
    private const val K_PROXIMA = "prog_proxima"
    private const val K_EXACTA = "prog_exacta"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nexo", Context.MODE_PRIVATE)

    fun activa(ctx: Context) = prefs(ctx).getBoolean(K_ACTIVA, false)
    fun hora(ctx: Context) = prefs(ctx).getInt(K_HORA, 21)
    fun minuto(ctx: Context) = prefs(ctx).getInt(K_MINUTO, 0)
    fun proxima(ctx: Context): ZonedDateTime? =
        prefs(ctx).getString(K_PROXIMA, null)?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }
    /** Si la última alarma programada fue exacta (false = el sistema puede retrasarla). */
    fun ultimaFueExacta(ctx: Context) = prefs(ctx).getBoolean(K_EXACTA, false)

    fun puedeExacta(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Próxima vez que toca [hora]:[minuto] estrictamente después de [ahora]. */
    fun siguiente(ahora: ZonedDateTime, hora: Int, minuto: Int): ZonedDateTime {
        var fecha = ahora.toLocalDate()
        var t = fecha.atTime(hora, minuto).atZone(ahora.zone)
        if (!t.isAfter(ahora)) {
            fecha = fecha.plusDays(1)
            t = fecha.atTime(hora, minuto).atZone(ahora.zone)
        }
        return t
    }

    fun configurar(ctx: Context, activa: Boolean, hora: Int, minuto: Int) {
        prefs(ctx).edit()
            .putBoolean(K_ACTIVA, activa)
            .putInt(K_HORA, hora)
            .putInt(K_MINUTO, minuto)
            .commit()
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
        val cuando = siguiente(ZonedDateTime.now(ZoneId.systemDefault()), hora(ctx), minuto(ctx))
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

    /** Encola la sincronización en segundo plano (necesita conexión). */
    fun encolarSincronizacion(ctx: Context) {
        val peticion = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
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
