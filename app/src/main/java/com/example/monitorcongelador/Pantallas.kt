package com.example.monitorcongelador

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// Paleta oscura: en pantallas OLED los píxeles oscuros consumen menos batería.
private val Fondo = Color(0xFF0B0F14)
private val Superficie = Color(0xFF141B23)
private val Acento = Color(0xFF4FD1C5)
private val Ok = Color(0xFF4ADE80)
private val Alerta = Color(0xFFFBBF24)
private val Peligro = Color(0xFFF87171)
private val Tenue = Color(0xFF8B98A5)
private val Texto = Color(0xFFE6EDF3)

private val formatoHora = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private val formatoFecha = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
private fun hora(ms: Long): String = formatoHora.format(Date(ms))
private fun horaFila(ms: Long): String =
    if (Estado.rango == 0) formatoHora.format(Date(ms)) else formatoFecha.format(Date(ms))

// Íconos del ojo (mostrar / ocultar contraseña), dibujados aquí para no cargar la librería completa de íconos.
private const val OJO = "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z"

private val OjoAbierto: ImageVector = ImageVector.Builder("ojo", 24.dp, 24.dp, 24f, 24f)
    .addPath(pathData = addPathNodes(OJO), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
    .build()

private val OjoCerrado: ImageVector = ImageVector.Builder("ojo_cerrado", 24.dp, 24.dp, 24f, 24f)
    .addPath(pathData = addPathNodes(OJO), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
    .addPath(pathData = addPathNodes("M3.5,3.5L20.5,20.5"), stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f)
    .build()

@Composable
fun AppTema(contenido: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Acento,
            onPrimary = Fondo,
            background = Fondo,
            onBackground = Texto,
            surface = Superficie,
            onSurface = Texto,
            error = Peligro
        ),
        content = contenido
    )
}

@Composable
fun Raiz() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (Estado.logueado) PantallaMonitor() else PantallaLogin()
        }
    }
}

// ============================================================
//  Login
// ============================================================
@Composable
fun PantallaLogin() {
    var correo by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var verClave by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cargando by remember { mutableStateOf(false) }
    val alcance = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            Text("Monitor de congelador", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Inicia sesión para ver la temperatura",
                color = Tenue,
                modifier = Modifier.padding(top = 4.dp, bottom = 28.dp)
            )
            OutlinedTextField(
                value = correo,
                onValueChange = { correo = it },
                placeholder = { Text("tucorreo@ejemplo.cl", color = Tenue) },
                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = Tenue) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = clave,
                onValueChange = { clave = it },
                placeholder = { Text("Tu contraseña", color = Tenue) },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = Tenue) },
                trailingIcon = {
                    IconButton(onClick = { verClave = !verClave }) {
                        Icon(
                            if (verClave) OjoCerrado else OjoAbierto,
                            contentDescription = if (verClave) "Ocultar contraseña" else "Mostrar contraseña",
                            tint = Tenue
                        )
                    }
                },
                singleLine = true,
                visualTransformation = if (verClave) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            error?.let { Text(it, color = Peligro, modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    cargando = true
                    error = null
                    alcance.launch {
                        error = Monitor.entrar(correo, clave)
                        cargando = false
                        if (error == null) clave = ""
                    }
                },
                enabled = !cargando && correo.isNotBlank() && clave.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                if (cargando) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Fondo, strokeWidth = 2.dp)
                } else {
                    Text("Entrar")
                }
            }
        }
    }
}

// ============================================================
//  Monitoreo (se adapta: una columna en celular, dos en tablet)
// ============================================================
@Composable
fun PantallaMonitor() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 700.dp) {
            Row(
                Modifier.fillMaxSize().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Encabezado()
                    TarjetaActual()
                    PanelComandos()
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TituloLista()
                    LazyColumn(Modifier.weight(1f)) {
                        items(Estado.lecturas, key = { it.ts }) { FilaLectura(it) }
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Encabezado() }
                item { TarjetaActual() }
                item { PanelComandos() }
                item { TituloLista() }
                items(Estado.lecturas, key = { it.ts }) { FilaLectura(it) }
            }
        }
    }
}

@Composable
fun Encabezado() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Congelador 01", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (Estado.enLinea) Ok else Alerta))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (Estado.enLinea) "En línea" else "Sin conexión · datos guardados",
                    color = Tenue,
                    fontSize = 13.sp
                )
            }
        }
        TextButton(onClick = { Monitor.actualizarYa() }) { Text("Actualizar") }
        TextButton(onClick = { Monitor.cerrarSesion() }) { Text("Salir") }
    }
}

