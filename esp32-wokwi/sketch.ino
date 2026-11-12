/*
  Sistema IoT de monitoreo y control termico para cadena de frio
  ---------------------------------------------------------------
  Un unico ESP32 (Wokwi) que cumple los dos roles del diseno original:
    - Nodo sensor:   lee el DS18B20 y publica por MQTT
    - Nodo actuador: controla el rele y muestra el estado en el LCD
  El "broker" ya no es un ESP32: lo cumple AWS IoT Core.

  Flujo:
    DS18B20 -> ESP32 --MQTT/TLS (8883)--> AWS IoT Core --Regla--> DynamoDB
                     <--MQTT/TLS-- comandos (topic .../cmd)

  Topics (THING_NAME = congelador-01):
    congelador/<thing>/telemetria   (publica)   lecturas
    congelador/<thing>/estado       (publica)   online/offline + configuracion
    congelador/<thing>/cmd          (suscrito)  comandos JSON

  Comandos (tambien se pueden escribir por el Monitor Serial):
    {"setpoint": -20}
    {"modo": "auto"}   |  {"modo": "forzar_on"}  |  {"modo": "forzar_off"}
    {"intervalo": 15}   (segundos entre lecturas, 2 a 300)
*/

#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <time.h>
#include "secrets.h"

// ---------------- Pines ----------------
const uint8_t PIN_SENSOR = 4;
const uint8_t PIN_RELE   = 16;
const uint8_t PIN_SDA    = 21;
const uint8_t PIN_SCL    = 22;
const bool RELE_ACTIVO_ALTO = true;   // si tu modulo real es activo en bajo, cambiar a false

// ---------------- Parametros de control ----------------
float    setpoint    = -18.0;         // temperatura objetivo (C)
const float HISTERESIS = 1.0;         // evita encender/apagar el compresor todo el rato
uint32_t intervaloMs = 10000;         // intervalo base entre lecturas

enum Modo { AUTO, FORZAR_ON, FORZAR_OFF };
Modo modo = AUTO;

// ---------------- Objetos ----------------
OneWire oneWire(PIN_SENSOR);
DallasTemperature sensores(&oneWire);
LiquidCrystal_I2C lcd(0x27, 16, 2);
WiFiClientSecure red;
PubSubClient mqtt(red);

char topicTelemetria[64], topicEstado[64], topicCmd[64];

// ---------------- Estado ----------------
bool     releOn = false;
float    ultimaTemp = NAN;
bool     ultimoSensorOk = false;
uint32_t seq = 0;                     // numero de secuencia: si falta alguno, se perdieron datos
uint32_t perdidas = 0;                // lecturas descartadas por buffer lleno
uint32_t ultimaLectura = 0, intervaloActual = 0, ultimaPantalla = 0;
uint32_t ultimoIntentoMqtt = 0, esperaMqtt = 0;
bool     ntpIniciado = false;
const char* estadoRed = "NO-WIFI";
const time_t EPOCH_VALIDO = 1700000000;   // si la hora es menor, el NTP aun no sincroniza

// ---------------- Buffer circular (almacenamiento temporal sin conexion) ----------------
struct Lectura {
  uint32_t up;        // segundos desde el arranque en que se tomo la lectura
  float    temp;
  float    sp;
  bool     rele;
  bool     sensorOk;
  uint32_t seq;
};
const uint8_t BUF_MAX = 60;
Lectura  buf[BUF_MAX];
uint8_t  bufInicio = 0, bufCantidad = 0;

void bufAgregar(const Lectura& l) {
  if (bufCantidad == BUF_MAX) {       // lleno: se descarta la mas antigua
    bufInicio = (bufInicio + 1) % BUF_MAX;
    bufCantidad--;
    perdidas++;
  }
  buf[(bufInicio + bufCantidad) % BUF_MAX] = l;
  bufCantidad++;
}

// ============================================================
//  MQTT
// ============================================================
void publicarEstado(const char* estado) {
  JsonDocument doc;
  doc["estado"] = estado;
  doc["setpoint"] = setpoint;
  doc["modo"] = (modo == AUTO) ? "auto" : (modo == FORZAR_ON ? "forzar_on" : "forzar_off");
  doc["intervalo"] = intervaloMs / 1000;
  doc["perdidas"] = perdidas;
  char out[200];
  serializeJson(doc, out, sizeof(out));
  mqtt.publish(topicEstado, out);
}

