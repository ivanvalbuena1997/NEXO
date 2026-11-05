package com.nexo.vita.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Llamadas REST a la API de Google Drive v3, las mismas que usa V.I.T.A. (js/drive.js):
 * files.get, files.list, files.get?alt=media, subida multipart (crear),
 * subida media con PATCH (actualizar) y files.delete.
 */
class DriveApi(private val token: String) {

    class ErrorDrive(val codigo: Int, mensaje: String) : Exception(mensaje)

    data class Archivo(
        val id: String,
        val nombre: String,
        val mimeType: String,
        val enPapelera: Boolean,
        val modificado: String?,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** El archivo o carpeta; null si Drive responde 404 (no existe o NEXO no tiene permiso para verlo). */
    suspend fun obtener(id: String): Archivo? = try {
        archivo(JSONObject(llamar(get("$DRIVE/files/$id", "fields" to "id,name,mimeType,trashed,modifiedTime"))))
    } catch (e: ErrorDrive) {
        if (e.codigo == 404) null else throw e
    }

    /** Archivos dentro de una carpeta (sin los de la papelera). */
    suspend fun listar(carpeta: String): List<Archivo> {
        val salida = mutableListOf<Archivo>()
        var pagina: String? = null
        do {
            val params = mutableListOf(
                "q" to "'$carpeta' in parents and trashed = false",
                "fields" to "nextPageToken,files(id,name,mimeType,trashed,modifiedTime)",
                "pageSize" to "100",
                "spaces" to "drive",
            )
            pagina?.let { params += "pageToken" to it }
            val j = JSONObject(llamar(get("$DRIVE/files", *params.toTypedArray())))
            val files = j.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) salida += archivo(files.getJSONObject(i))
            pagina = j.optString("nextPageToken").ifEmpty { null }
        } while (pagina != null)
        return salida
    }

    suspend fun descargarTexto(id: String): String = llamar(get("$DRIVE/files/$id", "alt" to "media"))

    /** Sustituye el contenido de un archivo existente. */
    suspend fun actualizar(id: String, texto: String) {
        val url = "$SUBIR/files/$id".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "media")
            .addQueryParameter("fields", "id")
            .build()
        llamar(peticion(url.toString()).patch(texto.toRequestBody(JSON)).build())
    }

    /** Crea un JSON dentro de la carpeta y devuelve su id. */
    suspend fun crear(carpeta: String, nombre: String, texto: String): String {
        val limite = "nexo" + System.nanoTime().toString(36)
        val meta = JSONObject()
            .put("name", nombre)
            .put("parents", org.json.JSONArray().put(carpeta))
            .put("mimeType", "application/json")
        val cuerpo = "--$limite\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$limite\r\nContent-Type: application/json\r\n\r\n$texto\r\n--$limite--"
        val url = "$SUBIR/files".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id")
            .build()
        val r = llamar(
            peticion(url.toString())
                .post(cuerpo.toRequestBody("multipart/related; boundary=$limite".toMediaType()))
                .build()
        )
        return JSONObject(r).getString("id")
    }

    suspend fun borrar(id: String) {
        llamar(peticion("$DRIVE/files/$id").delete().build())
    }

    // ------------------------------------------------------------------ interno

    private fun peticion(url: String) = Request.Builder().url(url).header("Authorization", "Bearer $token")

    private fun get(base: String, vararg params: Pair<String, String>): Request {
        val url = base.toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return peticion(url.toString()).get().build()
    }

    private suspend fun llamar(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            val cuerpo = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(cuerpo).getJSONObject("error").getString("message") }.getOrDefault("")
                throw ErrorDrive(r.code, "Drive respondió ${r.code}${if (msg.isNotEmpty()) ": $msg" else ""}")
            }
            cuerpo
        }
    }

    private fun archivo(j: JSONObject) = Archivo(
        id = j.getString("id"),
        nombre = j.optString("name"),
        mimeType = j.optString("mimeType"),
        enPapelera = j.optBoolean("trashed", false),
        modificado = j.optString("modifiedTime").ifEmpty { null },
    )

    companion object {
        const val CARPETA_MIME = "application/vnd.google-apps.folder"
        private const val DRIVE = "https://www.googleapis.com/drive/v3"
        private const val SUBIR = "https://www.googleapis.com/upload/drive/v3"
        private val JSON = "application/json; charset=UTF-8".toMediaType()
    }
}
