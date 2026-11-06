package com.nexo.vita.drive

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lectura de todo lo de users/{uid} en Firestore (solo lectura) y comprobación de la
 * carpeta «V.I.T.A.» en Drive. Base de la copia de seguridad.
 */
object Respaldo {
    private const val TIEMPO_MAX_MS = 60_000L

    /** Lo leído de Firestore. [errores]: colección → motivo, si alguna no se pudo leer. */
    data class Lectura(
        val ajustes: Map<String, Any?>?,
        val compra: Map<String, Any?>?,
        val colecciones: Map<String, List<Map<String, Any?>>>,
        val errores: Map<String, String>,
        val especiales: List<String>,
    ) {
        @Suppress("UNCHECKED_CAST")
        private val drive: Map<String, Any?>?
            get() = ((ajustes?.get("app") as? Map<String, Any?>)?.get("drive")) as? Map<String, Any?>
        val carpetaId: String? get() = drive?.get("carpeta") as? String
        val ultimaId: String? get() = drive?.get("ultima") as? String
        val completa: Boolean get() = ajustes != null && errores.isEmpty()
    }

    suspend fun leerFirestore(uid: String): Lectura {
        val db = FirebaseFirestore.getInstance()
        val usuario = db.collection("users").document(uid)
        val especiales = mutableListOf<String>()
        val errores = linkedMapOf<String, String>()
        val convertir: (Any) -> Any? = { v -> if (v is Timestamp) CopiaVita.exportado(v.toDate().toInstant()) else v.toString() }

        suspend fun documento(col: String, id: String): Map<String, Any?>? = try {
            val s = withTimeout(TIEMPO_MAX_MS) { usuario.collection(col).document(id).get(Source.SERVER).await() }
            @Suppress("UNCHECKED_CAST")
            s.data?.let { CopiaVita.limpiar(it, especiales, "$col/$id", convertir) as Map<String, Any?> }
        } catch (e: Exception) {
            errores["$col/$id"] = motivo(e); null
        }

        val ajustes = documento("ajustes", "principal")
        val compra = documento("compra", "actual")
        val colecciones = linkedMapOf<String, List<Map<String, Any?>>>()
        for (c in CopiaVita.COLECCIONES) {
            try {
                val q = withTimeout(TIEMPO_MAX_MS) { usuario.collection(c).get(Source.SERVER).await() }
                @Suppress("UNCHECKED_CAST")
                colecciones[c] = q.documents.mapNotNull { d ->
                    d.data?.let { CopiaVita.limpiar(it, especiales, "$c/${d.id}", convertir) as Map<String, Any?> }
                }
            } catch (e: Exception) {
                errores[c] = motivo(e)
            }
        }
        return Lectura(ajustes, compra, colecciones, errores, especiales)
    }

    private fun motivo(e: Exception): String = when {
        e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            "las reglas de Firestore no dejan leerla"
        e is kotlinx.coroutines.TimeoutCancellationException -> "sin respuesta (¿hay conexión?)"
        else -> "${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}"
    }

    /** Estado de la carpeta «V.I.T.A.» vista con el permiso de NEXO. */
    data class EstadoDrive(
        val carpeta: DriveApi.Archivo?,
        val archivos: List<DriveApi.Archivo>,
        val ultima: DriveApi.Archivo?,
        /** «documentacion» de vita-copia-ultima.json, o null si no se pudo leer. */
        val documentacion: Any?,
        val versionUltima: String?,
        val exportadoUltima: String?,
        val avisoUltima: String?,
        /** Documentos por colección en la copia que ya hay en Drive (para la comprobación de seguridad). */
        val conteosUltima: Map<String, Int> = emptyMap(),
    ) {
        val fechadas: List<DriveApi.Archivo> get() = archivos.filter { CopiaVita.esFechada(it.nombre) }
    }

    class CarpetaNoVisible(mensaje: String) : Exception(mensaje)

    suspend fun comprobarDrive(api: DriveApi, carpetaId: String?, ultimaId: String?): EstadoDrive {
        if (carpetaId.isNullOrBlank()) {
            throw CarpetaNoVisible(
                "En Ajustes de V.I.T.A. no hay carpeta de Drive (app.drive.carpeta). " +
                    "Guarda antes una copia en Drive desde V.I.T.A."
            )
        }
        val carpeta = api.obtener(carpetaId)
        if (carpeta == null || carpeta.enPapelera || carpeta.mimeType != DriveApi.CARPETA_MIME) {
            throw CarpetaNoVisible(
                if (carpeta == null)
                    "NEXO no puede ver la carpeta «V.I.T.A.» ($carpetaId) con su permiso: Drive responde " +
                        "que no existe. Con el permiso drive.file, Drive solo enseña a una app los archivos " +
                        "creados por ella; parece que no considera a NEXO la misma app que V.I.T.A."
                else if (carpeta.enPapelera) "La carpeta «V.I.T.A.» está en la papelera de Drive."
                else "El id de app.drive.carpeta no es una carpeta (${carpeta.mimeType})."
            )
        }
        val archivos = api.listar(carpetaId)
        val ultima = archivos.firstOrNull { it.id == ultimaId && it.nombre == CopiaVita.ULTIMA }
            ?: archivos.firstOrNull { it.nombre == CopiaVita.ULTIMA }

        var documentacion: Any? = null
        var version: String? = null
        var exportado: String? = null
        var aviso: String? = null
        val conteos = mutableMapOf<String, Int>()
        if (ultima == null) {
            aviso = "No hay ${CopiaVita.ULTIMA} en la carpeta: la copia irá sin «documentacion»."
        } else {
            try {
                val j = JSONObject(api.descargarTexto(ultima.id))
                version = j.optString("version_app").ifEmpty { null }
                exportado = j.optString("exportado").ifEmpty { null }
                documentacion = j.opt("documentacion")?.let { aKotlin(it) }
                j.optJSONObject("datos")?.let { d ->
                    CopiaVita.COLECCIONES.forEach { c -> d.optJSONArray(c)?.let { conteos[c] = it.length() } }
                }
                if (documentacion == null) aviso = "La copia de Drive no trae «documentacion»: la nueva irá sin ella."
            } catch (e: Exception) {
                aviso = "No se pudo leer ${CopiaVita.ULTIMA} (${e.message}): la copia irá sin «documentacion»."
            }
        }
        return EstadoDrive(carpeta, archivos, ultima, documentacion, version, exportado, aviso, conteos)
    }

    /** org.json → mapas y listas de Kotlin (conserva el orden de las claves). */
    fun aKotlin(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> linkedMapOf<String, Any?>().apply { v.keys().forEach { k -> put(k, aKotlin(v.get(k))) } }
        is JSONArray -> (0 until v.length()).map { aKotlin(v.get(it)) }
        is Int -> v.toLong()
        else -> v
    }
}
