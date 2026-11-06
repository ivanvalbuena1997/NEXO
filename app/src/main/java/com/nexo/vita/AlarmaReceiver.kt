package com.nexo.vita

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Recibe la alarma: encola la pasada y programa la siguiente hora de la lista. */
class AlarmaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Programador.ACCION_ALARMA) return
        if (!Programador.activa(context)) return
        // La alarma que salta es la que estaba guardada como «próxima».
        val programada = Programador.proxima(context) ?: java.time.ZonedDateTime.now()
        val ultima = Programador.esUltimaDelDia(programada, Programador.horas(context))
        Programador.encolarSincronizacion(context, ultima)
        Programador.reprogramar(context)
    }
}

/**
 * Las alarmas se pierden al reiniciar el móvil; también se reprograman al actualizar NEXO
 * y cuando se concede el permiso «Alarmas y recordatorios».
 */
class SistemaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                if (Programador.activa(context)) Programador.reprogramar(context)
        }
    }
}
