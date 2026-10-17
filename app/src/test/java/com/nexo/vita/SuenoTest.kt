package com.nexo.vita

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SuenoTest {

    private fun t(s: String) = LocalDateTime.parse(s).atZone(ZONA_ESPANA)

    @Test
    fun nocheConFases_vaAlDiaEnQueTermina() {
        val s = SesionSueno(
            inicio = t("2026-10-16T23:41:00"),
            fin = t("2026-10-17T07:28:00"),
            origen = "app",
            tramos = listOf(
                Tramo(Fase.LIGERO, 251 * 60L),
                Tramo(Fase.PROFUNDO, 78 * 60L),
                Tramo(Fase.REM, 96 * 60L),
                Tramo(Fase.DESPIERTO, 27 * 60L),
                Tramo(Fase.DESCONOCIDO, 15 * 60L),
            ),
        )
        val dias = SuenoDia.agrupar(listOf(s))
        val d = dias.getValue(LocalDate.parse("2026-10-17"))
        assertEquals(251L + 78 + 96, d.min) // despierto y desconocido no cuentan
        assertEquals(FasesMin(78, 251, 96, 27), d.fases)
        assertEquals("2026-10-16T23:41:00+02:00", isoLocal(d.principal!!.inicio))
        assertEquals("2026-10-17T07:28:00+02:00", isoLocal(d.principal!!.fin))
    }

    @Test
    fun sinFases_cuentaLaSesionEntera() {
        val s = SesionSueno(t("2026-10-16T23:00:00"), t("2026-10-17T07:00:00"), "app", emptyList())
        val d = SuenoDia.agrupar(listOf(s)).getValue(LocalDate.parse("2026-10-17"))
        assertEquals(480L, d.min)
        assertNull(d.fases)
    }

    @Test
    fun siesta_seSumaYLaNocheMarcaInicioYFin() {
        val noche = SesionSueno(t("2026-10-16T23:00:00"), t("2026-10-17T07:00:00"), "app", emptyList())
        val siesta = SesionSueno(t("2026-10-17T15:00:00"), t("2026-10-17T15:40:00"), "app", emptyList())
        val d = SuenoDia.agrupar(listOf(siesta, noche)).getValue(LocalDate.parse("2026-10-17"))
        assertEquals(520L, d.min)
        assertEquals(2, d.sesiones.size)
        assertEquals(noche, d.principal)
    }

    @Test
    fun dormidoSinDetalle_cuentaComoDormido() {
        val s = SesionSueno(
            t("2026-10-16T23:00:00"), t("2026-10-17T06:00:00"), "app",
            listOf(Tramo(Fase.DORMIDO_SIN_DETALLE, 400 * 60L), Tramo(Fase.DESPIERTO, 20 * 60L)),
        )
        val d = SuenoDia.agrupar(listOf(s)).getValue(LocalDate.parse("2026-10-17"))
        assertEquals(400L, d.min)
        assertEquals(FasesMin(0, 0, 0, 20), d.fases)
    }

    @Test
    fun horaDeInvierno_usaDesplazamientoDeInvierno() {
        val s = SesionSueno(t("2026-12-01T23:00:00"), t("2026-12-02T07:00:00"), "app", emptyList())
        val d = SuenoDia.agrupar(listOf(s)).getValue(LocalDate.parse("2026-12-02"))
        assertEquals("2026-12-02T07:00:00+01:00", isoLocal(d.principal!!.fin))
    }
}
