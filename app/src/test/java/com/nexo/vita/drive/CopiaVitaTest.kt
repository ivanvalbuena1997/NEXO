package com.nexo.vita.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CopiaVitaTest {

    private val ahora = Instant.parse("2026-11-05T15:44:00Z")

    private fun copia(doc: Any? = mapOf("lee_esto" to listOf("x"))) = CopiaVita.montar(
        versionApp = "nexo-0.4.0",
        ahora = ahora,
        documentacion = doc,
        ajustes = mapOf("schema" to 1L),
        compra = mapOf("items" to emptyList<Any>()),
        colecciones = mapOf(
            "dias" to listOf(mapOf("fecha" to "2026-11-02"), mapOf("fecha" to "2026-10-30")),
            "sesiones" to listOf(mapOf("fecha" to "2026-11-01"), mapOf("fecha" to "2026-10-01")),
            "diario" to listOf(mapOf("fecha" to "2026-11-05"), mapOf("fecha" to "2026-11-04")),
            "alimentos" to listOf(mapOf("id" to "b"), mapOf("id" to "a")),
        ),
    )

    @Test
    fun formatoDeVita_campoACampoYEnOrden() {
        val c = copia()
        assertEquals(listOf("app", "version_app", "esquema", "formato", "exportado", "documentacion", "datos"), c.keys.toList())
        assertEquals("vita", c["app"])
        assertEquals("nexo-0.4.0", c["version_app"])
        assertEquals(1L, c["esquema"])
        assertEquals(1L, c["formato"])
        assertEquals("2026-11-05T15:44:00.000Z", c["exportado"])
        val datos = c["datos"] as Map<*, *>
        assertEquals(
            listOf("ajustes", "ejercicios", "sesiones", "dias", "alimentos", "menus", "diario", "planes", "compra"),
            datos.keys.toList(),
        )
        assertEquals(emptyList<Any>(), datos["planes"])
    }

    @Test
    fun sesionesDiasYDiario_ordenadosPorFecha_elRestoTalCual() {
        val datos = copia()["datos"] as Map<*, *>
        fun fechas(k: String) = (datos[k] as List<*>).map { (it as Map<*, *>)["fecha"] }
        assertEquals(listOf("2026-10-30", "2026-11-02"), fechas("dias"))
        assertEquals(listOf("2026-10-01", "2026-11-01"), fechas("sesiones"))
        assertEquals(listOf("2026-11-04", "2026-11-05"), fechas("diario"))
        assertEquals(listOf("b", "a"), (datos["alimentos"] as List<*>).map { (it as Map<*, *>)["id"] })
    }

    @Test
    fun sinDocumentacion_seOmite() {
        assertFalse(copia(doc = null).containsKey("documentacion"))
    }

    @Test
    fun limpiar_ordenaClavesYMarcaTiposEspeciales() {
        val especiales = mutableListOf<String>()
        val r = CopiaVita.limpiar(mapOf("b" to 2, "a" to listOf(1.5f, Any())), especiales, "dias/x")
        assertEquals(listOf("a", "b"), (r as Map<*, *>).keys.toList())
        assertEquals(2L, r["b"])
        assertEquals(1, especiales.size)
        assertTrue(especiales[0].startsWith("dias/x.a[1]"))
    }

    @Test
    fun sobrantes_dejaLasOchoFechadasMasRecientes() {
        val nombres = (1..10).map { "id$it" to "vita-copia-2026-11-%02d.json".format(it) } +
            listOf("u" to "vita-copia-ultima.json", "r" to "vita-copia-antes-de-restaurar-2026-10-01.json")
        val borrar = CopiaVita.sobrantes(nombres)
        assertEquals(listOf("vita-copia-2026-11-02.json", "vita-copia-2026-11-01.json"), borrar.map { it.second })
    }

    @Test
    fun caidasSospechosas_soloCuandoUnaColeccionGrandeBajaAMenosDeLaMitad() {
        val antes = mapOf("alimentos" to 122, "menus" to 2, "dias" to 67, "diario" to 40)
        assertTrue(CopiaVita.caidasSospechosas(antes, mapOf("alimentos" to 123, "menus" to 0, "dias" to 67, "diario" to 41)).isEmpty())
        assertEquals(
            listOf("dias: 67 → 0 documentos", "alimentos: 122 → 60 documentos"),
            CopiaVita.caidasSospechosas(antes, mapOf("alimentos" to 60, "menus" to 2, "diario" to 40)),
        )
        assertTrue(CopiaVita.caidasSospechosas(emptyMap(), mapOf("dias" to 0)).isEmpty())
    }
}
