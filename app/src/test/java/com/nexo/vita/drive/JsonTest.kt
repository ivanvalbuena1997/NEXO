package com.nexo.vita.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTest {

    @Test
    fun numeros_enterosComoEnterosYDecimalesComoDecimales() {
        assertEquals("[9234,64.4,64,0.1,-3,1.5E-7,null]",
            Json.escribir(listOf(9234L, 64.4, 64.0, 0.1, -3, 1.5e-7, Double.NaN)))
    }

    @Test
    fun cadenas_escapadasComoJsonStringify() {
        assertEquals("\"Café \\\"con\\\" leche\\n\\\\ \\u0001 ñ\"", Json.escribir("Café \"con\" leche\n\\ \u0001 ñ"))
    }

    @Test
    fun mapasYListasAnidados() {
        val m = linkedMapOf("a" to 1L, "b" to listOf(true, null, mapOf("c" to "x")))
        assertEquals("""{"a":1,"b":[true,null,{"c":"x"}]}""", Json.escribir(m))
    }
}
