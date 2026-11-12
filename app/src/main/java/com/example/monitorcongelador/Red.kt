package com.example.monitorcongelador

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class SesionExpirada : Exception("La sesión expiró")

class Respuesta(val codigo: Int, val cuerpo: String)

/** Petición HTTPS mínima, sin librerías externas (menos peso y menos consumo). */
private fun solicitud(metodo: String, url: String, cabeceras: Map<String, String>, cuerpo: String?): Respuesta {
    require(url.startsWith("https://")) { "Solo se permite HTTPS" }
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.requestMethod = metodo
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        cabeceras.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (cuerpo != null) {
            c.doOutput = true
            c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
        }
        val codigo = c.responseCode
        val flujo = if (codigo in 200..299) c.inputStream else c.errorStream
        val texto = flujo?.bufferedReader()?.use { it.readText() } ?: ""
        return Respuesta(codigo, texto)
    } finally {
        c.disconnect()
    }
}

/** Login con Amazon Cognito. La contraseña NO se guarda; solo el token de renovación, cifrado. */
object Sesion {
    private var idToken: String? = null
    private var expiraEn = 0L
    private val candado = Mutex()

    private suspend fun cognito(operacion: String, cuerpo: JSONObject): Respuesta =
        withContext(Dispatchers.IO) {
            solicitud(
                "POST",
                "https://cognito-idp.${Config.REGION}.amazonaws.com/",
                mapOf(
                    "X-Amz-Target" to "AWSCognitoIdentityProviderService.$operacion",
                    "Content-Type" to "application/x-amz-json-1.1"
                ),
                cuerpo.toString()
            )
        }

    private fun guardarResultado(r: JSONObject) {
        idToken = r.getString("IdToken")
        expiraEn = System.currentTimeMillis() + r.getLong("ExpiresIn") * 1000
        if (r.has("RefreshToken")) Almacen.guardar("refresh", r.getString("RefreshToken"))
    }

    fun haySesionGuardada(): Boolean = Almacen.leer("refresh") != null

    /** Devuelve null si entró bien, o el mensaje de error para mostrar en pantalla. */
    suspend fun iniciar(correo: String, clave: String): String? {
        return try {
            val r = cognito(
                "InitiateAuth",
                JSONObject()
                    .put("AuthFlow", "USER_PASSWORD_AUTH")
                    .put("ClientId", Config.CLIENT_ID)
                    .put("AuthParameters", JSONObject().put("USERNAME", correo).put("PASSWORD", clave))
            )
            val j = JSONObject(r.cuerpo)
            val tipo = j.optString("__type")
            when {
                r.codigo == 200 && j.has("AuthenticationResult") -> {
                    guardarResultado(j.getJSONObject("AuthenticationResult"))
                    Almacen.guardar("correo", correo)
                    null
                }
                r.codigo == 200 -> "Esta cuenta requiere un paso extra de verificación"
                // Mismo mensaje para "usuario no existe" y "clave mala": no se revela cuál falló
                tipo.contains("NotAuthorized") || tipo.contains("UserNotFound") -> "Correo o contraseña incorrectos"
                else -> "No se pudo iniciar sesión (código ${r.codigo})"
            }
        } catch (e: IOException) {
            "Sin conexión. Revisa tu internet."
        } catch (e: Exception) {
            "Error inesperado al iniciar sesión"
        }
    }

    private suspend fun refrescar() {
        val rt = Almacen.leer("refresh") ?: throw SesionExpirada()
        val r = cognito(
            "InitiateAuth",
            JSONObject()
                .put("AuthFlow", "REFRESH_TOKEN_AUTH")
                .put("ClientId", Config.CLIENT_ID)
                .put("AuthParameters", JSONObject().put("REFRESH_TOKEN", rt))
        )
        if (r.codigo == 200) {
            guardarResultado(JSONObject(r.cuerpo).getJSONObject("AuthenticationResult"))
            return
        }
        if (r.codigo == 429 || r.codigo >= 500) throw IOException("Cognito no disponible (${r.codigo})")
        throw SesionExpirada()
    }

    /** Token vigente; si venció (o falta), lo renueva solo. */
    suspend fun token(forzar: Boolean = false): String = candado.withLock {
        if (forzar || idToken == null || System.currentTimeMillis() > expiraEn - 60_000) refrescar()
        idToken ?: throw SesionExpirada()
    }

    fun cerrar() {
        idToken = null
        expiraEn = 0
        Almacen.borrarTodo()
    }
}

/** Llamadas a la API protegida: siempre HTTPS y con el token en la cabecera. */
object Api {
    suspend fun llamar(metodo: String, ruta: String, cuerpo: String? = null): Respuesta =
        withContext(Dispatchers.IO) {
            var r = enviar(metodo, ruta, cuerpo, Sesion.token())
            if (r.codigo == 401) r = enviar(metodo, ruta, cuerpo, Sesion.token(forzar = true))
            if (r.codigo == 401) throw SesionExpirada()
            r
        }

    private fun enviar(metodo: String, ruta: String, cuerpo: String?, token: String): Respuesta =
        solicitud(
            metodo,
            Config.API_URL + ruta,
            mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
            cuerpo
        )
}
