package com.example.authanticator.backup

import com.example.authanticator.security.Aead
import java.nio.ByteBuffer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object BackupCodec {
    private val magic = byteArrayOf(0x56, 0x41, 0x55, 0x4c, 0x54, 0x01)
    const val ITERATIONS = 600_000
    private const val HEADER_SIZE = 26

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        require(password.size in 16..256) { "Use 16 to 256 characters, preferably six or more random words." }
        require(plaintext.size <= Aead.MAX_BYTES - HEADER_SIZE - 28) { "Backup exceeds 4 MiB." }
        val salt = Aead.random(16)
        val header = ByteBuffer.allocate(HEADER_SIZE).put(magic).putInt(ITERATIONS).put(salt).array()
        val key = derive(password, salt)
        return try { header + Aead.seal(Aead.key(key), plaintext, header) } finally { key.fill(0) }
    }

    fun decrypt(encrypted: ByteArray, password: CharArray): ByteArray {
        require(password.size in 16..256) { "Enter the original passphrase or recovery key." }
        require(encrypted.size in (HEADER_SIZE + 28)..Aead.MAX_BYTES) { "Invalid backup size." }
        val header = encrypted.copyOfRange(0, HEADER_SIZE)
        require(header.copyOfRange(0, 6).contentEquals(magic)) { "Unsupported backup format or version." }
        require(ByteBuffer.wrap(header, 6, 4).int == ITERATIONS) { "Unsupported backup key derivation." }
        val key = derive(password, header.copyOfRange(10, 26))
        return try { Aead.open(Aead.key(key), encrypted.copyOfRange(HEADER_SIZE, encrypted.size), header) }
        finally { key.fill(0) }
    }

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val specification = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded }
        finally { specification.clearPassword() }
    }
}