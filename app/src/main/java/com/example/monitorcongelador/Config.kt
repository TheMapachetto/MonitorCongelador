package com.example.monitorcongelador

object Config {
    const val REGION = "us-east-1"
    const val CLIENT_ID = "1pm4gaht3f1e8u3buvuvb8qbk0"
    const val API_URL = "https://egadyy4kwi.execute-api.us-east-1.amazonaws.com"

    const val INTERVALO_MS = 15_000L      // cada cuánto consulta la app (solo con la pantalla visible)
    const val ESPERA_MAX_MS = 60_000L     // tope de espera al reintentar sin conexión
    const val LECTURAS = 20               // cuántas lecturas muestra
}
