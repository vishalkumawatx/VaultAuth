package com.example.authanticator.data

import com.example.authanticator.domain.Enrollment
import com.example.authanticator.totp.Algorithm
import com.example.authanticator.totp.Base32Secret
import com.example.authanticator.totp.OtpParameters
import com.example.authanticator.totp.OtpType
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class VaultRecord(val metadata: AccountMetadata, val secret: ByteArray) : AutoCloseable {
    val parameters: OtpParameters
        get() = OtpParameters(Algorithm.valueOf(metadata.algorithm), metadata.digits, metadata.period, OtpType.valueOf(metadata.otpType), metadata.counter)
    override fun close() = secret.fill(0)
}

internal object VaultCodec {
    const val MAX_ACCOUNTS = 1000

    fun encode(records: List<VaultRecord>): ByteArray {
        require(records.size <= MAX_ACCOUNTS) { "Vault supports at most 1,000 accounts." }
        val array = JSONArray()
        for (record in records) {
            val metadata = record.metadata
            array.put(JSONObject().put("id", metadata.id).put("issuer", metadata.issuer)
                .put("account", metadata.account).put("algorithm", metadata.algorithm)
                .put("digits", metadata.digits).put("period", metadata.period)
                .put("favorite", metadata.favorite).put("folder", metadata.folder)
                .put("otpType", metadata.otpType).put("counter", metadata.counter)
                .put("secret", Base32Secret.encode(record.secret)))
        }
            return JSONObject().put("version", 2).put("accounts", array).toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): List<VaultRecord> {
        val records = mutableListOf<VaultRecord>()
        try {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            val version = strictInteger(root, "version")
            require(version in 1..2) { "Unsupported vault version." }
            val array = root.getJSONArray("accounts")
            require(array.length() <= MAX_ACCOUNTS) { "Too many accounts in vault." }
            val ids = mutableSetOf<String>()
            val identities = mutableSetOf<Pair<String, String>>()
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val id = item.getString("id")
                require(UUID.fromString(id).toString() == id && ids.add(id)) { "Invalid or duplicate account identifier." }
                require(version != 1 || (!item.has("otpType") && !item.has("counter"))) { "Ambiguous legacy record." }
                val type = if (version == 1) OtpType.TOTP else OtpType.valueOf(item.getString("otpType"))
                val counter = if (version == 1) 0L else {
                    val value = item.get("counter")
                    require(value is Int || value is Long) { "Counter must be an integer." }
                    (value as Number).toLong()
                }
                val metadata = AccountMetadata(id, item.getString("issuer"), item.getString("account"),
                    item.getString("algorithm"), strictInteger(item, "digits"), strictInteger(item, "period"),
                    item.getBoolean("favorite"), item.getString("folder"), type.name, counter)
                require(metadata.folder.length <= 64 && metadata.folder.none { it.isISOControl() }) { "Invalid folder." }
                require(identities.add(identity(metadata))) { "Duplicate account in vault." }
                val parameters = OtpParameters(Algorithm.valueOf(metadata.algorithm), metadata.digits, metadata.period, type, counter)
                val secret = Base32Secret.decode(item.getString("secret"))
                try {
                    Enrollment(metadata.issuer, metadata.account, secret, parameters)
                    records.add(VaultRecord(metadata, secret))
                } catch (error: Exception) {
                    secret.fill(0)
                    throw error
                }
            }
            return records
        } catch (error: Exception) {
            records.forEach { it.close() }
            throw IllegalArgumentException("Invalid or unsupported vault contents. No accounts were changed.")
        }
    }

    private fun strictInteger(value: JSONObject, name: String): Int {
        val number = value.get(name)
        require(number is Int) { "Expected integer $name." }
        return number
    }

    fun identity(metadata: AccountMetadata): Pair<String, String> =
        metadata.issuer.trim().lowercase(java.util.Locale.ROOT) to metadata.account.trim().lowercase(java.util.Locale.ROOT)
}