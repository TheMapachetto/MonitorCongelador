package com.example.monitorcongelador

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Almacen.init(applicationContext)
        Monitor.restaurar()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent { AppTema { Raiz() } }
    }

    override fun onStart() {
        super.onStart()
        Monitor.alVisible()
    }

    override fun onStop() {
        super.onStop()
        Monitor.alOculto()
    }
}