bool publicarLectura(const Lectura& l) {
  time_t ahora = time(nullptr);
  if (ahora < EPOCH_VALIDO) return false;
  // La hora real se calcula al enviar: asi una lectura guardada offline
  // llega a DynamoDB con la hora en que realmente se midio.
  uint32_t ts = (uint32_t)ahora - (millis() / 1000 - l.up);

  JsonDocument doc;
  doc["deviceId"] = THING_NAME;
  doc["ts"] = ts;
  doc["seq"] = l.seq;
  doc["sensorOk"] = l.sensorOk;
  if (l.sensorOk) doc["temp"] = roundf(l.temp * 100) / 100;
  doc["setpoint"] = l.sp;
  doc["rele"] = l.rele;
  doc["rssi"] = WiFi.RSSI();

  char out[256];
  size_t n = serializeJson(doc, out, sizeof(out));
  if (n == 0) return false;
  return mqtt.publish(topicTelemetria, out);
}

void vaciarBuffer() {
  if (!mqtt.connected()) return;
  uint8_t enviados = 0;
  while (bufCantidad > 0 && enviados < 5) {      // de a poco para no bloquear el control
    if (!publicarLectura(buf[bufInicio])) break;
    bufInicio = (bufInicio + 1) % BUF_MAX;
    bufCantidad--;
    enviados++;
  }
}

void procesarComando(JsonDocument& doc) {
  if (doc["setpoint"].is<float>()) {
    float v = doc["setpoint"];
    if (v >= -40 && v <= 10) { setpoint = v; Serial.printf("CMD: setpoint = %.1f\n", v); }
    else Serial.println("CMD rechazado: setpoint fuera de rango (-40 a 10)");
  }
  if (doc["modo"].is<const char*>()) {
    const char* m = doc["modo"];
    if (strcmp(m, "auto") == 0)           modo = AUTO;
    else if (strcmp(m, "forzar_on") == 0) modo = FORZAR_ON;
    else if (strcmp(m, "forzar_off") == 0) modo = FORZAR_OFF;
    else Serial.println("CMD rechazado: modo desconocido");
    Serial.printf("CMD: modo = %s\n", m);
  }
  if (doc["intervalo"].is<int>()) {
    int s = doc["intervalo"];
    if (s >= 2 && s <= 300) { intervaloMs = s * 1000UL; Serial.printf("CMD: intervalo = %d s\n", s); }
    else Serial.println("CMD rechazado: intervalo fuera de rango (2 a 300)");
  }
  intervaloActual = 0;                 // mide ya, para que el cambio se vea de inmediato
  if (mqtt.connected()) publicarEstado("online");   // confirmacion (ack) del comando
}

void onMensaje(char* topic, byte* payload, unsigned int len) {
  JsonDocument doc;
  DeserializationError e = deserializeJson(doc, payload, len);
  if (e) { Serial.printf("MQTT: JSON invalido (%s)\n", e.c_str()); return; }
  Serial.printf("MQTT <- %s\n", topic);
  procesarComando(doc);
}

bool conectarMqtt() {
  Serial.print("MQTT: conectando a AWS IoT... ");
  // Last Will: si el ESP32 se cae sin avisar, AWS publica "offline" por nosotros
  if (mqtt.connect(THING_NAME, NULL, NULL, topicEstado, 1, false, "{\"estado\":\"offline\"}")) {
    Serial.println("OK");
    mqtt.subscribe(topicCmd, 1);
    publicarEstado("online");
    esperaMqtt = 0;
    return true;
  }
  Serial.printf("fallo (rc=%d)\n", mqtt.state());
  esperaMqtt = (esperaMqtt == 0) ? 2000 : min<uint32_t>(esperaMqtt * 2, 60000);  // backoff exponencial
  return false;
}

// Reconexion automatica, sin bloquear el control del rele
void gestionarRed() {
  if (WiFi.status() != WL_CONNECTED) { estadoRed = "NO-WIFI"; return; }  // el driver reintenta solo
  if (!ntpIniciado) {
    configTime(0, 0, "pool.ntp.org", "time.google.com");   // TLS necesita hora valida
    ntpIniciado = true;
  }
  if (time(nullptr) < EPOCH_VALIDO) { estadoRed = "SIN-HORA"; return; }
  if (mqtt.connected()) { estadoRed = "ONLINE"; return; }
  estadoRed = "OFFLINE";
  if (millis() - ultimoIntentoMqtt >= esperaMqtt) {
    ultimoIntentoMqtt = millis();
    conectarMqtt();
  }
}

