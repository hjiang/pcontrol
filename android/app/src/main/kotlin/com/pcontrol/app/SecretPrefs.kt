package com.pcontrol.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted wrapper around server URL and device token.
 *
 * Uses [EncryptedSharedPreferences] (AES-256 GCM) so the bearer token is
 * encrypted at rest. Policy version is non-secret and stored in a separate
 * plain preferences file.
 */
class SecretPrefs private constructor(
    private val encrypted: SharedPreferences
) {
    companion object {
        private const val ENCRYPTED_NAME = "pcontrol_encrypted"

        @Volatile
        private var instance: SecretPrefs? = null

        /**
         * Returns the singleton [SecretPrefs]. The master key is generated
         * once (AES256_GCM) and stored in the Android Keystore.
         */
        fun getInstance(context: Context): SecretPrefs {
            return instance ?: synchronized(this) {
                instance ?: buildInstance(context).also { instance = it }
            }
        }

        private fun buildInstance(context: Context): SecretPrefs {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val encrypted = EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            return SecretPrefs(encrypted)
        }
    }

    fun getServerUrl(): String = encrypted.getString(KEY_SERVER_URL, "") ?: ""
    fun setServerUrl(url: String) = encrypted.edit().putString(KEY_SERVER_URL, url).apply()

    fun getDeviceToken(): String = encrypted.getString(KEY_DEVICE_TOKEN, "") ?: ""
    fun setDeviceToken(token: String) = encrypted.edit().putString(KEY_DEVICE_TOKEN, token).apply()

    /** True when both server URL and device token are set. */
    fun isConfigured(): Boolean = getServerUrl().isNotEmpty() && getDeviceToken().isNotEmpty()

    private companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_TOKEN = "device_token"
    }
}
