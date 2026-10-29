package com.example.idautble

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional per-device storage. PIN values are encrypted with a non-exportable Android Keystore key.
 */
class PlcCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("plc_credentials", Context.MODE_PRIVATE)
    private val alias = "idaut.plc.credentials.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    fun load(address: String): String? =
        try {
            prefs.getString(address, null)?.let { encoded ->
                val parts = encoded.split(':', limit = 2)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
                )
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
                    .takeIf(::validMaintenancePin)
            }
        } catch (_: Exception) {
            remove(address)
            null
        }

    fun save(address: String, pin: String) {
        require(validMaintenancePin(pin))
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val bytes =
            Base64.encodeToString(cipher.doFinal(pin.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString(address, "$iv:$bytes").apply()
    }

    fun remove(address: String) {
        prefs.edit().remove(address).apply()
    }
}