// ============================================================
//  Control y sensor
// ============================================================
void controlarRele(float t, bool sensorOk) {
  bool destino = releOn;
  if (!sensorOk)                destino = true;    // falla de sensor: modo seguro, sigue enfriando
  else if (modo == FORZAR_ON)   destino = true;
  else if (modo == FORZAR_OFF)  destino = false;
  else {                                           // AUTO con histeresis
    if (t >= setpoint + HISTERESIS)      destino = true;
    else if (t <= setpoint - HISTERESIS) destino = false;
  }
  releOn = destino;
  bool nivel = RELE_ACTIVO_ALTO ? releOn : !releOn;
  digitalWrite(PIN_RELE, nivel ? HIGH : LOW);
}

uint32_t calcularIntervalo(bool ok, float t) {
  // Muy por debajo del setpoint y estable: se mide menos para ahorrar energia.
  if (ok && t <= setpoint - HISTERESIS - 2) return intervaloMs * 3;
  return intervaloMs;
}

void medir() {
  sensores.requestTemperatures();
  float t = sensores.getTempCByIndex(0);
  bool ok = (t != DEVICE_DISCONNECTED_C) && t > -55 && t < 125;

  controlarRele(t, ok);
  ultimaTemp = ok ? t : NAN;
  ultimoSensorOk = ok;

  Lectura l = { millis() / 1000, ok ? t : NAN, setpoint, releOn, ok, ++seq };
  Serial.printf("[#%lu] T=%.2f C | SP=%.1f | RELE=%s | RED=%s | BUF=%u\n",
                (unsigned long)l.seq, ok ? t : NAN, setpoint, releOn ? "ON" : "OFF",
                estadoRed, bufCantidad);

  // Si hay datos pendientes se encola igual, para mantener el orden
  if (mqtt.connected() && bufCantidad == 0) {
    if (!publicarLectura(l)) bufAgregar(l);
  } else {
    bufAgregar(l);
  }
  intervaloActual = calcularIntervalo(ok, t);
}

void actualizarPantalla() {
  char l1[17], l2[17];
  if (ultimoSensorOk) snprintf(l1, sizeof(l1), "T:%5.1fC SP:%3.0f ", ultimaTemp, setpoint);
  else                snprintf(l1, sizeof(l1), "SENSOR FALLA!   ");
  snprintf(l2, sizeof(l2), "R:%-3s %-7s %2u", releOn ? "ON" : "OFF", estadoRed, bufCantidad);
  lcd.setCursor(0, 0); lcd.print(l1);
  lcd.setCursor(0, 1); lcd.print(l2);
}

// Comandos por Serial: el mismo JSON que por MQTT
void leerSerial() {
  static String linea;
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (linea.length()) {
        JsonDocument doc;
        if (!deserializeJson(doc, linea)) procesarComando(doc);
        else Serial.println("Serial: JSON invalido");
        linea = "";
      }
    } else if (linea.length() < 120) {
      linea += c;
    }
  }
}

// ============================================================
void setup() {
  Serial.begin(115200);
  setCpuFrequencyMhz(80);              // ahorro de energia: 80 MHz alcanza para WiFi + control

  pinMode(PIN_RELE, OUTPUT);
  Wire.begin(PIN_SDA, PIN_SCL);
  lcd.init();
  lcd.backlight();
  lcd.setCursor(0, 0); lcd.print("Iniciando...    ");
  sensores.begin();

  snprintf(topicTelemetria, sizeof(topicTelemetria), "congelador/%s/telemetria", THING_NAME);
  snprintf(topicEstado,     sizeof(topicEstado),     "congelador/%s/estado",     THING_NAME);
  snprintf(topicCmd,        sizeof(topicCmd),        "congelador/%s/cmd",        THING_NAME);

  WiFi.mode(WIFI_STA);
  WiFi.setSleep(true);                 // modem sleep: la radio duerme entre balizas
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASS, WIFI_CANAL);

  // TLS con autenticacion mutua (el servidor y el dispositivo se validan entre si)
  red.setCACert(AWS_CERT_CA);
  red.setCertificate(AWS_CERT_CRT);
  red.setPrivateKey(AWS_CERT_PRIVATE);
  mqtt.setServer(AWS_IOT_ENDPOINT, 8883);
  mqtt.setCallback(onMensaje);
  mqtt.setBufferSize(512);
  mqtt.setKeepAlive(30);
  mqtt.setSocketTimeout(5);

  Serial.println("Sistema listo. Esperando WiFi...");
}

void loop() {
  gestionarRed();
  if (mqtt.connected()) mqtt.loop();
  leerSerial();

  uint32_t ahora = millis();
  if (ahora - ultimaLectura >= intervaloActual) {
    ultimaLectura = ahora;
    medir();
  }
  vaciarBuffer();

  if (ahora - ultimaPantalla >= 1000) {
    ultimaPantalla = ahora;
    actualizarPantalla();
  }
}
