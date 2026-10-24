package com.example.authanticator

import com.example.authanticator.domain.ProvisioningUri
import com.example.authanticator.totp.Algorithm
import com.example.authanticator.totp.Base32Secret
import com.example.authanticator.totp.Otp
import com.example.authanticator.totp.OtpParameters
import com.example.authanticator.totp.OtpType
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class OtpTest {
    @Test fun hotpRfc4226AndLocaleIndependentDigits() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val expected = listOf("755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489")
            expected.forEachIndexed { counter, code ->
                assertEquals(code, Otp.code("12345678901234567890".toByteArray(), OtpParameters(type = OtpType.HOTP, counter = counter.toLong()), 0))
            }
            assertEquals("287082", Otp.code("12345678901234567890".toByteArray(), OtpParameters(), 59))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun hotpEnrollmentAndExtendedTotpParameters() {
        ProvisioningUri.parse("otpauth://hotp/Example:alice?secret=GEZDGNBVGY3TQOJQ&counter=42&digits=8").use {
            assertEquals(OtpType.HOTP, it.parameters.type)
            assertEquals(42L, it.parameters.counter)
        }
        ProvisioningUri.parse("OTPAUTH://TOTP/alice?secret=GEZDGNBVGY3TQOJQ&algorithm=SHA-256&period=300&image=https%3A%2F%2Fexample.invalid%2Ficon.png").use {
            assertEquals(Algorithm.SHA256, it.parameters.algorithm)
            assertEquals(300, it.parameters.period)
        }
        val base = "otpauth://hotp/alice?secret=GEZDGNBVGY3TQOJQ"
        listOf("", "&counter=-1", "&counter=9223372036854775808", "&counter=1&period=30", "&counter=0&algorithm=SHA256").forEach {
            assertThrows(IllegalArgumentException::class.java) { ProvisioningUri.parse(base + it) }
        }
    }

    @Test fun rfc6238AllAlgorithmsAndTimestamps() {
        val times = listOf(59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L)
        val expected = listOf(
            listOf("94287082", "07081804", "14050471", "89005924", "69279037", "65353130"),
            listOf("46119246", "68084774", "67062674", "91819424", "90698825", "77737706"),
            listOf("90693936", "25091201", "99943326", "93441116", "38618901", "47863826")
        )
        val seeds = listOf(
            "12345678901234567890", "12345678901234567890123456789012",
            "1234567890123456789012345678901234567890123456789012345678901234"
        )
        Algorithm.entries.forEachIndexed { algorithmIndex, algorithm ->
            times.forEachIndexed { timeIndex, time ->
                assertEquals(expected[algorithmIndex][timeIndex], Otp.code(seeds[algorithmIndex].toByteArray(), OtpParameters(algorithm, 8), time))
            }
        }
    }

    @Test fun rfc4226CounterVectorsThroughTotp() {
        val expected = listOf("755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489")
        expected.forEachIndexed { counter, code ->
            assertEquals(code, Otp.code("12345678901234567890".toByteArray(), OtpParameters(), counter * 30L))
        }
    }

    @Test fun boundaryAndLeadingZero() {
        val secret = "12345678901234567890".toByteArray()
        assertEquals(Otp.code(secret, OtpParameters(), 0), Otp.code(secret, OtpParameters(), 29))
        assertNotEquals(Otp.code(secret, OtpParameters(), 29), Otp.code(secret, OtpParameters(), 30))
        assertEquals(1, Otp.remaining(29, 30))
        assertEquals(30, Otp.remaining(30, 30))
        assertEquals("07081804", Otp.code(secret, OtpParameters(digits = 8), 1111111109))
        assertThrows(IllegalArgumentException::class.java) { Otp.code(secret, OtpParameters(), -1) }
    }

    @Test fun base32CanonicalValidation() {
        assertArrayEquals("foobar".toByteArray(), Base32Secret.decode("mzxw6 ytb oi======"))
        assertArrayEquals("foobar".toByteArray(), Base32Secret.decode("MZXW6YTBOI"))
        listOf("", "0O1I", "MZ======", "MY=", "MY======A", "MY!!!!!!").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { Base32Secret.decode(value) }
        }
    }

    @Test fun parseEncodedLabelsAndOptions() {
        ProvisioningUri.parse("otpauth://totp/Example%20Co%3Aalice%2Bwork%40example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Example+Co&algorithm=SHA256&digits=8&period=60").use {
            assertEquals("Example Co", it.issuer)
            assertEquals("alice+work@example.com", it.account)
            assertEquals(OtpParameters(Algorithm.SHA256, 8, 60), it.parameters)
            assertFalse(it.toString().contains("GEZD"))
        }
        ProvisioningUri.parse("otpauth://totp/alice+work?secret=GEZDGNBVGY3TQOJQ").use {
            assertEquals("alice+work", it.account)
            assertEquals("", it.issuer)
        }
    }

    @Test fun rejectAmbiguousOrUnsupportedUris() {
        val valid = "otpauth://totp/Example:alice?secret=GEZDGNBVGY3TQOJQ"
        listOf(
            valid.replace("totp", "hotp"), valid + "&secret=MY", valid + "&issuer=Other",
            valid + "&digits=5", valid + "&algorithm=MD5", valid + "&period=0",
            valid + "&period=-30", valid + "&counter=1", valid + "#fragment",
            "otpauth://totp/?secret=GEZDGNBVGY3TQOJQ", "otpauth://totp/alice?issuer=Example",
            valid.replace("alice", "%FF"), valid.replace("alice", "%XX")
        ).forEach { value -> assertThrows(IllegalArgumentException::class.java) { ProvisioningUri.parse(value) } }
    }
}