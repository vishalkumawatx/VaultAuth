package com.example.authanticator.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import java.io.IOException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

enum class UnlockIssue {
    AUTH_EXPIRED, KEY_MISSING, KEY_INVALIDATED, RECORD_DAMAGED, STORAGE,
    SCREEN_LOCK_REQUIRED, TEMPORARILY_UNAVAILABLE, LOCKED_OUT, OPERATION_LOST, UNKNOWN
}

class DeviceKeyFailure(val issue: UnlockIssue) : IllegalStateException(issue.name)

data class UnlockProblem(val issue: UnlockIssue, val title: String, val message: String, val canRetry: Boolean = true) {
    companion object {
        fun from(error: Throwable): UnlockProblem {
            val causes = generateSequence(error) { it.cause }.take(8).toList()
            val issue = when {
                causes.any { it is KeyPermanentlyInvalidatedException } -> UnlockIssue.KEY_INVALIDATED
                causes.any { it is UserNotAuthenticatedException } -> UnlockIssue.AUTH_EXPIRED
                causes.any { it is DeviceKeyFailure } -> causes.filterIsInstance<DeviceKeyFailure>().first().issue
                causes.any { it is UnrecoverableKeyException } -> UnlockIssue.KEY_MISSING
                causes.any { it is AEADBadTagException } -> UnlockIssue.RECORD_DAMAGED
                causes.any { it is IOException } -> UnlockIssue.STORAGE
                else -> UnlockIssue.UNKNOWN
            }
            return forIssue(issue)
        }

        fun forIssue(issue: UnlockIssue): UnlockProblem = when (issue) {
            UnlockIssue.AUTH_EXPIRED, UnlockIssue.OPERATION_LOST -> UnlockProblem(issue, "Please unlock again",
                "Android could not use the last authentication attempt. Tap Try again and complete the fingerprint or screen-lock prompt.")
            UnlockIssue.KEY_MISSING -> UnlockProblem(issue, "Device key not found",
                "The key for this vault is missing from Android Keystore. Retrying cannot recreate it. If you saved an encrypted backup, restore it on another installation using its backup password. Do not uninstall this app or clear its storage.", false)
            UnlockIssue.KEY_INVALIDATED -> UnlockProblem(issue, "Android invalidated this key",
                "Android reports that this vault's key is no longer usable. This can follow a screen-lock reset. Recover from an encrypted backup and its password, or use the service's recovery codes. Do not uninstall this app or clear its storage.", false)
            UnlockIssue.RECORD_DAMAGED -> UnlockProblem(issue, "Vault data could not be verified",
                "The saved key or vault file failed validation. Nothing was overwritten. Keep this installation intact. A previously saved encrypted backup may let you recover on another installation.", false)
            UnlockIssue.STORAGE -> UnlockProblem(issue, "Device storage is unavailable",
                "Android could not read or save the local key or account index. Check available storage, then try again. Do not clear this app's storage.")
            UnlockIssue.SCREEN_LOCK_REQUIRED -> UnlockProblem(issue, "Set up a screen lock",
                "Add a PIN, pattern, or password in Android security settings, then return here to unlock.", false)
            UnlockIssue.TEMPORARILY_UNAVAILABLE -> UnlockProblem(issue, "Unlock is temporarily unavailable",
                "Android authentication is not ready. Unlock your phone with its PIN, pattern, or password, then return and try again.")
            UnlockIssue.LOCKED_OUT -> UnlockProblem(issue, "Too many unlock attempts",
                "Android has paused biometric authentication. Wait, or unlock your phone with its screen lock, then try again here.")
            UnlockIssue.UNKNOWN -> UnlockProblem(issue, "Couldn't unlock",
                "Android could not finish opening the vault. Try again. If it repeats, restart your phone and retry. This error alone does not mean your key is lost.")
        }
    }
}