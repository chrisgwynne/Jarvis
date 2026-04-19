package com.jarvis.assistant.security

import android.util.Base64
import com.jarvis.assistant.util.SettingsStore
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * AppLockManager — PIN + biometric gate for the whole app.
 *
 * Backend for Phase 4b.  The PIN is stored as a PBKDF2-HmacSHA256 hash with
 * a per-device random salt; the plain PIN never lands on disk and never
 * leaves this class.  A successful unlock (PIN or biometric) writes a
 * timestamp to [SettingsStore.appLockLastUnlockMs]; [isLocked] treats the
 * app as unlocked for [SettingsStore.APP_LOCK_SESSION_MS] after that.
 *
 * This class is deliberately free of Android UI concerns — the biometric
 * prompt flow is built by the caller (e.g. MainActivity) using the
 * androidx.biometric APIs; AppLockManager only records the successful
 * outcome via [markUnlocked].  The UI integration is scheduled for a
 * follow-up commit; the backend exists now so PIN storage + verification
 * are stable and testable in isolation.
 */
class AppLockManager(private val settings: SettingsStore) {

    companion object {
        private const val PBKDF2_ALGO       = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 120_000          // OWASP 2023 floor
        private const val HASH_BITS         = 256
        private const val SALT_BYTES        = 16
        private const val MIN_PIN_LENGTH    = 4
        private const val MAX_PIN_LENGTH    = 12
    }

    /**
     * True when the app is locked and needs PIN / biometric verification before
     * tool execution or personal actions may proceed.
     */
    fun isLocked(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!settings.appLockEnabled) return false
        if (settings.appLockPinHash.isBlank()) return false
        val last = settings.appLockLastUnlockMs
        if (last == 0L) return true
        return (nowMs - last) >= SettingsStore.APP_LOCK_SESSION_MS
    }

    /** Whether a PIN is currently enrolled. */
    fun hasPin(): Boolean = settings.appLockPinHash.isNotBlank()

    /**
     * Enroll or replace the PIN.  Returns [SetPinResult] describing the outcome.
     * Validation is deliberately strict so the stored secret is always within
     * supported bounds; digit-only is NOT enforced so alphanumeric PINs remain
     * possible if the UI chooses to allow them.
     */
    fun setPin(newPin: String): SetPinResult {
        if (newPin.length < MIN_PIN_LENGTH) return SetPinResult.TooShort
        if (newPin.length > MAX_PIN_LENGTH) return SetPinResult.TooLong

        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(newPin.toCharArray(), salt)
        settings.appLockPinSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        settings.appLockPinHash = Base64.encodeToString(hash, Base64.NO_WRAP)
        settings.appLockEnabled = true
        // Mark unlocked so the user isn't immediately prompted after setting a PIN.
        markUnlocked()
        return SetPinResult.Ok
    }

    /**
     * Constant-time compare of a user-entered PIN against the stored hash.
     * Returns true and records [markUnlocked] on match; false otherwise.
     */
    fun verifyPin(candidate: String): Boolean {
        val storedHashB64 = settings.appLockPinHash
        val storedSaltB64 = settings.appLockPinSalt
        if (storedHashB64.isBlank() || storedSaltB64.isBlank()) return false

        val salt       = Base64.decode(storedSaltB64, Base64.NO_WRAP)
        val storedHash = Base64.decode(storedHashB64, Base64.NO_WRAP)
        val candHash   = pbkdf2(candidate.toCharArray(), salt)

        if (!constantTimeEquals(storedHash, candHash)) return false
        markUnlocked()
        return true
    }

    /** Record a successful biometric / PIN verification. */
    fun markUnlocked(nowMs: Long = System.currentTimeMillis()) {
        settings.appLockLastUnlockMs = nowMs
    }

    /** Force-relock the app — next tool call (or MainActivity resume) will prompt. */
    fun lockNow() {
        settings.appLockLastUnlockMs = 0L
    }

    /** Turn the feature off entirely and forget the stored PIN. */
    fun disableLock() {
        settings.clearAppLock()
    }

    /**
     * Whether the user has opted in to biometric unlock in addition to PIN.
     * The MainActivity biometric flow still needs to verify device capability
     * at prompt time via [androidx.biometric.BiometricManager].
     */
    fun biometricOptedIn(): Boolean = settings.appLockBiometricEnabled

    fun setBiometricOptIn(enabled: Boolean) {
        settings.appLockBiometricEnabled = enabled
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun pbkdf2(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, PBKDF2_ITERATIONS, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGO).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Constant-time byte-array compare — avoids leaking PIN length / prefix via timing. */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

sealed class SetPinResult {
    object Ok       : SetPinResult()
    object TooShort : SetPinResult()
    object TooLong  : SetPinResult()
}
