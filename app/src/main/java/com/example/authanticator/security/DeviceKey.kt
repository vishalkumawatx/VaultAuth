package com.example.authanticator.security

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AuthTicket internal constructor(
    val cipher: Cipher,
    internal val newKey: ByteArray?,
    internal val wrappedKey: ByteArray?
) : AutoCloseable {
    private val consumed = AtomicBoolean(false)

    internal fun complete(authenticatedCipher: Cipher): ByteArray {
        if (authenticatedCipher !== cipher) throw DeviceKeyFailure(UnlockIssue.OPERATION_LOST)
        if (!consumed.compareAndSet(false, true)) throw DeviceKeyFailure(UnlockIssue.AUTH_EXPIRED)
        authenticatedCipher.updateAAD("VaultAuth/device-key/v1".toByteArray())
        return if (newKey != null) authenticatedCipher.doFinal(newKey) else {
            val wrapped = requireNotNull(wrappedKey)
            authenticatedCipher.doFinal(wrapped, 12, wrapped.size - 12)
        }
    }

    override fun close() {
        consumed.set(true)
        newKey?.fill(0)
    }
}

class DeviceKey(private val context: Context, private val alias: String = "vault-auth.wrapper.v1") {
    private val wrapper = AtomicFile(File(context.noBackupFilesDir, "device-key.v1"))

    fun prepare(): AuthTicket {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val wrapped = if (wrapper.baseFile.exists()) {
            if (wrapper.baseFile.length() != 60L) throw DeviceKeyFailure(UnlockIssue.RECORD_DAMAGED)
            wrapper.readFully()
        } else null
        if (wrapped == null && File(context.noBackupFilesDir, "vault.v1").exists()) throw DeviceKeyFailure(UnlockIssue.KEY_MISSING)
        if (!store.containsAlias(alias)) {
            if (wrapped != null) throw DeviceKeyFailure(UnlockIssue.KEY_MISSING)
            generate(context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE))
        }
        val key = store.getKey(alias, null) as? SecretKey ?: throw DeviceKeyFailure(UnlockIssue.KEY_MISSING)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        return if (wrapped == null) {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            AuthTicket(cipher, Aead.random(32), null)
        } else {
            if (wrapped.size != 60) throw DeviceKeyFailure(UnlockIssue.RECORD_DAMAGED)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, 12))
            AuthTicket(cipher, null, wrapped)
        }
    }

    fun finish(ticket: AuthTicket, authenticatedCipher: Cipher): ByteArray = try {
        if (ticket.newKey != null) {
            val encrypted = authenticatedCipher.iv + ticket.complete(authenticatedCipher)
            atomicWrite(wrapper, encrypted)
            ticket.newKey.copyOf()
        } else {
            ticket.complete(authenticatedCipher).also { require(it.size == 32) }
        }
    } finally { ticket.close() }

    private fun generate(strongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setIsStrongBoxBacked(strongBox)
        try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(builder.build())
                generateKey()
            }
        } catch (error: StrongBoxUnavailableException) {
            if (!strongBox) throw error
            generate(false)
        }
    }
}

internal fun atomicWrite(file: AtomicFile, bytes: ByteArray) {
    val stream = file.startWrite()
    try {
        stream.write(bytes)
        file.finishWrite(stream)
    } catch (error: Exception) {
        file.failWrite(stream)
        throw error
    }
}