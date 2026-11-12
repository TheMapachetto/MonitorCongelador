package com.example.monitorcongelador

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda datos en el teléfono SIEMPRE cifrados (AES-256-GCM).
 * La clave vive dentro del Android Keystore: la app puede usarla, pero nadie puede extraerla.
 */
object Almacen {
    private const val ALIAS = "monitor_congelador_aes"
    private lateinit var prefs: SharedPreferences

    fun init(contexto: Context) {
        if (!::prefs.isInitialized) {
            prefs = contexto.getSharedPreferences("datos_cifrados", Context.MODE_PRIVATE)
        }
    }

    private fun clave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existente = ks.getKey(ALIAS, null)
        if (existente is SecretKey) return existente

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun cifrar(texto: String): String {
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
        cifrador.init(Cipher.ENCRYPT_MODE, clave())
        val cifrado = cifrador.doFinal(texto.toByteArray(Charsets.UTF_8))
        // Se guarda: IV (12 bytes) + texto cifrado
        return Base64.encodeToString(cifrador.iv + cifrado, Base64.NO_WRAP)
    }

    private fun descifrar(b64: String): String? = try {
        val datos = Base64.decode(b64, Base64.NO_WRAP)
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
        cifrador.init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(128, datos.copyOfRange(0, 12)))
        String(cifrador.doFinal(datos, 12, datos.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun guardar(nombre: String, valor: String) {
        prefs.edit().putString(nombre, cifrar(valor)).apply()
    }

    fun leer(nombre: String): String? = prefs.getString(nombre, null)?.let { descifrar(it) }

    fun borrar(nombre: String) {
        prefs.edit().remove(nombre).apply()
    }

    fun borrarTodo() {
        prefs.edit().clear().apply()
    }
}
