package com.nexo.vita

import java.time.Duration
import java.time.Instant
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
    /** Última modificación del registro en Health Connect (para quedarse con la versión más reciente). */
    val modificado: Instant = Instant.EPOCH,
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

/**
 * El sueño de un día: las sesiones que TERMINAN ese día (hora de España), ya sin solapes.
 * [descartadas]: sesiones que se solapaban con otra y no cuentan (solo para mostrar).
 */
data class SuenoDia(
    val fecha: LocalDate,
    val sesiones: List<SesionSueno>,
    val descartadas: List<SesionSueno> = emptyList(),
) {
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
        /**
         * Quita solapes y agrupa por la fecha local (España) en la que termina cada sesión.
         *
         * Mi Fitness puede dejar en Health Connect varias versiones de la misma noche
         * (p. ej. 05:03–10:35 y 05:03–11:46). Las sesiones que se solapan en el tiempo se
         * tratan como una sola noche y se queda la modificada más recientemente
         * (a igualdad, la más larga). Las que no se solapan (una siesta) se suman.
         */
        fun agrupar(sesiones: List<SesionSueno>): Map<LocalDate, SuenoDia> {
            val ganadoras = mutableListOf<SesionSueno>()
            val descartadasDe = mutableMapOf<SesionSueno, List<SesionSueno>>()

            var grupo = mutableListOf<SesionSueno>()
            var finGrupo: ZonedDateTime? = null
            fun cerrar() {
                if (grupo.isEmpty()) return
                val g = grupo.maxWithOrNull(compareBy<SesionSueno>({ it.modificado }, { it.duracionSeg }))!!
                ganadoras += g
                descartadasDe[g] = grupo.filter { it !== g }
                grupo = mutableListOf()
            }
            for (s in sesiones.sortedBy { it.inicio }) {
                val f = finGrupo
                if (f != null && s.inicio.isBefore(f)) {
                    grupo += s
                    if (s.fin.isAfter(f)) finGrupo = s.fin
                } else {
                    cerrar()
                    grupo += s
                    finGrupo = s.fin
                }
            }
            cerrar()

            return ganadoras
                .groupBy { it.fin.withZoneSameInstant(ZONA_ESPANA).toLocalDate() }
                .mapValues { (fecha, lista) ->
                    SuenoDia(
                        fecha = fecha,
                        sesiones = lista.sortedBy { it.inicio },
                        descartadas = lista.flatMap { descartadasDe[it].orEmpty() }.sortedBy { it.inicio },
                    )
                }
        }
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
