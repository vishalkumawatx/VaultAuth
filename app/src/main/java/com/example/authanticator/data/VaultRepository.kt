package com.example.authanticator.data

import android.content.Context
import android.util.AtomicFile
import com.example.authanticator.domain.Enrollment
import com.example.authanticator.security.Aead
import com.example.authanticator.security.atomicWrite
import com.example.authanticator.totp.Otp
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

interface VaultRepository {
    val unlocked: StateFlow<Boolean>
    val accounts: StateFlow<List<AccountMetadata>>
    suspend fun unlock(key: ByteArray)
    fun lock()
    suspend fun add(enrollment: Enrollment)
    suspend fun edit(metadata: AccountMetadata)
    suspend fun delete(id: String)
    suspend fun advanceCounter(id: String)
    suspend fun codes(epochSeconds: Long): Map<String, String>
    suspend fun snapshot(): ByteArray
    suspend fun merge(snapshot: ByteArray): Pair<Int, Int>
}

class LocalVaultRepository(context: Context, private val dao: AccountDao) : VaultRepository {
    private val file = AtomicFile(File(context.noBackupFilesDir, "vault.v1"))
    private val aad = "VaultAuth/accounts/v1".toByteArray()
    private val monitor = Any()
    private var key: ByteArray? = null
    private var records = emptyList<VaultRecord>()
    private val mutableUnlocked = MutableStateFlow(false)
    private val mutableAccounts = MutableStateFlow<List<AccountMetadata>>(emptyList())
    override val unlocked: StateFlow<Boolean> = mutableUnlocked
    override val accounts: StateFlow<List<AccountMetadata>> = mutableAccounts

    override suspend fun unlock(key: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(monitor) {
            lock()
            val loaded = if (file.baseFile.exists()) {
                require(file.baseFile.length() <= Aead.MAX_BYTES) { "Vault exceeds the supported size." }
                val plaintext = Aead.open(Aead.key(key), file.readFully(), aad)
                try { VaultCodec.decode(plaintext) } finally { plaintext.fill(0) }
            } else emptyList()
            try {
                dao.replace(loaded.map { it.metadata })
            } catch (error: Exception) {
                loaded.forEach { it.close() }
                throw error
            }
            records = loaded
            this@LocalVaultRepository.key = key.copyOf()
            publish()
            mutableUnlocked.value = true
        }
    }

    override fun lock() = synchronized(monitor) {
        mutableUnlocked.value = false
        key?.fill(0)
        key = null
        records.forEach { it.close() }
        records = emptyList()
        mutableAccounts.value = emptyList()
    }

    override suspend fun add(enrollment: Enrollment) = withContext(Dispatchers.IO) {
        synchronized(monitor) {
            requireUnlocked()
            val metadata = AccountMetadata(UUID.randomUUID().toString(), enrollment.issuer.trim(), enrollment.account.trim(),
                enrollment.parameters.algorithm.name, enrollment.parameters.digits, enrollment.parameters.period,
                otpType = enrollment.parameters.type.name, counter = enrollment.parameters.counter)
            require(records.none { VaultCodec.identity(it.metadata) == VaultCodec.identity(metadata) }) { "This issuer and account already exist." }
            val added = VaultRecord(metadata, enrollment.secret.copyOf())
            try { persist(records + added) } catch (error: Exception) { added.close(); throw error }
        }
    }

    override suspend fun edit(metadata: AccountMetadata) = withContext(Dispatchers.IO) {
        synchronized(monitor) {
            requireUnlocked()
            val existing = records.firstOrNull { it.metadata.id == metadata.id } ?: error("Account no longer exists.")
            require(metadata.account.isNotBlank() && metadata.account.length <= 160 && metadata.issuer.length <= 100)
            require(metadata.folder.length <= 64 && (metadata.issuer + metadata.account + metadata.folder).none { it.isISOControl() })
            require(metadata.algorithm == existing.metadata.algorithm && metadata.digits == existing.metadata.digits && metadata.period == existing.metadata.period)
            require(metadata.otpType == existing.metadata.otpType && metadata.counter == existing.metadata.counter) { "Use Next code to advance an HOTP counter." }
            require(records.none { it.metadata.id != metadata.id && VaultCodec.identity(it.metadata) == VaultCodec.identity(metadata) }) { "An account with this name already exists." }
            persist(records.map { if (it === existing) VaultRecord(metadata, it.secret) else it })
        }
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        synchronized(monitor) {
            requireUnlocked()
            val removed = records.firstOrNull { it.metadata.id == id } ?: error("Account no longer exists.")
            persist(records.filterNot { it === removed })
            removed.close()
        }
    }

    override suspend fun advanceCounter(id: String) = withContext(Dispatchers.IO) {
        synchronized(monitor) {
            requireUnlocked()
            val current = records.firstOrNull { it.metadata.id == id } ?: error("Account no longer exists.")
            require(current.metadata.otpType == "HOTP") { "Only HOTP accounts have a counter." }
            require(current.metadata.counter < Long.MAX_VALUE) { "Counter limit reached. Re-enroll with your service." }
            val updated = VaultRecord(current.metadata.copy(counter = current.metadata.counter + 1), current.secret)
            persist(records.map { if (it === current) updated else it })
        }
    }

    override suspend fun codes(epochSeconds: Long): Map<String, String> = withContext(Dispatchers.Default) {
        synchronized(monitor) {
            requireUnlocked()
            records.associate { it.metadata.id to Otp.code(it.secret, it.parameters, epochSeconds) }
        }
    }

    override suspend fun snapshot(): ByteArray = withContext(Dispatchers.IO) {
        synchronized(monitor) { requireUnlocked(); VaultCodec.encode(records) }
    }

    override suspend fun merge(snapshot: ByteArray): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val imported = VaultCodec.decode(snapshot)
        try {
            synchronized(monitor) {
                requireUnlocked()
                val selected = imported.filter { candidate -> records.none {
                    it.metadata.id == candidate.metadata.id || VaultCodec.identity(it.metadata) == VaultCodec.identity(candidate.metadata)
                } }
                val copies = selected.map { VaultRecord(it.metadata, it.secret.copyOf()) }
                try { persist(records + copies) } catch (error: Exception) { copies.forEach { it.close() }; throw error }
                copies.size to (imported.size - copies.size)
            }
        } finally { imported.forEach { it.close() } }
    }

    private fun requireUnlocked() { check(key != null && mutableUnlocked.value) { "Unlock the vault first." } }

    private fun persist(updated: List<VaultRecord>) {
        val plaintext = VaultCodec.encode(updated)
        val encrypted = try { Aead.seal(Aead.key(requireNotNull(key)), plaintext, aad) } finally { plaintext.fill(0) }
        atomicWrite(file, encrypted)
        records = updated
        publish()
        try { dao.replace(updated.map { it.metadata }) } catch (_: Exception) {
            lock()
            error("Vault saved, but the metadata index could not be refreshed. Unlock to rebuild the index.")
        }
    }

    private fun publish() {
        mutableAccounts.value = records.map { it.metadata }.sortedWith(
            compareByDescending<AccountMetadata> { it.favorite }.thenBy { it.issuer.lowercase() }.thenBy { it.account.lowercase() }
        )
    }
}