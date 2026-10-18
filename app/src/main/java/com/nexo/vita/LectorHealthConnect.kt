package com.nexo.vita

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.reflect.KClass

/** Lee pasos y sueño de Health Connect. No escribe nada en ningún sitio. */
class LectorHealthConnect(private val client: HealthConnectClient) {

    companion object {
        val PERMISO_PASOS = HealthPermission.getReadPermission(StepsRecord::class)
        val PERMISO_SUENO = HealthPermission.getReadPermission(SleepSessionRecord::class)
        val PERMISOS = setOf(PERMISO_PASOS, PERMISO_SUENO)

        fun traducirFase(tipo: Int): Fase = when (tipo) {
            SleepSessionRecord.STAGE_TYPE_DEEP -> Fase.PROFUNDO
            SleepSessionRecord.STAGE_TYPE_LIGHT -> Fase.LIGERO
            SleepSessionRecord.STAGE_TYPE_REM -> Fase.REM
            SleepSessionRecord.STAGE_TYPE_SLEEPING -> Fase.DORMIDO_SIN_DETALLE
            SleepSessionRecord.STAGE_TYPE_AWAKE,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> Fase.DESPIERTO
            else -> Fase.DESCONOCIDO
        }
    }

    suspend fun permisosConcedidos(): Set<String> =
        client.permissionController.getGrantedPermissions()

    /** Lee los últimos [dias] días (hoy incluido), del más reciente al más antiguo. */
    suspend fun leerDias(dias: Int, concedidos: Set<String>): List<Dia> {
        val hoy = LocalDate.now(ZONA_ESPANA)
        val ahora = Instant.now()

        val suenoPorDia: Map<LocalDate, SuenoDia> =
            if (PERMISO_SUENO in concedidos) {
                // Desde el día anterior al primero, para coger noches que empiezan antes de medianoche.
                val desde = hoy.minusDays(dias.toLong()).atStartOfDay(ZONA_ESPANA).toInstant()
                SuenoDia.agrupar(leerSesiones(desde, ahora))
            } else emptyMap()

        return (0 until dias).map { i ->
            val fecha = hoy.minusDays(i.toLong())
            val pasos = if (PERMISO_PASOS in concedidos) leerPasos(fecha, ahora)
            else PasosDia(null, emptyList(), emptyMap())
            Dia(fecha, pasos, suenoPorDia[fecha])
        }
    }

    private suspend fun leerPasos(fecha: LocalDate, ahora: Instant): PasosDia {
        val inicio = fecha.atStartOfDay(ZONA_ESPANA).toInstant()
        val finDia = fecha.plusDays(1).atStartOfDay(ZONA_ESPANA).toInstant()
        val fin = if (finDia.isAfter(ahora)) ahora else finDia
        val filtro = TimeRangeFilter.between(inicio, fin)

        // Total oficial: agregado de Health Connect (combina fuentes según su prioridad).
        val agregado = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = filtro,
            )
        )
        val total = agregado[StepsRecord.COUNT_TOTAL]
        val origenes = agregado.dataOrigins.map { it.packageName }.sorted()

        // Diagnóstico: registros en bruto sumados por app (puede contar dos veces lo mismo).
        val desglose = leerTodo(StepsRecord::class, filtro)
            .groupBy { it.metadata.dataOrigin.packageName }
            .mapValues { (_, lista) -> lista.sumOf { it.count } }

        return PasosDia(total, origenes, desglose)
    }

    private suspend fun leerSesiones(desde: Instant, hasta: Instant): List<SesionSueno> =
        leerTodo(SleepSessionRecord::class, TimeRangeFilter.between(desde, hasta)).map { r ->
            SesionSueno(
                inicio = r.startTime.atZone(ZONA_ESPANA),
                fin = r.endTime.atZone(ZONA_ESPANA),
                origen = r.metadata.dataOrigin.packageName,
                tramos = r.stages.map { s ->
                    Tramo(traducirFase(s.stage), Duration.between(s.startTime, s.endTime).seconds)
                },
                modificado = r.metadata.lastModifiedTime,
            )
        }

    private suspend fun <T : Record> leerTodo(tipo: KClass<T>, filtro: TimeRangeFilter): List<T> {
        val salida = mutableListOf<T>()
        var token: String? = null
        do {
            val respuesta = client.readRecords(
                ReadRecordsRequest(
                    recordType = tipo,
                    timeRangeFilter = filtro,
                    pageToken = token,
                )
            )
            salida += respuesta.records
            token = respuesta.pageToken
        } while (!token.isNullOrEmpty())
        return salida
    }
}
