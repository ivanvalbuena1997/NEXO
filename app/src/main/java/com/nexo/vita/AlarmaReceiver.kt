package com.nexo.vita

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Recibe la alarma diaria: encola la sincronización y programa la del día siguiente. */
class AlarmaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Programador.ACCION_ALARMA) return
        if (!Programador.activa(context)) return
        Programador.encolarSincronizacion(context)
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
