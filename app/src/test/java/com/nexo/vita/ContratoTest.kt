package com.nexo.vita

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ContratoTest {

    private fun t(s: String) = LocalDateTime.parse(s).atZone(ZONA_ESPANA)
    private val ahora = t("2026-10-18T21:00:05")
    private val fecha = LocalDate.parse("2026-10-18")

    private val noche = SesionSueno(
        t("2026-10-18T05:03:00"), t("2026-10-18T11:46:00"), "com.xiaomi.wearable",
        listOf(Tramo(Fase.LIGERO, 318 * 60L), Tramo(Fase.PROFUNDO, 84 * 60L), Tramo(Fase.DESPIERTO, 60L)),
    )

    private fun dia(pasos: Long?, conSueno: Boolean) = Dia(
        fecha,
        PasosDia(pasos, emptyList(), emptyMap()),
        if (conSueno) SuenoDia(fecha, listOf(noche)) else null,
    )

    @Test
    fun documentoCompleto_siguElContrato() {
        val p = Contrato.plan(dia(9234, true), null, ahora)
        assertTrue(p.escribe)
        assertTrue(p.borrarSueno)
        assertEquals("2026-10-18", p.datos["fecha"])
        assertEquals(9234L, p.datos["pasos"])
        assertEquals("health-connect", p.datos["pasosFuente"])
        assertEquals("2026-10-18T21:00:05+02:00", p.datos["sincronizado"])
        val s = p.datos["sueno"] as Map<*, *>
        assertEquals(402L, s["min"])
        assertEquals("2026-10-18T05:03:00+02:00", s["inicio"])
        assertEquals("2026-10-18T11:46:00+02:00", s["fin"])
        assertEquals(mapOf("profundo" to 84L, "ligero" to 318L, "rem" to 0L, "despierto" to 1L), s["fases"])
        assertEquals(1, s["sesiones"])
        assertEquals("health-connect", s["fuente"])
    }

    @Test
    fun pasosManual_noSeTocanPeroElSuenoSi() {
        val p = Contrato.plan(dia(9234, true), mapOf("pasos" to 8000L, "pasosFuente" to "manual"), ahora)
        assertEquals(Contrato.Estado.MANUAL, p.pasos)
        assertFalse(p.datos.containsKey("pasos"))
        assertFalse(p.datos.containsKey("pasosFuente"))
        assertTrue(p.datos.containsKey("sueno"))
    }

    @Test
    fun suenoManual_noSeTocaNiSeBorra() {
        val existente = mapOf("sueno" to mapOf("min" to 420L, "fuente" to "manual"))
        val p = Contrato.plan(dia(9234, true), existente, ahora)
        assertEquals(Contrato.Estado.MANUAL, p.sueno)
        assertFalse(p.borrarSueno)
        assertFalse(p.datos.containsKey("sueno"))
        assertEquals(9234L, p.datos["pasos"])
    }

    @Test
    fun sinDatos_noSeEscribeNada() {
        val p = Contrato.plan(dia(null, false), mapOf("peso" to 80.5), ahora)
        assertFalse(p.escribe)
        assertFalse(p.borrarSueno)
        assertEquals(Contrato.Estado.SIN_DATOS, p.pasos)
        assertEquals(Contrato.Estado.SIN_DATOS, p.sueno)
    }

    @Test
    fun soloPasos_noBorraElSuenoAnterior() {
        val p = Contrato.plan(dia(500, false), null, ahora)
        assertTrue(p.escribe)
        assertFalse(p.borrarSueno)
        assertNull(p.datos["sueno"])
    }

    @Test
    fun nocheSinFases_omiteFases() {
        val sinFases = noche.copy(tramos = emptyList())
        val d = Dia(fecha, PasosDia(null, emptyList(), emptyMap()), SuenoDia(fecha, listOf(sinFases)))
        val s = Contrato.plan(d, null, ahora).datos["sueno"] as Map<*, *>
        assertFalse(s.containsKey("fases"))
        assertEquals(403L, s["min"])
    }
}
