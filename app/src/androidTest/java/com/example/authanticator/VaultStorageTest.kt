package com.example.authanticator

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.security.keystore.KeyInfo
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.authanticator.data.LocalVaultRepository
import com.example.authanticator.data.MetadataDatabase
import com.example.authanticator.domain.Enrollment
import com.example.authanticator.security.Aead
import com.example.authanticator.security.DeviceKey
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultStorageTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun hotpCounterPersistsAndDuplicateRestoreCannotRollItBack() = runBlocking {
        val context = isolatedContext()
        val database = Room.inMemoryDatabaseBuilder(context, MetadataDatabase::class.java).build()
        val repository = LocalVaultRepository(context, database.accounts())
        val key = Aead.random(32)
        try {
            repository.unlock(key)
            Enrollment("RFC", "counter-test", "12345678901234567890".toByteArray(),
                com.example.authanticator.totp.OtpParameters(type = com.example.authanticator.totp.OtpType.HOTP)).use { repository.add(it) }
            val id = repository.accounts.value.single().id
            assertEquals("755224", repository.codes(0)[id])
            val backup = repository.snapshot()
            try {
                repository.advanceCounter(id)
                assertEquals("287082", repository.codes(999999)[id])
                repository.lock()
                repository.unlock(key)
                assertEquals(1L, repository.accounts.value.single().counter)
                assertEquals(0 to 1, repository.merge(backup))
                assertEquals(1L, repository.accounts.value.single().counter)
            } finally { backup.fill(0) }
        } finally {
            repository.lock()
            key.fill(0)
            database.close()
            context.noBackupFilesDir.deleteRecursively()
        }
    }

    @Test fun roomMigrationPreservesExistingMetadata() {
        val context = isolatedContext()
        val file = File(context.noBackupFilesDir, "migration.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
            legacy.execSQL("CREATE TABLE accounts (id TEXT NOT NULL PRIMARY KEY, issuer TEXT NOT NULL, account TEXT NOT NULL, algorithm TEXT NOT NULL, digits INTEGER NOT NULL, period INTEGER NOT NULL, favorite INTEGER NOT NULL, folder TEXT NOT NULL)")
            legacy.execSQL("INSERT INTO accounts VALUES ('test-id', 'Example', 'alice', 'SHA1', 6, 30, 0, '')")
            legacy.version = 1
        }
        val database = Room.databaseBuilder(context, MetadataDatabase::class.java, file.absolutePath)
            .addMigrations(MetadataDatabase.MIGRATION_1_2).build()
        try {
            database.openHelper.writableDatabase.query("SELECT account, otpType, counter FROM accounts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("alice", cursor.getString(0))
                assertEquals("TOTP", cursor.getString(1))
                assertEquals(0L, cursor.getLong(2))
            }
        } finally { database.close(); context.noBackupFilesDir.deleteRecursively() }
    }

    private fun isolatedContext(): Context = object : ContextWrapper(target) {
        private val directory = File(target.cacheDir, "vault-test-${UUID.randomUUID()}").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = directory
    }

    @Test fun persistentVaultIsEncryptedAndRejectsTampering() = runBlocking {
        val context = isolatedContext()
        val database = Room.inMemoryDatabaseBuilder(context, MetadataDatabase::class.java).build()
        val repository = LocalVaultRepository(context, database.accounts())
        val key = Aead.random(32)
        try {
            repository.unlock(key)
            Enrollment("Example", "test@example.invalid", "12345678901234567890".toByteArray()).use { repository.add(it) }
            val metadata = repository.accounts.value.single()
            val expected = repository.codes(59)[metadata.id]
            val file = File(context.noBackupFilesDir, "vault.v1")
            val ciphertext = file.readBytes()
            assertFalse(ciphertext.toString(Charsets.ISO_8859_1).contains("12345678901234567890"))
            assertFalse(ciphertext.toString(Charsets.ISO_8859_1).contains("GEZDGNBV"))
            repository.lock()
            assertTrue(repository.accounts.value.isEmpty())
            repository.unlock(key)
            assertEquals(expected, repository.codes(59)[metadata.id])
            val snapshot = repository.snapshot()
            try { assertEquals(0 to 1, repository.merge(snapshot)) } finally { snapshot.fill(0) }
            repository.lock()
            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            file.writeBytes(ciphertext)
            try { repository.unlock(key); fail("Tampered vault was accepted") } catch (_: AEADBadTagException) { }
            assertFalse(repository.unlocked.value)
            assertArrayEquals(ciphertext, file.readBytes())
        } finally {
            repository.lock()
            key.fill(0)
            database.close()
            context.noBackupFilesDir.deleteRecursively()
        }
    }

    @Test fun missingKeystoreKeyDoesNotOverwriteExistingWrapper() {
        val context = isolatedContext()
        val alias = "vault-auth.test.${UUID.randomUUID()}"
        val wrapped = Aead.random(60)
        val file = File(context.noBackupFilesDir, "device-key.v1").apply { writeBytes(wrapped) }
        try {
            assertThrows(IllegalStateException::class.java) { DeviceKey(context, alias).prepare() }
            assertArrayEquals(wrapped, file.readBytes())
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertFalse(store.containsAlias(alias))
        } finally { context.noBackupFilesDir.deleteRecursively() }
    }

    @Test fun generatedDeviceKeyRequiresAuthentication() {
        val context = isolatedContext()
        val alias = "vault-auth.test.${UUID.randomUUID()}"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val manager = androidx.biometric.BiometricManager.from(target)
        org.junit.Assume.assumeTrue(manager.canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS)
        try {
            DeviceKey(context, alias).prepare().close()
            val key = store.getKey(alias, null) as SecretKey
            val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
            assertTrue(info.isUserAuthenticationRequired)
            assertTrue(info.userAuthenticationValidityDurationSeconds <= 0)
            assertFalse(File(context.noBackupFilesDir, "device-key.v1").exists())
        } finally {
            store.deleteEntry(alias)
            context.noBackupFilesDir.deleteRecursively()
        }
    }

    @Test fun mergedApplicationConfigurationDoesNotAllowNetworkOrBackup() {
        val info = target.packageManager.getPackageInfo(target.packageName, PackageManager.GET_PERMISSIONS)
        val permissions = info.requestedPermissions?.toList().orEmpty()
        assertFalse(permissions.contains("android.permission.INTERNET"))
        assertFalse(permissions.contains("android.permission.ACCESS_NETWORK_STATE"))
        assertEquals(0, target.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test fun bundledScannerReadsMockProvisioningQrOffline() {
        val uri = "otpauth://totp/Example:test%40example.invalid?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Example"
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(uri, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
        val pixels = IntArray(512 * 512) { index -> if (matrix[index % 512, index / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        val bitmap = android.graphics.Bitmap.createBitmap(pixels, 512, 512, android.graphics.Bitmap.Config.ARGB_8888)
        val scanner = com.google.mlkit.vision.barcode.BarcodeScanning.getClient()
        try {
            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
            val results = com.google.android.gms.tasks.Tasks.await(scanner.process(image), 15, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(uri, results.single().rawValue)
            com.example.authanticator.domain.ProvisioningUri.parse(requireNotNull(results.single().rawValue)).use {
                assertEquals("Example", it.issuer)
                assertEquals("test@example.invalid", it.account)
            }
        } finally { scanner.close(); bitmap.recycle() }
    }
}