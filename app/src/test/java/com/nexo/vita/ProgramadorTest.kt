package com.nexo.vita

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class ProgramadorTest {

    private fun t(s: String) = LocalDateTime.parse(s).atZone(ZONA_ESPANA)

    @Test
    fun antesDeLaHora_esHoy() {
        assertEquals(t("2026-10-18T21:00:00"), Programador.siguiente(t("2026-10-18T20:39:00"), 21, 0))
    }

    @Test
    fun justoALaHoraODespues_esManana() {
        assertEquals(t("2026-10-19T21:00:00"), Programador.siguiente(t("2026-10-18T21:00:00"), 21, 0))
        assertEquals(t("2026-10-19T21:00:00"), Programador.siguiente(t("2026-10-18T22:15:00"), 21, 0))
    }

    @Test
    fun cambioAHorarioDeInvierno_mantieneLaHoraDelReloj() {
        // El 25/10/2026 a las 03:00 se vuelve a las 02:00 en España.
        val s = Programador.siguiente(t("2026-10-24T22:00:00"), 21, 0)
        assertEquals("2026-10-25T21:00+01:00[Europe/Madrid]", s.toString())
    }
}
