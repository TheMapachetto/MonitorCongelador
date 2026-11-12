package com.example.monitorcongelador

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class Lectura(
    val ts: Long,
    val temp: Double?,
    val rele: Boolean,
    val setpoint: Double,
    val sensorOk: Boolean
)

data class Resumen(val min: Double, val max: Double, val prom: Double, val total: Int)

/** Lo que muestra la pantalla. Cuando algo cambia aquí, la UI se redibuja sola. */
object Estado {
    var logueado by mutableStateOf(false)
    var lecturas by mutableStateOf<List<Lectura>>(emptyList())
    var resumen by mutableStateOf<Resumen?>(null)
    var rango by mutableIntStateOf(0)            // 0 = actual, 1 = 24 h, 2 = 7 días, 3 = 30 días
    var cargandoRango by mutableStateOf(false)
    var enLinea by mutableStateOf(true)
    var ultimaSync by mutableLongStateOf(0L)
    var pendientes by mutableIntStateOf(0)
    var mensaje by mutableStateOf<String?>(null)
}

private class Datos(val lecturas: List<Lectura>, val resumen: Resumen?)

/** Lee la respuesta de la API (también el formato antiguo, que era solo una lista). */
private fun leerDatos(texto: String): Datos {
    val t = texto.trim()
    val arr: JSONArray
    var resumen: Resumen? = null
    if (t.startsWith("[")) {
        arr = JSONArray(t)
    } else {
        val o = JSONObject(t)
        arr = o.getJSONArray("items")
        val r = o.optJSONObject("resumen")
        if (r != null && r.has("min")) {
            resumen = Resumen(r.getDouble("min"), r.getDouble("max"), r.getDouble("prom"), o.optInt("total", arr.length()))
        }
    }
    val lista = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        Lectura(
            ts = o.getLong("ts"),
            temp = if (o.has("temp") && !o.isNull("temp")) o.getDouble("temp") else null,
            rele = o.optBoolean("rele", false),
            setpoint = o.optDouble("setpoint", 0.0),
            sensorOk = o.optBoolean("sensorOk", true)
        )
    }
    return Datos(lista, resumen)
}

object Monitor {
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trabajo: Job? = null
    private val colaCandado = Mutex()
    private var visible = false

    // ---------- Arranque y ciclo de vida ----------

    /** Recupera la sesión y los últimos datos guardados (para mostrar algo aun sin internet). */
    fun restaurar() {
        Estado.logueado = Sesion.haySesionGuardada()
        if (Estado.logueado) {
            Almacen.leer("cache")?.let { runCatching { Estado.lecturas = leerDatos(it).lecturas } }
            Estado.ultimaSync = Almacen.leer("cache_ts")?.toLongOrNull() ?: 0L
            Estado.pendientes = leerCola().size
        }
    }

    // Solo se consulta con la app a la vista: en segundo plano no hay red ni CPU gastada.
    fun alVisible() { visible = true; arrancar() }

    fun alOculto() {
        visible = false
        trabajo?.cancel()
        trabajo = null
    }

    private fun arrancar() {
        if (!visible || !Estado.logueado || trabajo?.isActive == true) return
        trabajo = alcance.launch {
            var espera = Config.INTERVALO_MS
            while (isActive) {
                // Solo la vista "Actual" se refresca sola; el historial se consulta al elegirlo.
                // Reconexión automática: si falla, espera más cada vez (30 s, 60 s...) y reintenta.
                espera = if (actualizar(Estado.rango == 0)) Config.INTERVALO_MS else minOf(espera * 2, Config.ESPERA_MAX_MS)
                delay(espera)
            }
        }
    }

    // ---------- Sesión ----------

    suspend fun entrar(correo: String, clave: String): String? {
        val error = Sesion.iniciar(correo.trim(), clave)
        if (error == null) {
            Estado.logueado = true
            arrancar()
        }
        return error
    }

    fun cerrarSesion() {
        trabajo?.cancel()
        trabajo = null
        Sesion.cerrar()
        Estado.logueado = false
        Estado.lecturas = emptyList()
        Estado.resumen = null
        Estado.rango = 0
        Estado.pendientes = 0
        Estado.mensaje = null
        Estado.ultimaSync = 0L
    }

    // ---------- Recibir datos ----------

    private fun rutaLecturas(): String = when (Estado.rango) {
        1 -> "/lecturas?horas=24"
        2 -> "/lecturas?horas=168"
        3 -> "/lecturas?horas=720"
        else -> "/lecturas?n=${Config.LECTURAS}"
    }

    fun actualizarYa() {
        alcance.launch { actualizar(true) }
    }

    fun elegirRango(indice: Int) {
        if (indice == Estado.rango) return
        Estado.rango = indice
        Estado.resumen = null
        alcance.launch {
            Estado.cargandoRango = true
            actualizar(true)
            Estado.cargandoRango = false
        }
    }

    /** Devuelve true si todo salió bien. Con consultar = false solo envía los comandos pendientes. */
    private suspend fun actualizar(consultar: Boolean): Boolean {
        return try {
            enviarPendientes()
            if (consultar) {
                val rangoPedido = Estado.rango
                val r = Api.llamar("GET", rutaLecturas())
                if (r.codigo !in 200..299) throw IOException("HTTP ${r.codigo}")
                val datos = leerDatos(r.cuerpo)
                if (rangoPedido == Estado.rango) {      // por si cambió de rango mientras cargaba
                    Estado.lecturas = datos.lecturas
                    Estado.resumen = datos.resumen
                }
                Estado.enLinea = true
                Estado.ultimaSync = System.currentTimeMillis()
                if (rangoPedido == 0) {                 // solo la vista "Actual" se guarda para uso sin conexión
                    Almacen.guardar("cache", r.cuerpo)
                    Almacen.guardar("cache_ts", Estado.ultimaSync.toString())
                }
            }
            true
        } catch (e: SesionExpirada) {
            cerrarSesion()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Estado.enLinea = false
            false
        }
    }

    // ---------- Enviar comandos (con cola para cuando no hay conexión) ----------

    private fun leerCola(): List<JSONObject> {
        val texto = Almacen.leer("cola") ?: return emptyList()
        return runCatching {
            val a = JSONArray(texto)
            List(a.length()) { a.getJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    private fun escribirCola(cola: List<JSONObject>) {
        if (cola.isEmpty()) Almacen.borrar("cola") else Almacen.guardar("cola", JSONArray(cola).toString())
        Estado.pendientes = cola.size
    }

    /** Manda los comandos guardados, en orden. Si no hay red, quedan en la cola. */
    private suspend fun enviarPendientes() = colaCandado.withLock {
        var cola = leerCola()
        while (cola.isNotEmpty()) {
            val r = Api.llamar("POST", "/comando", cola.first().toString())
            if (r.codigo in 200..299 || r.codigo == 400) {   // 400 = comando inválido: se descarta
                cola = cola.drop(1)
                escribirCola(cola)
            } else {
                throw IOException("HTTP ${r.codigo}")
            }
        }
    }

    fun enviarComando(cmd: JSONObject) {
        alcance.launch {
            Estado.mensaje = null
            colaCandado.withLock { escribirCola((leerCola() + cmd).takeLast(10)) }
            try {
                enviarPendientes()
                Estado.enLinea = true
                Estado.mensaje = "Comando enviado"
            } catch (e: SesionExpirada) {
                cerrarSesion()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Estado.enLinea = false
                Estado.mensaje = "Sin conexión: el comando se enviará al reconectar"
            }
        }
    }
}
