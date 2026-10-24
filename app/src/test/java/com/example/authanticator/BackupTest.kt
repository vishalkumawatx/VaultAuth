package com.example.authanticator

import com.example.authanticator.backup.BackupCodec
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    private val password = "test only six random words example".toCharArray()

    @Test fun roundTripAndUniqueSalt() {
        val plaintext = "test vault with mock secrets".toByteArray()
        val first = BackupCodec.encrypt(plaintext, password)
        val second = BackupCodec.encrypt(plaintext, password)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(plaintext, BackupCodec.decrypt(first, password))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("mock secrets"))
    }

    @Test fun rejectsWrongPasswordTamperingAndTruncation() {
        val encrypted = BackupCodec.encrypt("example".toByteArray(), password)
        assertThrows(AEADBadTagException::class.java) { BackupCodec.decrypt(encrypted, "wrong but long enough password".toCharArray()) }
        for (offset in listOf(10, 26, encrypted.lastIndex)) {
            val modified = encrypted.copyOf()
            modified[offset] = (modified[offset].toInt() xor 1).toByte()
            assertThrows(AEADBadTagException::class.java) { BackupCodec.decrypt(modified, password) }
        }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decrypt(encrypted.copyOf(20), password) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encrypt(byteArrayOf(), "short".toCharArray()) }
    }

    @Test fun rejectsUnsupportedVersionAndUnboundedKdfBeforeDerivation() {
        val encrypted = BackupCodec.encrypt(byteArrayOf(1, 2, 3), password)
        val version = encrypted.copyOf().apply { this[5] = 2 }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decrypt(version, password) }
        val iterations = encrypted.copyOf().apply { ByteBuffer.wrap(this, 6, 4).putInt(Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decrypt(iterations, password) }
    }
}