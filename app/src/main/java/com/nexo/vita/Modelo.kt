package com.nexo.vita

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Zona de las fechas del contrato con V.I.T.A.: fecha local de España. */
val ZONA_ESPANA: ZoneId = ZoneId.of("Europe/Madrid")

private val ISO_CON_ZONA: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

fun isoLocal(t: ZonedDateTime): String = t.withZoneSameInstant(ZONA_ESPANA).format(ISO_CON_ZONA)

/** Fases de sueño agrupadas como las usa el contrato. */
enum class Fase { PROFUNDO, LIGERO, REM, DORMIDO_SIN_DETALLE, DESPIERTO, DESCONOCIDO }

/** Un tramo de fase dentro de una sesión de sueño. */
data class Tramo(val fase: Fase, val segundos: Long)

/** Una sesión de sueño tal como llega de Health Connect, ya traducida. */
data class SesionSueno(
    val inicio: ZonedDateTime,
    val fin: ZonedDateTime,
    val origen: String,
    val tramos: List<Tramo>,
) {
    val duracionSeg: Long get() = Duration.between(inicio, fin).seconds
    val conFases: Boolean get() = tramos.isNotEmpty()

    /** Segundos dormido: LIGHT + DEEP + REM + SLEEPING; sin fases, la sesión entera. */
    val dormidoSeg: Long
        get() = if (!conFases) duracionSeg else tramos
            .filter { it.fase in FASES_DORMIDO }
            .sumOf { it.segundos }

    fun segundosDe(fase: Fase): Long = tramos.filter { it.fase == fase }.sumOf { it.segundos }

    companion object {
        val FASES_DORMIDO = setOf(Fase.PROFUNDO, Fase.LIGERO, Fase.REM, Fase.DORMIDO_SIN_DETALLE)
    }
}

/** Fases del contrato, en minutos. */
data class FasesMin(val profundo: Long, val ligero: Long, val rem: Long, val despierto: Long)

/** El sueño de un día: todas las sesiones que TERMINAN ese día (hora de España). */
data class SuenoDia(val fecha: LocalDate, val sesiones: List<SesionSueno>) {
    /** Minutos dormido sumando todas las sesiones (siestas incluidas). */
    val min: Long get() = aMinutos(sesiones.sumOf { it.dormidoSeg })

    /** La sesión más larga marca `inicio` y `fin`. */
    val principal: SesionSueno? get() = sesiones.maxByOrNull { it.duracionSeg }

    /** Fases sumadas de las sesiones que las traen; null si ninguna trae fases. */
    val fases: FasesMin?
        get() {
            val conFases = sesiones.filter { it.conFases }
            if (conFases.isEmpty()) return null
            return FasesMin(
                profundo = aMinutos(conFases.sumOf { it.segundosDe(Fase.PROFUNDO) }),
                ligero = aMinutos(conFases.sumOf { it.segundosDe(Fase.LIGERO) }),
                rem = aMinutos(conFases.sumOf { it.segundosDe(Fase.REM) }),
                despierto = aMinutos(conFases.sumOf { it.segundosDe(Fase.DESPIERTO) }),
            )
        }

    companion object {
        /** Agrupa sesiones por la fecha local (España) en la que terminan. */
        fun agrupar(sesiones: List<SesionSueno>): Map<LocalDate, SuenoDia> =
            sesiones
                .groupBy { it.fin.withZoneSameInstant(ZONA_ESPANA).toLocalDate() }
                .mapValues { (fecha, lista) -> SuenoDia(fecha, lista.sortedBy { it.inicio }) }
    }
}

/** Pasos de un día. `total` es el agregado de Health Connect (null = sin datos). */
data class PasosDia(
    val total: Long?,
    val origenesAgregado: List<String>,
    /** Suma de registros por app, SIN deduplicar: solo para diagnóstico. */
    val desglose: Map<String, Long>,
)

data class Dia(val fecha: LocalDate, val pasos: PasosDia, val sueno: SuenoDia?)

/** Redondeo al minuto más cercano. */
fun aMinutos(segundos: Long): Long = (segundos + 30) / 60
