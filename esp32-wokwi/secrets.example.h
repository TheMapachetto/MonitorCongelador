#pragma once
// ============================================================
// secrets.example.h  -  PLANTILLA (sin credenciales reales)
// Copiar como secrets.h y rellenar. El secrets.h real NUNCA se sube a GitHub.
// En el repositorio subir una copia con los valores de ejemplo
// (secrets.example.h) y sin la clave privada real.
// ============================================================

// WiFi simulado de Wokwi (no lleva contrasena). El canal 6 acelera la conexion.
#define WIFI_SSID  "Wokwi-GUEST"
#define WIFI_PASS  ""
#define WIFI_CANAL 6

// Nombre del Thing creado en AWS IoT Core (tambien se usa como Client ID y deviceId)
#define THING_NAME "congelador-01"

// AWS IoT Core > Configuracion > Punto de enlace de datos del dispositivo
#define AWS_IOT_ENDPOINT "REEMPLAZAR-ats.iot.us-east-1.amazonaws.com"

// 1) Certificado raiz: Amazon Root CA 1 (https://www.amazontrust.com/repository/AmazonRootCA1.pem)
static const char AWS_CERT_CA[] PROGMEM = R"EOF(
-----BEGIN CERTIFICATE-----
PEGAR_AQUI_EL_CONTENIDO_DE_AmazonRootCA1.pem
-----END CERTIFICATE-----
)EOF";

// 2) Certificado del dispositivo (xxxx-certificate.pem.crt)
static const char AWS_CERT_CRT[] PROGMEM = R"EOF(
-----BEGIN CERTIFICATE-----
PEGAR_AQUI_EL_CERTIFICADO_DEL_DISPOSITIVO
-----END CERTIFICATE-----
)EOF";

// 3) Clave privada (xxxx-private.pem.key)
static const char AWS_CERT_PRIVATE[] PROGMEM = R"EOF(
-----BEGIN RSA PRIVATE KEY-----
PEGAR_AQUI_LA_CLAVE_PRIVADA
-----END RSA PRIVATE KEY-----
)EOF";