private fun diagnostico(l: Lectura?): Pair<Color, String> {
    val t = l?.temp
    return when {
        l == null -> Tenue to "Sin datos todavía"
        !l.sensorOk || t == null -> Peligro to "Falla del sensor"
        t > l.setpoint + 2 -> Alerta to "Sobre el límite"
        else -> Ok to "Temperatura estable"
    }
}

@Composable
fun TarjetaActual() {
    val l = Estado.lecturas.firstOrNull()
    val t = l?.temp
    val (color, estado) = diagnostico(l)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Superficie)
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(estado, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                if (t != null) "%.1f °C".format(t) else "-- °C",
                fontSize = 56.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (l != null) {
                Text(
                    "Objetivo ${"%.0f".format(l.setpoint)} °C · Compresor ${if (l.rele) "encendido" else "apagado"}",
                    color = Tenue
                )
                Text(
                    "Última lectura del sensor: ${hora(l.ts * 1000)}",
                    color = Tenue,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
fun PanelComandos() {
    var objetivo by remember {
        mutableIntStateOf(Estado.lecturas.firstOrNull()?.setpoint?.roundToInt() ?: -18)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Superficie)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Control", fontWeight = FontWeight.SemiBold)

            Text("Temperatura objetivo", color = Tenue, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { objetivo = (objetivo - 1).coerceAtLeast(-40) }) { Text("−") }
                Text(
                    "$objetivo °C",
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { objetivo = (objetivo + 1).coerceAtMost(10) }) { Text("+") }
            }
            Button(
                onClick = { Monitor.enviarComando(JSONObject().put("setpoint", objetivo)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Aplicar") }

            Text("Modo del compresor", color = Tenue, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BotonComando("Auto", "modo", "auto", Modifier.weight(1f))
                BotonComando("Forzar ON", "modo", "forzar_on", Modifier.weight(1f))
                BotonComando("Forzar OFF", "modo", "forzar_off", Modifier.weight(1f))
            }

            Text("Intervalo del sensor", color = Tenue, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BotonComando("10 s", "intervalo", 10, Modifier.weight(1f))
                BotonComando("30 s", "intervalo", 30, Modifier.weight(1f))
                BotonComando("60 s", "intervalo", 60, Modifier.weight(1f))
            }

            Estado.mensaje?.let { Text(it, color = Acento, fontSize = 13.sp) }
            if (Estado.pendientes > 0) {
                Text("${Estado.pendientes} comando(s) pendiente(s) de envío", color = Alerta, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun BotonComando(texto: String, campo: String, valor: Any, modifier: Modifier) {
    OutlinedButton(
        onClick = { Monitor.enviarComando(JSONObject().put(campo, valor)) },
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        Text(texto, fontSize = 13.sp, maxLines = 1)
    }
}

// ============================================================
//  Registros: selector de rango + resumen + lista
// ============================================================
private val RANGOS = listOf("Actual", "24 h", "7 días", "30 días")

@Composable
fun TituloLista() {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Registros", color = Tenue, fontSize = 14.sp)
        SelectorRango()
        ResumenRango()
    }
}

@Composable
fun SelectorRango() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RANGOS.forEachIndexed { i, nombre ->
            val m = Modifier.weight(1f)
            if (Estado.rango == i) {
                Button(onClick = {}, modifier = m, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(nombre, fontSize = 13.sp, maxLines = 1)
                }
            } else {
                OutlinedButton(
                    onClick = { Monitor.elegirRango(i) },
                    modifier = m,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(nombre, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun ResumenRango() {
    val r = Estado.resumen
    if (Estado.cargandoRango) {
        Text("Cargando…", color = Tenue, fontSize = 13.sp)
    } else if (r != null) {
        Text(
            "Mín ${"%.1f".format(r.min)} · Máx ${"%.1f".format(r.max)} · Prom ${"%.1f".format(r.prom)} °C · ${r.total} lecturas",
            color = Tenue,
            fontSize = 13.sp
        )
    }
    if (Estado.rango != 0 && !Estado.enLinea) {
        Text("Sin conexión: el historial no se pudo actualizar", color = Alerta, fontSize = 13.sp)
    }
}

@Composable
fun FilaLectura(l: Lectura) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(horaFila(l.ts * 1000), color = Tenue, modifier = Modifier.weight(1f))
        Text(l.temp?.let { "%.1f °C".format(it) } ?: "sin dato", modifier = Modifier.weight(1f))
        Text(
            if (l.rele) "Compresor ON" else "Compresor OFF",
            color = if (l.rele) Acento else Tenue,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}
