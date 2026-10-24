package com.example.authanticator.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.authanticator.AppContainer
import com.example.authanticator.backup.BackupCodec
import com.example.authanticator.data.VaultCodec
import com.example.authanticator.security.Aead
import com.example.authanticator.security.UnlockProblem
import com.example.authanticator.data.AccountMetadata
import com.example.authanticator.data.VaultRepository
import com.example.authanticator.domain.Enrollment
import com.example.authanticator.domain.ProvisioningUri
import com.example.authanticator.settings.PreferenceStore
import com.example.authanticator.settings.Preferences
import com.example.authanticator.totp.Algorithm
import com.example.authanticator.totp.Base32Secret
import com.example.authanticator.totp.OtpParameters
import com.example.authanticator.totp.OtpType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EnrollmentSummary(val issuer: String, val account: String, val algorithm: String, val digits: Int, val period: Int,
    val type: OtpType = OtpType.TOTP, val counter: Long = 0)

class VaultViewModel(
    val repository: VaultRepository,
    private val preferenceStore: PreferenceStore
) : ViewModel() {
    val preferences = preferenceStore.flow.catch {
        notify("Preferences could not be read. The vault is locked; existing files were preserved.")
        lock()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Preferences())
    val accounts = repository.accounts
    val unlocked = repository.unlocked
    private val mutableCodes = MutableStateFlow<Map<String, String>>(emptyMap())
    val codes = mutableCodes.asStateFlow()
    private val mutableTime = MutableStateFlow(System.currentTimeMillis() / 1000)
    val time = mutableTime.asStateFlow()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableUnlockProblem = MutableStateFlow<UnlockProblem?>(null)
    val unlockProblem = mutableUnlockProblem.asStateFlow()
    private val mutableAuthenticating = MutableStateFlow(false)
    val authenticating = mutableAuthenticating.asStateFlow()
    private val mutablePending = MutableStateFlow<EnrollmentSummary?>(null)
    val pending = mutablePending.asStateFlow()
    private var enrollment: Enrollment? = null
    private var lastInteraction = SystemClock.elapsedRealtime()
    private var generation = 0L
    private var foreground = false
    private var buckets = emptyList<Pair<String, Long>>()
    private val mutableSensitiveReady = MutableStateFlow(false)
    val sensitiveReady = mutableSensitiveReady.asStateFlow()
    private var sensitiveUntil = 0L
    private var exportBytes: ByteArray? = null
    private var restoreBytes: ByteArray? = null
    private var restorePlaintext: ByteArray? = null
    private val mutableRestoreSelected = MutableStateFlow(false)
    val restoreSelected = mutableRestoreSelected.asStateFlow()
    private val mutableRestoreSummary = MutableStateFlow<String?>(null)
    val restoreSummary = mutableRestoreSummary.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                val seconds = System.currentTimeMillis() / 1000
                mutableTime.value = seconds
                if (SystemClock.elapsedRealtime() >= sensitiveUntil) mutableSensitiveReady.value = false
                if (unlocked.value) {
                    if (SystemClock.elapsedRealtime() - lastInteraction >= preferences.value.timeoutSeconds * 1000L) {
                        lock()
                    } else if (foreground) {
                        val next = accounts.value.map { it.id to if (it.otpType == "HOTP") it.counter else seconds / it.period }
                        if (next != buckets) {
                            try {
                                val generated = repository.codes(seconds)
                                if (unlocked.value) mutableCodes.value = generated
                                buckets = next
                            } catch (_: Exception) {
                                mutableCodes.value = emptyMap()
                                notify("Codes are unavailable. Check your device time and unlock again.")
                            }
                        }
                    }
                }
                delay(250)
            }
        }
    }

    fun foreground(active: Boolean) {
        foreground = active
        if (!active && preferences.value.lockInBackground) lock()
        if (active && SystemClock.elapsedRealtime() - lastInteraction >= preferences.value.timeoutSeconds * 1000L) lock()
    }

    fun interaction() { lastInteraction = SystemClock.elapsedRealtime() }

    fun lock() {
        generation++
        repository.lock()
        mutableCodes.value = emptyMap()
        buckets = emptyList()
        cancelEnrollment()
        mutableSensitiveReady.value = false
        sensitiveUntil = 0
        cancelRestore()
    }

    fun acceptKey(key: ByteArray, sensitive: Boolean = false, after: () -> Unit = {}) {
        if (mutableBusy.value) { key.fill(0); return }
        val expected = generation
        action {
            try {
                if (!foreground) return@action
                if (!unlocked.value) repository.unlock(key)
                if (generation != expected || !foreground) { repository.lock(); return@action }
                interaction()
                sensitiveUntil = if (sensitive) SystemClock.elapsedRealtime() + 30_000 else 0
                mutableSensitiveReady.value = sensitive
                buckets = emptyList()
                dismissUnlockProblem()
                after()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lock()
                showUnlockProblem(UnlockProblem.from(error))
            } finally { key.fill(0) }
        }
    }

    fun prepareManual(issuer: String, account: String, secret: String, algorithm: Algorithm, digits: String, period: String,
        type: OtpType = OtpType.TOTP, counter: String = "0") = action {
        val initialCounter = if (type == OtpType.HOTP) {
            require(counter.matches(Regex("[0-9]{1,19}"))) { "Enter the counter supplied by your service." }
            counter.toLongOrNull() ?: throw IllegalArgumentException("Counter exceeds the supported range.")
        } else 0L
        val options = OtpParameters(algorithm, digits.toIntOrNull() ?: 0, if (type == OtpType.TOTP) period.toIntOrNull() ?: 0 else 30, type, initialCounter)
        val decoded = withContext(Dispatchers.Default) { Base32Secret.decode(secret) }
        try { setEnrollment(Enrollment(issuer.trim(), account.trim(), decoded, options)) } catch (error: Exception) {
            decoded.fill(0)
            throw error
        }
    }

    fun prepareUri(uri: String) = action {
        val parsed = withContext(Dispatchers.Default) { ProvisioningUri.parse(uri) }
        setEnrollment(parsed)
    }

    private fun setEnrollment(value: Enrollment) {
        if (!unlocked.value) { value.close(); error("Unlock the vault first.") }
        cancelEnrollment()
        enrollment = value
        mutablePending.value = EnrollmentSummary(value.issuer, value.account, value.parameters.algorithm.name, value.parameters.digits,
            value.parameters.period, value.parameters.type, value.parameters.counter)
    }

    fun cancelEnrollment() {
        enrollment?.close()
        enrollment = null
        mutablePending.value = null
    }

    fun saveEnrollment(after: () -> Unit) = action {
        repository.add(requireNotNull(enrollment))
        cancelEnrollment()
        after()
        notify("Account added.")
    }

    fun edit(metadata: AccountMetadata, after: () -> Unit = {}) = action { repository.edit(metadata); after() }
    fun delete(id: String, after: () -> Unit) = action { repository.delete(id); after(); notify("Account deleted.") }
    fun advanceCounter(id: String) = action {
        buckets = emptyList()
        mutableCodes.value = mutableCodes.value - id
        repository.advanceCounter(id)
        val refreshed = repository.codes(System.currentTimeMillis() / 1000)
        if (unlocked.value) mutableCodes.value = refreshed
    }
    fun preferences(value: Preferences) = action { preferenceStore.update(value) }

    fun copy(id: String, copied: (String) -> Unit) = action {
        check(preferences.value.clipboard) { "Clipboard copying is disabled." }
        val current = repository.codes(System.currentTimeMillis() / 1000)[id] ?: error("Account no longer exists.")
        if (unlocked.value) { copied(current); notify("Code copied. Clipboard clears after 30 seconds.") }
    }

    fun notify(text: String) { mutableMessage.value = text }
    fun beginAuthentication() { mutableAuthenticating.value = true; dismissUnlockProblem() }
    fun endAuthentication() { mutableAuthenticating.value = false }
    fun showUnlockProblem(problem: UnlockProblem) { mutableUnlockProblem.value = problem }
    fun dismissUnlockProblem() { mutableUnlockProblem.value = null }
    fun dismissMessage() { mutableMessage.value = null }

    fun generateRecoveryKey(generated: (String) -> Unit) = action {
        val bytes = withContext(Dispatchers.Default) { Aead.random(32) }
        try { generated(Base32Secret.encode(bytes)) } finally { bytes.fill(0) }
    }

    fun createBackup(password: CharArray, ready: () -> Unit) {
        if (mutableBusy.value) { password.fill('\u0000'); return }
        action {
            try {
                requireSensitiveAuthentication()
                val expected = generation
                val plaintext = repository.snapshot()
                val encrypted = try { withContext(Dispatchers.Default) { BackupCodec.encrypt(plaintext, password) } }
                finally { plaintext.fill(0) }
                check(unlocked.value && generation == expected) { "Vault locked. Authenticate and export again." }
                exportBytes = encrypted
                ready()
            } finally { password.fill('\u0000') }
        }
    }

    fun takeExport(): ByteArray? = exportBytes.also { exportBytes = null }

    fun selectRestore(encrypted: ByteArray) {
        cancelRestore()
        restoreBytes = encrypted
        mutableRestoreSelected.value = true
        notify("Encrypted backup selected. Open Backup & restore after unlocking to continue.")
    }

    fun previewRestore(password: CharArray) {
        if (mutableBusy.value) { password.fill('\u0000'); return }
        action {
            try {
                requireSensitiveAuthentication()
                val expected = generation
                val encrypted = restoreBytes ?: error("Choose an encrypted backup first.")
                val plaintext = withContext(Dispatchers.Default) { BackupCodec.decrypt(encrypted, password) }
                try {
                    val imported = withContext(Dispatchers.Default) { VaultCodec.decode(plaintext) }
                    try {
                        check(unlocked.value && generation == expected) { "Vault locked. Authenticate and try again." }
                        val skipped = imported.count { candidate -> accounts.value.any {
                            it.id == candidate.metadata.id || VaultCodec.identity(it) == VaultCodec.identity(candidate.metadata)
                        } }
                        cancelRestore()
                        restorePlaintext = plaintext.copyOf()
                        mutableRestoreSummary.value = "${imported.size - skipped} accounts will be added. $skipped duplicate accounts will be skipped. Existing accounts will not be overwritten."
                    } finally { imported.forEach { it.close() } }
                } finally { plaintext.fill(0) }
            } finally { password.fill('\u0000') }
        }
    }

    fun confirmRestore() = action {
        val plaintext = restorePlaintext ?: error("Review the backup before restoring.")
        try {
            val result = repository.merge(plaintext)
            restoreBytes = null
            mutableRestoreSelected.value = false
            notify("Restored ${result.first} accounts; skipped ${result.second} duplicates.")
        } finally { cancelRestore() }
    }

    fun cancelRestore() {
        restorePlaintext?.fill(0)
        restorePlaintext = null
        mutableRestoreSummary.value = null
    }

    private fun requireSensitiveAuthentication() {
        check(unlocked.value && mutableSensitiveReady.value && SystemClock.elapsedRealtime() < sensitiveUntil) { "Authenticate again before exporting or restoring." }
        mutableSensitiveReady.value = false
        sensitiveUntil = 0
    }

    fun action(block: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try { block() } catch (error: CancellationException) { throw error }
            catch (error: IllegalArgumentException) { notify(error.message ?: "Invalid input.") }
            catch (error: IllegalStateException) { notify(error.message ?: "The operation is not available.") }
            catch (_: Exception) { notify("Operation failed. Existing vault data was preserved. Check storage, device authentication, or backup integrity.") }
            finally { mutableBusy.value = false }
        }
    }

    override fun onCleared() {
        lock()
        exportBytes = null
        restoreBytes = null
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = VaultViewModel(container.repository, container.preferences) as T
    }
}