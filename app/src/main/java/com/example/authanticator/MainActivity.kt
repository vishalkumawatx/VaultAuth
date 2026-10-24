package com.example.authanticator

import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.authanticator.security.AuthTicket
import com.example.authanticator.security.ClipboardGuard
import com.example.authanticator.security.Aead
import com.example.authanticator.security.DeviceKeyFailure
import com.example.authanticator.security.UnlockIssue
import com.example.authanticator.security.UnlockProblem
import com.example.authanticator.ui.VaultApp
import com.example.authanticator.ui.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    private val container get() = (application as VaultApplication).container
    private val model: VaultViewModel by viewModels { VaultViewModel.Factory(container) }
    private val clipboard by lazy { ClipboardGuard(this) }
    private var ticket: AuthTicket? = null
    private var authenticating = false
    private var afterAuthentication: () -> Unit = {}
    private var sensitiveAuthentication = false
    private lateinit var prompt: BiometricPrompt
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val encrypted = model.takeExport()
        if (uri != null && encrypted != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(encrypted); it.flush() }
                        ?: error("Cannot open destination.")
                }
                model.notify("Encrypted backup exported. Keep its password separately and test a restore before relying on it.")
            } catch (_: Exception) {
                model.notify("Export failed. The destination may contain an incomplete file. Your local vault is unchanged.")
            }
        } else if (uri != null) model.notify("Export was interrupted. Start a new export; the local vault is unchanged.")
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val encrypted = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var count = input.read(buffer)
                        while (count != -1) {
                            require(output.size() + count <= Aead.MAX_BYTES)
                            output.write(buffer, 0, count)
                            count = input.read(buffer)
                        }
                        output.toByteArray()
                    } ?: error("Cannot open backup.")
                }
                model.selectRestore(encrypted)
            } catch (_: Exception) { model.notify("Could not read backup. Choose a readable Vault Auth file smaller than 4 MiB.") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val current = ticket ?: return
                ticket = null
                lifecycleScope.launch {
                    try {
                        val authenticatedCipher = result.cryptoObject?.cipher
                            ?: throw DeviceKeyFailure(UnlockIssue.OPERATION_LOST)
                        val key = withContext(Dispatchers.IO) { container.deviceKey.finish(current, authenticatedCipher) }
                        model.acceptKey(key, sensitiveAuthentication, afterAuthentication)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        model.showUnlockProblem(UnlockProblem.from(error))
                    } finally { current.close(); authenticating = false; model.endAuthentication(); afterAuthentication = {} }
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                ticket?.close()
                ticket = null
                authenticating = false
                model.endAuthentication()
                afterAuthentication = {}
                when (errorCode) {
                    BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> model.dismissUnlockProblem()
                    BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                        model.showUnlockProblem(UnlockProblem.forIssue(UnlockIssue.LOCKED_OUT))
                    else -> model.showUnlockProblem(UnlockProblem.forIssue(UnlockIssue.TEMPORARILY_UNAVAILABLE))
                }
            }
        })
        lifecycleScope.launch {
            model.unlocked.collect { if (!it) clipboard.clear() }
        }
        lifecycleScope.launch {
            model.preferences.collect { preferences ->
                val bars = WindowCompat.getInsetsController(window, window.decorView)
                bars.isAppearanceLightStatusBars = preferences.lightTheme
                bars.isAppearanceLightNavigationBars = preferences.lightTheme
            }
        }
        setContent {
            VaultApp(model, { authenticate() }, { clipboard.copy(it) }, {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }, {
                exportDocument.launch("VaultAuth-${java.time.Instant.now().epochSecond}.vaultauth")
            }, { importDocument.launch(arrayOf("application/octet-stream", "*/*")) }, { authenticate(credentialOnly = true) })
        }
    }

    private fun authenticate(credentialOnly: Boolean = false, after: () -> Unit = {}) {
        if (authenticating || model.busy.value) return
        val authenticators = if (credentialOnly) BiometricManager.Authenticators.DEVICE_CREDENTIAL
            else BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val availability = BiometricManager.from(this).canAuthenticate(authenticators)
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            model.showUnlockProblem(UnlockProblem.forIssue(if (availability == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
                UnlockIssue.SCREEN_LOCK_REQUIRED else UnlockIssue.TEMPORARILY_UNAVAILABLE))
            return
        }
        authenticating = true
        model.beginAuthentication()
        sensitiveAuthentication = model.unlocked.value
        afterAuthentication = after
        lifecycleScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) { container.deviceKey.prepare() }
                ticket = prepared
                prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Vault Auth")
                    .setSubtitle(if (credentialOnly) "Use your device PIN, pattern or password" else "Use a supported biometric or your screen lock")
                    .setAllowedAuthenticators(authenticators)
                    .build(), BiometricPrompt.CryptoObject(prepared.cipher))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ticket?.close()
                ticket = null
                authenticating = false
                model.endAuthentication()
                afterAuthentication = {}
                model.showUnlockProblem(UnlockProblem.from(error))
            }
        }
    }

    override fun onStart() { super.onStart(); model.foreground(true) }
    override fun onStop() {
        model.foreground(false)
        clipboard.clear()
        super.onStop()
    }
    override fun onUserInteraction() { super.onUserInteraction(); model.interaction() }
    override fun onDestroy() {
        ticket?.close()
        model.endAuthentication()
        clipboard.clear()
        super.onDestroy()
    }
}