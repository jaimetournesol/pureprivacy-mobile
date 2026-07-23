package ai.tournesol.pureprivacy.security

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The passcode gate for the app: a 6-digit **unlock** code and a 6-digit **duress** code.
 *
 * Codes are NEVER stored in plaintext or in any reversible form. Each is kept as a salted
 * PBKDF2-HMAC-SHA256 hash inside a Keystore-backed [EncryptedSharedPreferences] file (the
 * same at-rest scheme the session tokens use), so the on-disk value is both hashed AND
 * encrypted with a hardware-bound master key. Verification is constant-time
 * ([MessageDigest.isEqual]).
 *
 * This object also owns the brute-force defence: a failed-attempt counter and an escalating
 * lockout schedule that culminates in a wipe at [WIPE_AT_FAILS]. Both are **persisted**, so
 * force-quitting the app between guesses cannot reset the clock.
 *
 * IMPORTANT: this object NEVER destroys user data itself. It only reports a [Verdict]
 * (`UNLOCK` / `DURESS` / `WRONG` / `WIPE`). The single, real wipe path lives in
 * `AppViewModel.duressWipe()` so there is exactly one place that erases the device.
 */
object PasscodeStore {
    private const val PREFS = "pp_lock_enc"

    private const val K_UNLOCK = "unlock"        // "saltB64:hashB64:iters"
    private const val K_DURESS = "duress"
    private const val K_FAILS = "fails"
    private const val K_LOCKED_UNTIL = "locked_until"
    private const val K_TIMEOUT = "lock_timeout_ms"

    private const val ITERS = 120_000
    private const val KEYLEN_BITS = 256
    private const val SALT_BYTES = 16

    /** Length of a valid passcode (see design decision: 6-digit PIN). */
    const val PIN_LENGTH = 6

    /** After this many consecutive wrong entries, [verify] returns [Verdict.WIPE]. */
    const val WIPE_AT_FAILS = 10

    enum class Verdict { UNLOCK, DURESS, WRONG, WIPE }

    private fun prefs(ctx: Context) = run {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, PREFS, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun isConfigured(ctx: Context): Boolean =
        prefs(ctx).let { it.contains(K_UNLOCK) && it.contains(K_DURESS) }

    /** Store both codes (overwriting any previous ones) and reset the brute-force counter.
     *  Caller must have already validated format + that the two codes differ. */
    fun setCodes(ctx: Context, unlock: String, duress: String) {
        prefs(ctx).edit()
            .putString(K_UNLOCK, encode(unlock))
            .putString(K_DURESS, encode(duress))
            .putInt(K_FAILS, 0)
            .putLong(K_LOCKED_UNTIL, 0L)
            .apply()
    }

    /**
     * Check [code] against the stored hashes.
     * - matches unlock  -> [Verdict.UNLOCK] (resets the fail counter)
     * - matches duress  -> [Verdict.DURESS] (caller wipes; do NOT touch the counter)
     * - otherwise       -> record a failure; [Verdict.WIPE] once [WIPE_AT_FAILS] is reached,
     *                      else [Verdict.WRONG]
     *
     * The unlock check is compared FIRST but both stored records are read regardless, to keep
     * timing independent of which code was entered.
     */
    fun verify(ctx: Context, code: String): Verdict {
        val p = prefs(ctx)
        val unlockRec = p.getString(K_UNLOCK, null)
        val duressRec = p.getString(K_DURESS, null)
        val unlockMatch = unlockRec != null && matches(code, unlockRec)
        val duressMatch = duressRec != null && matches(code, duressRec)
        return when {
            unlockMatch -> { resetFailures(ctx); Verdict.UNLOCK }
            duressMatch -> Verdict.DURESS
            else -> {
                val fails = p.getInt(K_FAILS, 0) + 1
                p.edit()
                    .putInt(K_FAILS, fails)
                    .putLong(K_LOCKED_UNTIL, System.currentTimeMillis() + lockoutMsForFails(fails))
                    .apply()
                if (fails >= WIPE_AT_FAILS) Verdict.WIPE else Verdict.WRONG
            }
        }
    }

    fun failCount(ctx: Context): Int = prefs(ctx).getInt(K_FAILS, 0)

    /** Milliseconds still to wait before another attempt is allowed (0 if not locked out). */
    fun lockoutRemainingMs(ctx: Context): Long =
        (prefs(ctx).getLong(K_LOCKED_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun resetFailures(ctx: Context) {
        prefs(ctx).edit().putInt(K_FAILS, 0).putLong(K_LOCKED_UNTIL, 0L).apply()
    }

    /** Auto-lock timeout: how long the app may stay backgrounded before it re-locks.
     *  Default 0 = lock immediately on backgrounding (see design decision). */
    fun lockTimeoutMs(ctx: Context): Long = prefs(ctx).getLong(K_TIMEOUT, 0L)
    fun setLockTimeoutMs(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(K_TIMEOUT, ms.coerceAtLeast(0L)).apply()
    }

    /** Erase the whole passcode store (called by the duress wipe and by a normal sign-out,
     *  so the next sign-in re-prompts for a fresh passcode). */
    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        // Belt-and-braces: also drop the underlying file so nothing lingers.
        runCatching { ctx.deleteSharedPreferences(PREFS) }
    }

    // --- Escalating lockout schedule (iOS-like), wipe handled separately at WIPE_AT_FAILS ---
    private fun lockoutMsForFails(n: Int): Long = when {
        n < 5 -> 0L
        n == 5 -> 60_000L               // 1 min
        n == 6 -> 60_000L               // 1 min
        n == 7 -> 5 * 60_000L           // 5 min
        n == 8 -> 15 * 60_000L          // 15 min
        n == 9 -> 60 * 60_000L          // 1 hr
        else -> 0L                      // 10th attempt -> WIPE (no further waiting)
    }

    // --- Hashing ---

    /** Produce a fresh "salt:hash:iters" record for [code]. */
    private fun encode(code: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(code, salt, ITERS)
        return "${b64(salt)}:${b64(hash)}:$ITERS"
    }

    /** Constant-time compare of [code] against a stored "salt:hash:iters" record. */
    private fun matches(code: String, record: String): Boolean {
        val parts = record.split(":")
        if (parts.size != 3) return false
        val salt = runCatching { unb64(parts[0]) }.getOrNull() ?: return false
        val expected = runCatching { unb64(parts[1]) }.getOrNull() ?: return false
        val iters = parts[2].toIntOrNull() ?: return false
        val actual = pbkdf2(code, salt, iters)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun pbkdf2(code: String, salt: ByteArray, iters: Int): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), salt, iters, KEYLEN_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
