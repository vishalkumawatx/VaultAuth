package com.example.authanticator.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Aead {
    const val MAX_BYTES = 4 * 1024 * 1024
    fun random(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
    fun key(bytes: ByteArray): SecretKey {
        require(bytes.size == 32) { "Invalid vault key." }
        return SecretKeySpec(bytes, "AES")
    }

    fun seal(key: SecretKey, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(plaintext.size <= MAX_BYTES - 28) { "Vault size limit reached." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        check(cipher.iv.size == 12)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    fun open(key: SecretKey, envelope: ByteArray, aad: ByteArray): ByteArray {
        require(envelope.size in 28..MAX_BYTES) { "Invalid encrypted record size." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope, 0, 12))
        cipher.updateAAD(aad)
        return cipher.doFinal(envelope, 12, envelope.size - 12)
    }
}