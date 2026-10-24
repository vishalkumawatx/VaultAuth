package com.example.authanticator

import com.example.authanticator.data.AccountMetadata
import com.example.authanticator.data.VaultCodec
import com.example.authanticator.data.VaultRecord
import com.example.authanticator.security.Aead
import com.example.authanticator.security.AuthTicket
import com.example.authanticator.security.DeviceKeyFailure
import com.example.authanticator.security.UnlockIssue
import com.example.authanticator.security.UnlockProblem
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.*
import org.junit.Test

class SecurityTest {
    @Test fun unlockMessagesDistinguishRetryFromRecoveryWithoutLeakingErrors() {
        val unknown = UnlockProblem.from(IllegalStateException("sensitive provider details"))
        assertEquals(UnlockIssue.UNKNOWN, unknown.issue)
        assertTrue(unknown.canRetry)
        assertFalse(unknown.message.contains("sensitive provider details"))
        assertFalse(unknown.message.contains("backup"))
        val expired = UnlockProblem.from(DeviceKeyFailure(UnlockIssue.AUTH_EXPIRED))
        assertTrue(expired.canRetry)
        assertFalse(expired.message.contains("backup"))
        val missing = UnlockProblem.from(DeviceKeyFailure(UnlockIssue.KEY_MISSING))
        assertFalse(missing.canRetry)
        assertTrue(missing.message.contains("Do not uninstall"))
        assertEquals(UnlockIssue.STORAGE, UnlockProblem.from(java.io.IOException()).issue)
        assertEquals(UnlockIssue.RECORD_DAMAGED, UnlockProblem.from(AEADBadTagException()).issue)
    }

    @Test fun authenticatedOperationPreservesWrapperFormat() {
        val wrappingKey = Aead.key(Aead.random(32))
        val vaultKey = Aead.random(32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey) }
        val wrapped = AuthTicket(cipher, vaultKey.copyOf(), null).use { ticket ->
            cipher.iv + ticket.complete(cipher)
        }
        assertEquals(60, wrapped.size)
        assertArrayEquals(vaultKey, Aead.open(wrappingKey, wrapped, "VaultAuth/device-key/v1".toByteArray()))
        val decryption = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, wrapped, 0, 12))
        }
        AuthTicket(decryption, null, wrapped).use { ticket ->
            assertArrayEquals(vaultKey, ticket.complete(decryption))
            assertThrows(IllegalStateException::class.java) { ticket.complete(decryption) }
        }
        vaultKey.fill(0)
    }

    @Test fun cancelledOrDifferentAuthenticationCannotComplete() {
        val key = Aead.key(Aead.random(32))
        val original = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val different = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val secret = Aead.random(32)
        val ticket = AuthTicket(original, secret, null)
        assertThrows(IllegalStateException::class.java) { ticket.complete(different) }
        ticket.close()
        assertTrue(secret.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { ticket.complete(original) }
    }

    @Test fun encryptionUsesUniqueNoncesAndRejectsTampering() {
        val key = Aead.key(Aead.random(32))
        val secret = "test-only-secret-never-use".toByteArray()
        val aad = "test/v1".toByteArray()
        val first = Aead.seal(key, secret, aad)
        val second = Aead.seal(key, secret, aad)
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("test-only-secret"))
        assertArrayEquals(secret, Aead.open(key, first, aad))
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { Aead.open(key, first, aad) }
        assertThrows(AEADBadTagException::class.java) { Aead.open(key, second, "other".toByteArray()) }
        assertThrows(AEADBadTagException::class.java) { Aead.open(Aead.key(Aead.random(32)), second, aad) }
    }

    @Test fun vaultRoundTripAndInvalidDocuments() {
        val original = VaultRecord(AccountMetadata(UUID.randomUUID().toString(), "Example", "alice", "SHA1", 6, 30), "12345678901234567890".toByteArray())
        val decoded = VaultCodec.decode(VaultCodec.encode(listOf(original)))
        assertEquals(original.metadata, decoded.single().metadata)
        assertArrayEquals(original.secret, decoded.single().secret)
        assertThrows(IllegalArgumentException::class.java) { VaultCodec.decode(VaultCodec.encode(listOf(original, original))) }
        listOf("{}", "not json", "{\"version\":3,\"accounts\":[]}").forEach {
            assertThrows(IllegalArgumentException::class.java) { VaultCodec.decode(it.toByteArray()) }
        }
        decoded.forEach { it.close() }
        original.close()
        assertTrue(original.secret.all { it == 0.toByte() })
    }

    @Test fun legacyVaultsRemainTotpAndHotpRoundTripsWithoutCounterLoss() {
        val record = VaultRecord(AccountMetadata(UUID.randomUUID().toString(), "Example", "hotp", "SHA1", 8, 30,
            otpType = "HOTP", counter = Long.MAX_VALUE), "12345678901234567890".toByteArray())
        val snapshot = VaultCodec.encode(listOf(record))
        val decoded = VaultCodec.decode(snapshot)
        assertEquals("HOTP", decoded.single().metadata.otpType)
        assertEquals(Long.MAX_VALUE, decoded.single().metadata.counter)
        decoded.forEach { it.close() }
        val legacy = org.json.JSONObject(snapshot.toString(Charsets.UTF_8)).put("version", 1)
        legacy.getJSONArray("accounts").getJSONObject(0).apply { remove("otpType"); remove("counter") }
        val migrated = VaultCodec.decode(legacy.toString().toByteArray())
        assertEquals("TOTP", migrated.single().metadata.otpType)
        assertEquals(0L, migrated.single().metadata.counter)
        migrated.forEach { it.close() }
        val malformed = org.json.JSONObject(snapshot.toString(Charsets.UTF_8))
        malformed.getJSONArray("accounts").getJSONObject(0).put("counter", 1.5)
        assertThrows(IllegalArgumentException::class.java) { VaultCodec.decode(malformed.toString().toByteArray()) }
        malformed.put("version", 1)
        assertThrows(IllegalArgumentException::class.java) { VaultCodec.decode(malformed.toString().toByteArray()) }
        record.close()
    }
}