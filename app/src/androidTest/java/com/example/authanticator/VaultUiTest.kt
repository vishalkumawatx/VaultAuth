package com.example.authanticator

import android.content.ContextWrapper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.authanticator.backup.BackupCodec
import com.example.authanticator.data.LocalVaultRepository
import com.example.authanticator.data.MetadataDatabase
import com.example.authanticator.domain.Enrollment
import com.example.authanticator.security.Aead
import com.example.authanticator.security.UnlockIssue
import com.example.authanticator.security.UnlockProblem
import com.example.authanticator.settings.PreferenceStore
import com.example.authanticator.settings.Preferences
import com.example.authanticator.ui.VaultApp
import com.example.authanticator.ui.VaultViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: VaultViewModel
    private lateinit var repository: LocalVaultRepository
    private lateinit var database: MetadataDatabase
    private lateinit var directory: File
    private val key = Aead.random(32)
    private val models = ViewModelStore()
    private var copied: String? = null
    private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fontScale = mutableFloatStateOf(1f)

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(target.cacheDir, "ui-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getNoBackupFilesDir() = directory }
        database = Room.inMemoryDatabaseBuilder(context, MetadataDatabase::class.java).build()
        repository = LocalVaultRepository(context, database.accounts())
        val isolatedPreferences = PreferenceDataStoreFactory.create(scope = preferenceScope,
            produceFile = { File(directory, "settings.preferences_pb") })
        val settings = PreferenceStore(target, isolatedPreferences)
        runBlocking {
            settings.update(Preferences(onboarded = true, timeoutSeconds = 300))
            repository.unlock(key)
        }
        compose.runOnUiThread {
            model = VaultViewModel(repository, settings)
            models.put("test", model)
            model.foreground(true)
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.floatValue)) {
                VaultApp(model, { model.acceptKey(key.copyOf(), sensitive = repository.unlocked.value) }, { copied = it }, {}, {}, {})
            }
        }
        compose.waitUntil(5000) { model.preferences.value.onboarded }
    }

    @After fun cleanup() {
        compose.runOnUiThread { models.clear() }
        key.fill(0)
        database.close()
        preferenceScope.cancel()
        directory.deleteRecursively()
    }

    @Test fun unlockFailureRemainsVisibleAndRetryReturnsToVault() {
        compose.runOnUiThread {
            model.lock()
            model.showUnlockProblem(UnlockProblem.forIssue(UnlockIssue.AUTH_EXPIRED))
        }
        compose.onNodeWithText("Please unlock again").assertIsDisplayed()
        compose.onNodeWithText("Reference: AUTH_EXPIRED").assertExists()
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(5000) { repository.unlocked.value && !model.busy.value }
        assertNull(model.unlockProblem.value)
    }

    @Test fun missingKeyGuidanceDoesNotOfferResetOrRetry() {
        compose.runOnUiThread {
            model.lock()
            model.showUnlockProblem(UnlockProblem.forIssue(UnlockIssue.KEY_MISSING))
        }
        compose.onNodeWithText("Device key not found").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Nothing has been deleted. Do not clear app data.").assertExists()
        assertFalse(repository.unlocked.value)
    }

    @Test fun manualEnrollmentSearchCopyAndDelete() {
        compose.onNodeWithText("Add account").performClick()
        compose.onNodeWithText("Enter setup details").performClick()
        compose.onNodeWithText("Issuer (optional)").performTextInput("Example")
        compose.onNodeWithText("Account name").performTextInput("alice@example.invalid")
        compose.onNodeWithText("Base32 setup key").performTextInput("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        compose.onNodeWithText("Review account").performScrollTo().performClick()
        compose.onNodeWithText("Add this account?").assertExists()
        compose.onNodeWithText("Add account").performClick()
        compose.waitUntil(5000) { repository.accounts.value.size == 1 && !model.busy.value }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Search accounts").performTextInput("unmatched")
        compose.onNodeWithText("No matching accounts").assertExists()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithContentDescription("Copy code").performClick()
        compose.waitUntil(5000) { copied != null }
        assertTrue(copied!!.matches(Regex("[0-9]{6}")))
        compose.onNodeWithContentDescription("Account details").performClick()
        compose.onNodeWithText("Delete account").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5000) { repository.accounts.value.isEmpty() }
    }

    @Test fun themesAndLockRequireExplicitUnlock() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Light theme").performScrollTo().performClick()
        compose.waitUntil(5000) { model.preferences.value.lightTheme }
        compose.onNodeWithText("Reduce motion").performScrollTo().performClick()
        compose.waitUntil(5000) { model.preferences.value.reduceMotion }
        compose.onNodeWithText("Lock now").performScrollTo().performClick()
        compose.onNodeWithText("Vault locked").assertExists()
        assertFalse(repository.unlocked.value)
        compose.onNodeWithText("Unlock vault").performClick()
        compose.waitUntil(5000) { repository.unlocked.value }
        compose.onNodeWithText("Add account").assertExists()
        assertFalse(model.sensitiveReady.value)
    }

    @Test fun backupRecoveryRoundTripAndExplicitConfirmation() {
        runBlocking { Enrollment("Example", "recovery-test", "12345678901234567890".toByteArray()).use { repository.add(it) } }
        compose.runOnUiThread { model.acceptKey(key.copyOf(), sensitive = true) }
        compose.waitUntil(5000) { model.sensitiveReady.value && !model.busy.value }
        var exported = false
        compose.runOnUiThread { model.createBackup("mock six word recovery password only".toCharArray()) { exported = true } }
        compose.waitUntil(15000) { exported }
        val encrypted = requireNotNull(model.takeExport())
        val decrypted = BackupCodec.decrypt(encrypted, "mock six word recovery password only".toCharArray())
        assertTrue(decrypted.isNotEmpty())
        decrypted.fill(0)
        runBlocking { repository.delete(repository.accounts.value.single().id) }
        compose.runOnUiThread {
            model.selectRestore(encrypted)
            model.acceptKey(key.copyOf(), sensitive = true)
        }
        compose.waitUntil(5000) { model.sensitiveReady.value && !model.busy.value }
        compose.runOnUiThread { model.previewRestore("mock six word recovery password only".toCharArray()) }
        compose.waitUntil(15000) { model.restoreSummary.value != null && !model.busy.value }
        assertTrue(repository.accounts.value.isEmpty())
        compose.runOnUiThread { model.confirmRestore() }
        compose.waitUntil(5000) { repository.accounts.value.size == 1 && !model.busy.value }
        assertEquals("recovery-test", repository.accounts.value.single().account)
    }

    @Test fun hotpCounterAdvancesOnlyAfterConfirmation() {
        runBlocking {
            Enrollment("RFC test", "hotp@example.invalid", "12345678901234567890".toByteArray(),
                com.example.authanticator.totp.OtpParameters(type = com.example.authanticator.totp.OtpType.HOTP)).use { repository.add(it) }
        }
        compose.waitUntil(5000) { model.codes.value.isNotEmpty() }
        compose.onNodeWithText("755 224").assertExists()
        compose.onNodeWithContentDescription("Copy code").performClick()
        compose.waitUntil(5000) { copied != null && !model.busy.value }
        assertEquals(0L, repository.accounts.value.single().counter)
        compose.onNodeWithText("Next code").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0L, repository.accounts.value.single().counter)
        compose.onNodeWithText("Next code").performClick()
        compose.onNodeWithText("Advance counter").performClick()
        compose.waitUntil(5000) { repository.accounts.value.single().counter == 1L && !model.busy.value }
        compose.onNodeWithText("287 082").assertExists()
    }

    @Test fun captureMockAccountThemes() {
        runBlocking {
            Enrollment("Example", "design-test@example.invalid", "12345678901234567890".toByteArray()).use { repository.add(it) }
        }
        compose.waitUntil(5000) { model.codes.value.isNotEmpty() }
        captureMockScreen("accounts-dark.png")
        compose.runOnUiThread { model.preferences(model.preferences.value.copy(lightTheme = true, reduceMotion = true)) }
        compose.waitUntil(5000) { model.preferences.value.lightTheme && !model.busy.value }
        compose.waitForIdle()
        captureMockScreen("accounts-light.png")
        compose.runOnUiThread { fontScale.floatValue = 2f }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Copy code").performScrollTo().assertIsDisplayed()
        captureMockScreen("accounts-large-text.png")
    }

    private fun captureMockScreen(name: String) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue(image.width > 0 && image.height > 0)
        val output = File(requireNotNull(target.getExternalFilesDir("ui-validation")), name)
        output.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}