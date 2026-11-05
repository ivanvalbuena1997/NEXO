package com.nexo.vita.drive

import java.time.Instant

/**
 * JSON propio (sin org.json, para poder probarlo en las pruebas unitarias):
 * escribe los valores tal cual los da Firestore, sin tipos especiales.
 * - Enteros (Long/Int) como enteros; decimales (Double) como decimales.
 *   Un Double sin decimales (64.0) se escribe «64», igual que JSON.stringify en V.I.T.A.
 * - Mapas en el orden en que se recorren; las listas, tal cual.
 */
object Json {

    fun escribir(valor: Any?): String = StringBuilder().also { escribir(valor, it) }.toString()

    private fun escribir(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> cadena(v, sb)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Long, is Int, is Short, is Byte -> sb.append(v.toString())
            is Double -> numero(v, sb)
            is Float -> numero(v.toDouble(), sb)
            is Map<*, *> -> {
                sb.append('{')
                var primero = true
                for ((k, x) in v) {
                    if (!primero) sb.append(',')
                    primero = false
                    cadena(k.toString(), sb)
                    sb.append(':')
                    escribir(x, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var primero = true
                for (x in v) {
                    if (!primero) sb.append(',')
                    primero = false
                    escribir(x, sb)
                }
                sb.append(']')
            }
            is Array<*> -> escribir(v.asList(), sb)
            is Instant -> cadena(v.toString(), sb)
            else -> throw IllegalArgumentException("Tipo no admitido en la copia: ${v.javaClass.name}")
        }
    }

    private fun numero(d: Double, sb: StringBuilder) {
        when {
            d.isNaN() || d.isInfinite() -> sb.append("null") // como JSON.stringify
            d == Math.rint(d) && Math.abs(d) < 1e15 -> sb.append(d.toLong().toString())
            else -> sb.append(d.toString()) // Java da los dígitos justos para recuperar el mismo número
        }
    }

    private fun cadena(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
