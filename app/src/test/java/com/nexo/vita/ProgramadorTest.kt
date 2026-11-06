package com.nexo.vita

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class ProgramadorTest {

    private fun t(s: String) = LocalDateTime.parse(s).atZone(ZONA_ESPANA)
    private val horas = listOf(LocalTime.of(12, 0), LocalTime.of(17, 0), LocalTime.of(22, 0))

    @Test
    fun siguiente_esLaProximaHoraDeLaLista() {
        assertEquals(t("2026-11-06T12:00:00"), Programador.siguiente(t("2026-11-06T08:30:00"), horas))
        assertEquals(t("2026-11-06T17:00:00"), Programador.siguiente(t("2026-11-06T12:00:00"), horas))
        assertEquals(t("2026-11-06T22:00:00"), Programador.siguiente(t("2026-11-06T17:01:00"), horas))
    }

    @Test
    fun despuesDeLaUltima_esLaPrimeraDeManana() {
        assertEquals(t("2026-11-07T12:00:00"), Programador.siguiente(t("2026-11-06T22:00:00"), horas))
        assertEquals(t("2026-11-07T12:00:00"), Programador.siguiente(t("2026-11-06T23:59:00"), horas))
    }

    @Test
    fun unaSolaHora_comoAntes() {
        val una = listOf(LocalTime.of(21, 0))
        assertEquals(t("2026-10-18T21:00:00"), Programador.siguiente(t("2026-10-18T20:39:00"), una))
        assertEquals(t("2026-10-19T21:00:00"), Programador.siguiente(t("2026-10-18T21:00:00"), una))
    }

    @Test
    fun cambioAHorarioDeInvierno_mantieneLaHoraDelReloj() {
        // El 25/10/2026 a las 03:00 se vuelve a las 02:00 en España.
        val s = Programador.siguiente(t("2026-10-24T22:30:00"), horas)
        assertEquals("2026-10-25T12:00+01:00[Europe/Madrid]", s.toString())
    }

    @Test
    fun ultimaDelDia_soloLaHoraMasTardia() {
        assertTrue(Programador.esUltimaDelDia(t("2026-11-06T22:00:00"), horas))
        assertFalse(Programador.esUltimaDelDia(t("2026-11-06T17:00:00"), horas))
    }

    @Test
    fun horas_seGuardanOrdenadasYSinRepetir() {
        val texto = Programador.escribirHoras(listOf(LocalTime.of(22, 0), LocalTime.of(7, 5), LocalTime.of(22, 0)))
        assertEquals("07:05,22:00", texto)
        assertEquals(listOf(LocalTime.of(7, 5), LocalTime.of(22, 0)), Programador.leerHoras("22:00, 07:05,basura"))
    }
}
