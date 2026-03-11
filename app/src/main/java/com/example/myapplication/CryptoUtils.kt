package com.example.myapplication

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.security.MessageDigest

object CryptoUtils {

    private val secret = MessageDigest
        .getInstance("SHA-256")
        .digest("CHANGE_THIS_TO_LONG_RANDOM_SECRET_32_BYTES".toByteArray())

    fun decrypt(payload: String): String {

        return try {

            val buffer = Base64.decode(payload, Base64.NO_WRAP)

            if (buffer.size < 28) return payload

            val iv = buffer.copyOfRange(0, 12)
            val tag = buffer.copyOfRange(12, 28)
            val cipherText = buffer.copyOfRange(28, buffer.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            val key = SecretKeySpec(secret, "AES")

            val combined = cipherText + tag

            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, iv)
            )

            val decrypted = cipher.doFinal(combined)

            String(decrypted)

        } catch (e: Exception) {
            payload
        }
    }
}