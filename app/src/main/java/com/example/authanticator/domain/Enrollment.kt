package com.example.authanticator.domain

import com.example.authanticator.totp.Algorithm
import com.example.authanticator.totp.Base32Secret
import com.example.authanticator.totp.OtpParameters
import com.example.authanticator.totp.OtpType
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

class Enrollment(
    val issuer: String,
    val account: String,
    val secret: ByteArray,
    val parameters: OtpParameters = OtpParameters()
) : AutoCloseable {
    init {
        require(account.isNotBlank() && account.length <= 160) { "Account name is required (up to 160 characters)." }
        require(issuer.length <= 100) { "Issuer must be at most 100 characters." }
        require((issuer + account).none { it.isISOControl() }) { "Names cannot contain control characters." }
        require(secret.size in 10..640) { "Setup key must contain at least 80 bits." }
    }

    override fun close() = secret.fill(0)
    override fun toString(): String = "Enrollment(<redacted>)"
}

object ProvisioningUri {
    fun parse(input: String): Enrollment {
        require(input.length <= 4096) { "Provisioning URI is too long." }
        val uri = try { URI(input.trim()) } catch (_: Exception) {
            throw IllegalArgumentException("Invalid provisioning URI.")
        }
        require(uri.scheme.equals("otpauth", ignoreCase = true)) { "Use a standard otpauth TOTP or HOTP setup code. SMS, push approvals, and passkeys are managed by the service." }
        val type = when (uri.rawAuthority?.lowercase(Locale.ROOT)) {
            "totp" -> OtpType.TOTP
            "hotp" -> OtpType.HOTP
            else -> throw IllegalArgumentException("Only TOTP and standard HOTP setup codes are supported.")
        }
        require(uri.rawFragment == null && uri.rawPath?.startsWith('/') == true) { "Invalid provisioning URI." }
        val label = decode(uri.rawPath.drop(1), false)
        val parameters = mutableMapOf<String, String>()
        for (part in (uri.rawQuery ?: "").split('&')) {
            val pair = part.split('=', limit = 2)
            require(pair.size == 2) { "Malformed provisioning parameters." }
            val key = decode(pair[0], true)
            require(key in setOf("secret", "issuer", "algorithm", "digits", "period", "counter", "image")) { "Unsupported provisioning parameter." }
            require(parameters.put(key, decode(pair[1], true)) == null) { "Duplicate provisioning parameter." }
        }
        val labelIssuer = if (':' in label) label.substringBefore(':').trim() else ""
        val account = label.substringAfter(':', label).trim()
        val issuer = parameters["issuer"]?.trim() ?: labelIssuer
        require(labelIssuer.isEmpty() || issuer == labelIssuer) { "Issuer does not match the account label." }
        val algorithm = try {
            Algorithm.valueOf((parameters["algorithm"] ?: "SHA1").uppercase(Locale.ROOT).replace("-", ""))
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Supported algorithms are SHA1, SHA256, and SHA512.")
        }
        require(type != OtpType.TOTP || "counter" !in parameters) { "TOTP cannot include a counter." }
        require(type != OtpType.HOTP || "period" !in parameters) { "HOTP uses a counter, not a period." }
        val counter = if (type == OtpType.HOTP) {
            val value = parameters["counter"] ?: throw IllegalArgumentException("HOTP requires an initial counter.")
            require(value.matches(Regex("[0-9]{1,19}"))) { "Invalid HOTP counter." }
            value.toLongOrNull() ?: throw IllegalArgumentException("HOTP counter exceeds the supported range.")
        } else 0L
        val options = OtpParameters(algorithm, number(parameters, "digits", 6), number(parameters, "period", 30), type, counter)
        val secret = Base32Secret.decode(parameters["secret"] ?: throw IllegalArgumentException("Missing setup key."))
        return try { Enrollment(issuer, account, secret, options) } catch (error: Exception) {
            secret.fill(0)
            throw error
        }
    }

    private fun number(values: Map<String, String>, key: String, default: Int): Int {
        val value = values[key] ?: return default
        require(value.matches(Regex("[0-9]{1,5}"))) { "Invalid $key parameter." }
        return value.toInt()
    }

    private fun decode(value: String, query: Boolean): String = try {
        URLDecoder.decode(if (query) value else value.replace("+", "%2B"), "UTF-8").also {
            require('\uFFFD' !in it) { "Invalid UTF-8 encoding." }
        }
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid URL encoding.")
    }
}