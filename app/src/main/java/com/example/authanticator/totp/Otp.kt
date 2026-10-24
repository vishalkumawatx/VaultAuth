package com.example.authanticator.totp

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import com.eatthepath.otp.HmacOneTimePasswordGenerator
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.crypto.spec.SecretKeySpec
import org.apache.commons.codec.binary.Base32

enum class Algorithm(val hmac: String) {
    SHA1("HmacSHA1"), SHA256("HmacSHA256"), SHA512("HmacSHA512")
}

enum class OtpType { TOTP, HOTP }

data class OtpParameters(
    val algorithm: Algorithm = Algorithm.SHA1,
    val digits: Int = 6,
    val period: Int = 30,
    val type: OtpType = OtpType.TOTP,
    val counter: Long = 0
) {
    init {
        require(digits in 6..8) { "Use 6, 7, or 8 digits." }
        require(period in 1..86400) { "Period must be 1 to 86400 seconds." }
        require(counter >= 0) { "Counter must be a non-negative 64-bit integer." }
        require(type != OtpType.HOTP || algorithm == Algorithm.SHA1) { "Standard HOTP uses SHA1." }
        require(type != OtpType.TOTP || counter == 0L) { "TOTP does not use an event counter." }
    }
}

object Base32Secret {
    private val codec = Base32()

    fun decode(input: String): ByteArray {
        require(input.length <= 1024) { "Setup key is too long." }
        val normalized = input.replace(" ", "").uppercase(Locale.ROOT)
        require(normalized.matches(Regex("[A-Z2-7]+={0,6}"))) { "Enter a valid Base32 setup key." }
        val decoded = codec.decode(normalized)
        val canonical = codec.encodeAsString(decoded)
        require(canonical.trimEnd('=') == normalized.trimEnd('=')) { "Invalid Base32 encoding." }
        require(!normalized.contains('=') || normalized == canonical) { "Invalid Base32 padding." }
        return decoded
    }

    fun encode(bytes: ByteArray): String = codec.encodeAsString(bytes).trimEnd('=')
}

object Otp {
    fun code(secret: ByteArray, parameters: OtpParameters, epochSeconds: Long): String {
        if (parameters.type == OtpType.HOTP) {
            return HmacOneTimePasswordGenerator(parameters.digits).generateOneTimePasswordString(
                SecretKeySpec(secret, Algorithm.SHA1.hmac), parameters.counter, Locale.ROOT
            )
        }
        require(epochSeconds >= 0) { "Device time is before the Unix epoch." }
        val generator = TimeBasedOneTimePasswordGenerator(
            Duration.ofSeconds(parameters.period.toLong()), parameters.digits, parameters.algorithm.hmac
        )
        return generator.generateOneTimePassword(
            SecretKeySpec(secret, parameters.algorithm.hmac), Instant.ofEpochSecond(epochSeconds)
        ).toString().padStart(parameters.digits, '0')
    }

    fun remaining(epochSeconds: Long, period: Int): Int {
        require(epochSeconds >= 0 && period > 0)
        return period - (epochSeconds % period).toInt()
    }
}