package com.nexo.vita

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
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

    /** Caso real del 18/10: Mi Fitness dejó dos versiones de la misma noche. */
    @Test
    fun versionesSolapadas_cuentaSoloLaMasReciente() {
        val primera = SesionSueno(
            t("2026-10-18T05:03:00"), t("2026-10-18T10:35:00"), "com.xiaomi.wearable",
            listOf(Tramo(Fase.LIGERO, 260 * 60L), Tramo(Fase.PROFUNDO, 72 * 60L)),
            modificado = Instant.parse("2026-10-18T08:40:00Z"),
        )
        val segunda = SesionSueno(
            t("2026-10-18T05:03:00"), t("2026-10-18T11:46:00"), "com.xiaomi.wearable",
            listOf(Tramo(Fase.LIGERO, 317 * 60L), Tramo(Fase.PROFUNDO, 85 * 60L), Tramo(Fase.DESPIERTO, 60L)),
            modificado = Instant.parse("2026-10-18T09:50:00Z"),
        )
        val d = SuenoDia.agrupar(listOf(primera, segunda)).getValue(LocalDate.parse("2026-10-18"))
        assertEquals(listOf(segunda), d.sesiones)
        assertEquals(listOf(primera), d.descartadas)
        assertEquals(402L, d.min)
        assertEquals(FasesMin(85, 317, 0, 1), d.fases)
    }

    @Test
    fun solapadasSinFechaDeModificacion_ganaLaMasLarga() {
        val corta = SesionSueno(t("2026-10-18T01:00:00"), t("2026-10-18T05:00:00"), "app", emptyList())
        val larga = SesionSueno(t("2026-10-18T00:30:00"), t("2026-10-18T07:00:00"), "app", emptyList())
        val d = SuenoDia.agrupar(listOf(corta, larga)).getValue(LocalDate.parse("2026-10-18"))
        assertEquals(listOf(larga), d.sesiones)
        assertEquals(390L, d.min)
    }

    @Test
    fun horaDeInvierno_usaDesplazamientoDeInvierno() {
        val s = SesionSueno(t("2026-12-01T23:00:00"), t("2026-12-02T07:00:00"), "app", emptyList())
        val d = SuenoDia.agrupar(listOf(s)).getValue(LocalDate.parse("2026-12-02"))
        assertEquals("2026-12-02T07:00:00+01:00", isoLocal(d.principal!!.fin))
    }
}
