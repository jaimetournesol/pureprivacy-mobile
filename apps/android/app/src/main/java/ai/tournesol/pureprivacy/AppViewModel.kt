package ai.tournesol.pureprivacy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.tournesol.pureprivacy.matrix.MatrixRepo
import ai.tournesol.pureprivacy.security.PasscodeStore
import ai.tournesol.pureprivacy.tor.TorManager
import ai.tournesol.pureprivacy.util.mapError
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed class Screen {
    /** Cold-start branded loading screen — shown while Tor boots and a saved
     *  session restores, so a returning user never sees an empty login form. */
    data object Splash : Screen()
    data object Login : Screen()
    /** The ecosystem home: an apps grid (Messaging, PP Config). Landing after unlock. */
    data object Home : Screen()
    data object Rooms : Screen()
    /** PP Config — the box dashboard (feature B). */
    data object Config : Screen()
    data object Profile : Screen()
    /** "Go dark": Tor + sync are torn down and the chat list is hidden behind a calm
     *  offline wall. A privacy control — nothing goes in or out until Resume. Survives
     *  app restarts (persisted), so re-opening the app while paused stays dark. */
    data object Paused : Screen()
    data class Chat(val roomId: String, val roomName: String) : Screen()
}

/** The passcode gate, drawn *in front of* the normal [Screen] (see MainActivity). Kept
 *  independent of the Screen state machine so the lock never disturbs cold-start restore. */
enum class Gate {
    /** No gate — render the normal [Screen]. */
    Open,
    /** Passcode required — [MainActivity] draws the lock screen over everything. */
    Locked,
    /** First run / upgrade: the user must set their unlock + duress codes. */
    NeedsSetup,
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val torState = TorManager.state
    val rooms = MatrixRepo.rooms
    val messages = MatrixRepo.messages
    val status = MatrixRepo.status

    val screen = MutableStateFlow<Screen>(Screen.Splash)
    val error = MutableStateFlow<String?>(null)
    /** Transient, non-error heads-up (e.g. "pairing request sent"). Shown as a snackbar. */
    val notice = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    fun clearNotice() { notice.value = null }

    // --- Passcode gate (feature C) -------------------------------------------------------
    /** Drawn in front of [screen] by MainActivity. See [Gate]. */
    val gate = MutableStateFlow(Gate.Open)
    /** Transient lock-screen error ("Wrong code"). The LockScreen clears its dots on change. */
    val lockError = MutableStateFlow<String?>(null)
    /** Epoch-ms until which entry is locked out after wrong attempts (0 = not locked out).
     *  The LockScreen ticks a countdown off this. */
    val lockoutUntilMs = MutableStateFlow(0L)
    val pinLength = PasscodeStore.PIN_LENGTH
    /** Wall-clock of the last time the whole app went to background, for the auto-lock timeout. */
    @Volatile private var backgroundedAt = 0L

    /** After a successful sign-in with no passcode yet, force the user to set one. */
    private fun maybePromptSetup() {
        if (!PasscodeStore.isConfigured(getApplication())) gate.value = Gate.NeedsSetup
    }

    /** Process went to background (whole app, not an in-app activity hop). */
    fun onEnterBackground() { backgroundedAt = System.currentTimeMillis() }

    /** Process came to foreground. Re-lock if configured and the timeout has elapsed.
     *  Immediate by default (lockTimeoutMs = 0). Never locks over an in-progress setup. */
    fun onEnterForeground() {
        val app = getApplication<Application>()
        if (!PasscodeStore.isConfigured(app)) return
        if (gate.value != Gate.Open) return
        val elapsed = if (backgroundedAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - backgroundedAt
        if (elapsed >= PasscodeStore.lockTimeoutMs(app)) {
            lockError.value = null
            lockoutUntilMs.value = System.currentTimeMillis() + PasscodeStore.lockoutRemainingMs(app)
            gate.value = Gate.Locked
        }
    }

    /** Verify an entered code. Runs PBKDF2 off the main thread. Unlock opens the gate;
     *  the duress code (or the 10th wrong attempt) triggers the self-destruct wipe. */
    fun submitPasscode(code: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val app = getApplication<Application>()
            if (PasscodeStore.lockoutRemainingMs(app) > 0) {
                lockoutUntilMs.value = System.currentTimeMillis() + PasscodeStore.lockoutRemainingMs(app)
                return@launch
            }
            when (PasscodeStore.verify(app, code)) {
                PasscodeStore.Verdict.UNLOCK -> {
                    lockError.value = null; lockoutUntilMs.value = 0L; gate.value = Gate.Open
                }
                PasscodeStore.Verdict.DURESS, PasscodeStore.Verdict.WIPE -> duressWipe()
                PasscodeStore.Verdict.WRONG -> {
                    val remaining = PasscodeStore.lockoutRemainingMs(app)
                    lockoutUntilMs.value = if (remaining > 0) System.currentTimeMillis() + remaining else 0L
                    lockError.value = "Wrong code"
                }
            }
        }
    }

    /** Store the two codes during first-run setup, then open the app. Validates format +
     *  that the codes differ (the UI already confirms each twice). */
    fun setPasscodes(unlock: String, duress: String) {
        val app = getApplication<Application>()
        if (unlock.length != pinLength || duress.length != pinLength ||
            !unlock.all { it.isDigit() } || !duress.all { it.isDigit() }) {
            lockError.value = "Codes must be $pinLength digits"; return
        }
        if (unlock == duress) { lockError.value = "Your two codes must be different"; return }
        viewModelScope.launch(Dispatchers.Default) {
            PasscodeStore.setCodes(app, unlock, duress)
            lockError.value = null
            gate.value = Gate.Open
        }
    }

    /** Duress self-destruct: wipe ALL local app data (session + crypto store via a
     *  local-first [MatrixRepo.duressWipe], Tor data dir, caches, and the passcodes) and
     *  land on a neutral Login — no indication a wipe happened. Irreversible. */
    private fun duressWipe() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { PpSyncService.stop(app) }
            runCatching { MatrixRepo.duressWipe(app) }       // local-first: session + crypto store
            runCatching { TorManager.stop() }
            runCatching { java.io.File(app.filesDir, "tor").deleteRecursively() }  // guards + descriptor cache
            runCatching { app.cacheDir.deleteRecursively() }                       // cached media/thumbs
            PasscodeStore.clear(app)                          // forget the codes too
            isPaused = false; paused.value = false
            lockError.value = null; lockoutUntilMs.value = 0L
            gate.value = Gate.Open                            // reveal the neutral Login underneath
            screen.value = Screen.Login
            // Re-boot Tor for the next sign-in (its data dir was just wiped → fresh guards).
            viewModelScope.launch(Dispatchers.IO) { runCatching { TorManager.start(app) } }
        }
    }

    /** How the cold-start session restore is going, for the SplashScreen. A RETURNING
     *  user (saved session) must never silently fall through to a bare login form, so
     *  we surface restore as its own state: Working while it's progressing, Slow once
     *  it's taken long enough that the user deserves a "this is the slow part — keep
     *  waiting / try again" affordance (with a retry), rather than a frozen splash. */
    enum class RestorePhase { Working, Slow }
    val restorePhase = MutableStateFlow(RestorePhase.Working)

    /** A scanned/opened "pureprivacy://connect?…" setup code, parsed into the box's
     *  onion + the owner's username, ready to pre-fill the login form. The LoginScreen
     *  observes this and fills its fields (password left for the user — see
     *  loginFromConnectUri for why token-login isn't wired). Cleared once consumed. */
    data class Prefill(val onion: String, val user: String)
    val loginPrefill = MutableStateFlow<Prefill?>(null)
    fun clearLoginPrefill() { loginPrefill.value = null }

    /** This user's Matrix address (@name:onion) — the payload behind "my code". */
    val myId: String get() = MatrixRepo.userId

    /** Tell the repo whether our UI is on screen, so the background notification
     *  poll can slow down when the app isn't visible (battery). */
    fun setForeground(foreground: Boolean) = MatrixRepo.onForeground(foreground)

    /** Guards against two overlapping restore attempts (e.g. a retry tap arriving
     *  while one is still running). */
    @Volatile private var restoring = false

    init {
        // Passcode gate (feature C): if the user has set codes, lock immediately on cold
        // start — the lock is drawn over whatever the restore below reaches, so it never
        // interferes with restore. A configured-but-locked-out relaunch restores the
        // countdown from the persisted lockout timestamp.
        if (PasscodeStore.isConfigured(getApplication())) {
            gate.value = Gate.Locked
            lockoutUntilMs.value = System.currentTimeMillis() + PasscodeStore.lockoutRemainingMs(getApplication())
        }
        if (isPaused) {
            // Stay dark on launch: don't boot Tor or restore the session, and hide the
            // chat list. The user explicitly paused; honour it until they Resume.
            screen.value = Screen.Paused
        } else {
            // Tor runs for the lifetime of the app; start() blocks reading its log.
            viewModelScope.launch(Dispatchers.IO) { TorManager.start(getApplication()) }
            startRestore()
        }
        // [H1] A HARD auth error (revoked token / dead session) flips MatrixRepo.authExpired;
        // route the user to Login so they re-authenticate instead of staring at a stuck
        // "Reconnecting" with a dead token. Transient/soft errors never set this.
        viewModelScope.launch {
            MatrixRepo.authExpired.collect { expired ->
                if (expired && screen.value !is Screen.Login) {
                    restoring = false
                    error.value = "You were signed out. Please sign in again."
                    screen.value = Screen.Login
                }
            }
        }
    }

    /** Restore a saved session (if any) once Tor is up, and jump to the chats.
     *  We open on Splash; a first-run user (no saved session) drops straight to Login,
     *  while a RETURNING user waits on the branded splash. The wait is bounded and the
     *  splash NARRATES it: if restore takes long the splash escalates to a recoverable
     *  "still connecting — keep waiting / try again" (RestorePhase.Slow) instead of
     *  silently dumping the user onto a blank login that reads as "I got logged out".
     *  Re-entrant: a "Try again" tap (retryRestore) re-runs this. */
    fun startRestore() {
        if (restoring) return
        restoring = true
        restorePhase.value = RestorePhase.Working
        screen.value = Screen.Splash
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!MatrixRepo.hasSavedSession(getApplication())) {
                    screen.value = Screen.Login          // nothing to restore — sign in
                    return@launch
                }
                // Wait for Tor, but never forever: after ~45s of waiting we surface the
                // recoverable "slow part" splash (with a retry) rather than freezing.
                var waited = 0
                while (TorManager.state.value !is TorManager.State.Ready && waited < 120) {
                    // A hard Tor failure becomes a recoverable splash (retry restarts
                    // Tor), NOT a silent fall-through to login.
                    if (TorManager.state.value is TorManager.State.Failed) { restorePhase.value = RestorePhase.Slow; return@launch }
                    if (waited >= 45) restorePhase.value = RestorePhase.Slow
                    kotlinx.coroutines.delay(1000); waited++
                }
                if (TorManager.state.value !is TorManager.State.Ready) { restorePhase.value = RestorePhase.Slow; return@launch }
                // Restore can stall when Tor is flaky (the SDK runs a networked sliding-sync
                // discovery during client build). Bound each attempt; on the second slow
                // attempt escalate to the recoverable splash instead of looping silently.
                var restored = false
                for (attempt in 1..2) {
                    if (attempt == 2) restorePhase.value = RestorePhase.Slow
                    val ok = kotlinx.coroutines.withTimeoutOrNull(30_000) {
                        runCatching { MatrixRepo.tryRestore(getApplication()) }.getOrDefault(false)
                    }
                    if (ok == true) { restored = true; break }
                    kotlinx.coroutines.delay(1500)        // brief pause; a new Tor circuit may help
                }
                if (restored) {
                    runCatching {
                        MatrixRepo.startSync()
                        PpSyncService.start(getApplication())
                        screen.value = Screen.Home
                        consumePendingContact()
                        // Upgrade path: an existing user (session restored) with no passcode
                        // yet is prompted to set one on first launch of this version. If a
                        // passcode IS set, init() already locked the gate — this is a no-op.
                        maybePromptSetup()
                    }.onFailure {
                        // Sync failed to start — recoverable, not "signed out". Keep the
                        // saved session and offer a retry from the splash.
                        restorePhase.value = RestorePhase.Slow
                    }
                } else {
                    // Couldn't restore over Tor — offer a retry, don't drop a returning
                    // user onto a bare login form with no explanation.
                    restorePhase.value = RestorePhase.Slow
                }
            } finally {
                restoring = false
            }
        }
    }

    /** "Try again" on the slow/stuck splash: kick Tor back into bootstrapping and
     *  re-attempt the restore. For a returning user this is the recovery path; it
     *  never lands them on a blank login without explanation. */
    fun retryRestore() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { TorManager.retry(getApplication()) }
        }
        startRestore()
    }

    /** Last-resort escape from a stuck restore: the user explicitly chooses to sign in
     *  again. Only reachable from the recoverable splash (never silently). */
    fun signInInstead() {
        restoring = false
        error.value = null
        screen.value = Screen.Login
    }

    /** Retry Tor from anywhere it's surfaced (the status badge, a login error). Kicks
     *  the embedded Tor back into bootstrapping; observers (the badge) update live. */
    fun retryTor() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { TorManager.retry(getApplication()) } }
    }

    fun login(onion: String, user: String, pass: String) {
        error.value = null
        busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Wait until Tor is ready before talking to the box.
                var waited = 0
                while (TorManager.state.value !is TorManager.State.Ready && waited < 120) {
                    if (TorManager.state.value is TorManager.State.Failed)
                        throw IllegalStateException("Tor failed: ${(TorManager.state.value as TorManager.State.Failed).reason}")
                    kotlinx.coroutines.delay(1000); waited++
                }
                val hs = normalizeHomeserver(onion)
                MatrixRepo.login(getApplication(), hs, user.trim(), pass)
                MatrixRepo.startSync()
                PpSyncService.start(getApplication())
                screen.value = Screen.Home
                consumePendingContact()
                maybePromptSetup()   // first sign-in with no passcode -> force setup (feature C)
            } catch (t: Throwable) {
                Log.w("AppVM", "login failed", t)
                error.value = mapError(t)
            } finally {
                busy.value = false
            }
        }
    }

    private fun normalizeHomeserver(raw: String): String {
        var s = raw.trim()
        // The PurePrivacy box (pureprivacy-desktop) serves the Matrix client API as
        // plain http on onion:8008 — the .onion IS the encryption layer, so no TLS
        // is needed (and the Element Call bridge serves localhost-http to the WebView
        // regardless). The user just enters their box's .onion; we form the URL.
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        val afterScheme = s.substringAfter("://")
        if (!afterScheme.contains(":")) s = "$s:8008"
        return s
    }

    fun startChat(rawUserId: String) {
        val uid = rawUserId.trim()
        if (!uid.startsWith("@") || !uid.contains(":")) {
            error.value = "Enter a full address, e.g. @bob:xxxx.onion"
            return
        }
        // Validate the server is a real v3 onion before we record consent / pair —
        // a scanned/typed address drives a federation-allowlist write on our box.
        val server = uid.substringAfter(":")
        if (!Regex("^[a-z2-7]{56}\\.onion$").matches(server)) {
            error.value = "That doesn't look like a valid PurePrivacy address."
            return
        }
        error.value = null
        busy.value = true
        val who = uid.removePrefix("@").substringBefore(":")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val r = MatrixRepo.startChat(uid)
                if (r.paired) {
                    // both have scanned — open the live conversation.
                    kotlinx.coroutines.delay(1200)   // let the room settle in sync
                    MatrixRepo.openRoom(r.roomId)
                    screen.value = Screen.Chat(r.roomId, who)
                } else {
                    // mutual consent: our request is recorded, waiting for them to
                    // scan us back. Stay on the chat list; show a gentle heads-up.
                    notice.value = "Request sent to $who. You'll connect once they scan your code too."
                    screen.value = Screen.Rooms
                }
            } catch (t: Throwable) {
                Log.w("AppVM", "startChat failed", t)
                error.value = mapError(t)
            } finally {
                busy.value = false
            }
        }
    }

    /** Remove a contact (@user:onion). Destructive by default: cuts them from our box's
     *  federation allowlist (account-data drop → box reconcile). [notify] true also
     *  federates a "left" event; false removes them silently. Mirrors [startChat]:
     *  validate, mark busy, do the work off the main thread, surface notice/error. */
    fun removeContact(peerId: String, notify: Boolean) {
        val uid = peerId.trim()
        if (!uid.startsWith("@") || !uid.contains(":")) {
            error.value = "Enter a full address, e.g. @bob:xxxx.onion"
            return
        }
        val server = uid.substringAfter(":")
        if (!Regex("^[a-z2-7]{56}\\.onion$").matches(server)) {
            error.value = "That doesn't look like a valid PurePrivacy address."
            return
        }
        error.value = null
        busy.value = true
        val who = uid.removePrefix("@").substringBefore(":")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                MatrixRepo.removeContact(uid, notify)
                notice.value = "Removed $who."
            } catch (t: Throwable) {
                Log.w("AppVM", "removeContact failed", t)
                error.value = mapError(t)
            } finally {
                busy.value = false
            }
        }
    }

    /** A contact's code was scanned (or pasted). Normalize whatever the QR carried
     *  into a Matrix id and open/create the encrypted DM. */
    fun addContact(scanned: String?) {
        val raw = scanned?.trim().orEmpty()
        if (raw.isEmpty()) return
        // A "pureprivacy://connect?…" SETUP code is a sign-in handoff, not a contact —
        // route it to the login pre-fill instead of trying to start a chat (which,
        // pre-fix, would create a chat-with-myself from the box owner's own address).
        // Belt-and-braces: onDeepLink/the Login scanner already split these out, but
        // any caller that funnels a raw scan here (Rooms/Profile scanners) is covered.
        if (isConnectUri(raw)) { loginFromConnectUri(raw); return }
        // Pull the @name:onion out of whatever wrapper the QR / deep link carried:
        // "pureprivacy://contact/@bob:onion", "pureprivacy:@bob:onion",
        // "matrix:u/bob:onion", or a bare "@bob:onion".
        val match = Regex("@?[A-Za-z0-9._=+\\-]+:[a-z2-7]{56}\\.onion").find(raw)
        var id = match?.value ?: raw
        if (!id.startsWith("@")) id = "@$id"
        startChat(id)
    }

    /** A `pureprivacy://…` link was opened (system camera scanned a QR, or a tapped
     *  link). Two distinct kinds travel on the same scheme, so route by type:
     *   - `pureprivacy://connect?hs=…&user=…&token=…` — the desktop's "Connect your
     *     phone" SETUP code. This is a sign-in handoff (first-run "your box in your
     *     pocket"), NOT a contact — it must go to the login path, never addContact.
     *   - everything else (`pureprivacy:@name:onion`, `pureprivacy://contact/…`, a
     *     bare `@name:onion`) — a CONTACT's code → addContact (unchanged behaviour).
     *  If we're signed in, contacts are added now; otherwise stashed and replayed
     *  once a session is ready, so the link is never lost. */
    private var pendingContact: String? = null
    fun onDeepLink(uri: String?) {
        val raw = uri?.trim().orEmpty()
        if (raw.isEmpty()) return
        if (isConnectUri(raw)) { loginFromConnectUri(raw); return }
        if (Regex("[a-z2-7]{56}\\.onion").containsMatchIn(raw).not()) return  // not an address link
        when (screen.value) {
            is Screen.Rooms, is Screen.Chat, is Screen.Profile -> addContact(raw)
            // Splash (restoring) or Login: stash it. The normal flow lands on Rooms
            // (returning user) or Login→sign-in; consumePendingContact runs at both.
            else -> pendingContact = raw
        }
    }

    /** True for the desktop's setup handoff URI: `pureprivacy://connect?…`. */
    private fun isConnectUri(raw: String): Boolean =
        raw.startsWith("pureprivacy://connect", ignoreCase = true)

    /** The desktop "Connect your phone" code: `pureprivacy://connect?hs=<onion>&
     *  user=<username>&token=<hex>` (pureprivacy-desktop, commands.rs get_connect_qr).
     *
     *  Token-login note: the `token` here is a 16-byte hex *pairing nonce* the box
     *  generates at setup — it is NOT a Matrix `m.login.token`/one-time login token,
     *  and the box never exchanges it for a session (it authenticates the phone with
     *  `m.login.password` against the admin password). matrix-rust-sdk 26.06.11 also
     *  exposes no `m.login.token` login method (only password / email / JWT / OAuth /
     *  SSO / QR-rendezvous). So we can't log in straight from the QR. Instead we PRE-
     *  FILL the login form with the box's onion + username — turning a 56-char onion
     *  + a username into a one-scan, password-only sign-in. The user lands on Login
     *  with the hard parts filled; they just type the password they set on the desktop.
     *  Scanning a setup code therefore never routes into addContact (which would try
     *  to start a chat with the box owner's own address). */
    fun loginFromConnectUri(uri: String?) {
        val raw = uri?.trim().orEmpty()
        if (!isConnectUri(raw)) return
        val parsed = runCatching { android.net.Uri.parse(raw) }.getOrNull()
        val hs = parsed?.getQueryParameter("hs")?.trim().orEmpty()
        val user = parsed?.getQueryParameter("user")?.trim().orEmpty()
        if (hs.isBlank() || !Regex("^[a-z2-7]{56}\\.onion$").matches(hs)) {
            error.value = "That doesn't look like a valid PurePrivacy setup code."
            screen.value = Screen.Login
            return
        }
        error.value = null
        loginPrefill.value = Prefill(hs, user)
        notice.value = "Box found — enter the password you set in the desktop app."
        // A code scanned mid-restore (Splash) or already on Login: land on Login so
        // the pre-filled form is visible. (We never auto-submit: the token isn't a
        // login credential, so the password is still required.)
        screen.value = Screen.Login
    }

    private fun consumePendingContact() {
        val p = pendingContact ?: return
        pendingContact = null
        addContact(p)
    }

    fun showProfile() { error.value = null; screen.value = Screen.Profile }
    fun openRooms() { error.value = null; screen.value = Screen.Rooms }

    // --- Apps grid (feature E) + PP Config (feature B) ----------------------------------
    fun goHome() { error.value = null; screen.value = Screen.Home }
    fun openMessaging() { error.value = null; screen.value = Screen.Rooms }
    fun openConfig() { error.value = null; screen.value = Screen.Config; loadBoxStatus() }

    val boxStatus = MutableStateFlow<MatrixRepo.BoxStatus?>(null)
    val configBusy = MutableStateFlow(false)
    val configNotice = MutableStateFlow<String?>(null)
    fun clearConfigNotice() { configNotice.value = null }

    /** Read the box's published status (health/address/version/pairings) for PP Config. */
    fun loadBoxStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { MatrixRepo.readBoxStatus() }.getOrNull()?.let { boxStatus.value = it }
        }
    }

    /** The sealed backup envelope, once the box has produced one — the UI then asks the user
     *  where to save it. Already encrypted with their passphrase; we never hold the passphrase. */
    val backupEnvelope = MutableStateFlow<String?>(null)
    fun clearBackupEnvelope() { backupEnvelope.value = null }

    /** Ask the box for an encrypted identity backup, sealed with [passphrase] (feature D).
     *  The passphrase is sent once in the guarded command and never stored on this device. */
    fun backupBox(passphrase: String) {
        if (passphrase.length < 8) {
            configNotice.value = "Use a backup passphrase of at least 8 characters."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            configBusy.value = true
            configNotice.value = "Asking your box for a backup…"
            val id = runCatching { MatrixRepo.sendBoxCommand("backup", passphrase) }.getOrNull()
            if (id == null) {
                configNotice.value = "Couldn't reach your box."; configBusy.value = false; return@launch
            }
            var env: String? = null
            for (i in 1..20) {
                kotlinx.coroutines.delay(2000)
                env = runCatching { MatrixRepo.readBackupEnvelope(id) }.getOrNull()
                if (env != null) break
            }
            configBusy.value = false
            if (env == null) {
                configNotice.value = "Your box didn't return a backup — try again."
            } else {
                backupEnvelope.value = env
                configNotice.value = "Backup ready — choose where to save it."
            }
        }
    }

    /** Restart the box's services (safe) via the guarded command channel. */
    fun restartBox() {
        viewModelScope.launch(Dispatchers.IO) {
            configBusy.value = true
            val id = runCatching { MatrixRepo.sendBoxCommand("restart") }.getOrNull()
            if (id == null) { configNotice.value = "Couldn't reach your box."; configBusy.value = false; return@launch }
            configNotice.value = "Restarting your box…"
            repeat(20) {
                kotlinx.coroutines.delay(2000)
                if (runCatching { MatrixRepo.readCommandResult(id) }.getOrNull() == true) {
                    configNotice.value = "Your box is restarting."
                }
            }
            configBusy.value = false
            loadBoxStatus()
        }
    }

    /** Factory-reset the box — DESTRUCTIVE (wipes the onion, unrecoverable). Gated behind
     *  typing the box name (see PP Config). After reset the box identity is gone, so we
     *  sign out locally to a fresh state. */
    fun resetBox(typedName: String) {
        val expected = boxStatus.value?.boxName?.trim().orEmpty()
        if (expected.isEmpty() || typedName.trim() != expected) {
            configNotice.value = "That doesn't match your box's name."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            configBusy.value = true
            val id = runCatching { MatrixRepo.sendBoxCommand("reset") }.getOrNull()
            if (id == null) { configNotice.value = "Couldn't reach your box."; configBusy.value = false; return@launch }
            configNotice.value = "Resetting your box…"
            kotlinx.coroutines.delay(4000)   // let the box ack + begin wiping
            // The box + its account are being destroyed — sign out locally to a fresh state.
            runCatching { PpSyncService.stop(getApplication()) }
            runCatching { MatrixRepo.logout(getApplication()) }
            PasscodeStore.clear(getApplication())
            configBusy.value = false
            gate.value = Gate.Open
            screen.value = Screen.Login
        }
    }

    /** Persisted "paused" flag so a Pause survives an app restart (privacy: re-opening
     *  the app while paused must stay dark, not silently reconnect). */
    private fun appPrefs() =
        getApplication<Application>().getSharedPreferences("pp_app", android.content.Context.MODE_PRIVATE)
    private var isPaused: Boolean
        get() = appPrefs().getBoolean("paused", false)
        set(v) { appPrefs().edit().putBoolean("paused", v).apply() }
    val paused = MutableStateFlow(false).also { it.value = isPaused }

    /** Opt-in read receipts. OFF by default — a deliberate privacy stance: a contact
     *  only learns you've read their message if you turn this on (and only then do you
     *  see when they've read yours). MatrixRepo reads the same pref to decide whether to
     *  federate an `m.read` receipt. */
    private var sendReceipts: Boolean
        get() = appPrefs().getBoolean("send_read_receipts", false)
        set(v) { appPrefs().edit().putBoolean("send_read_receipts", v).apply() }
    val readReceipts = MutableStateFlow(false).also { it.value = sendReceipts }
    fun setReadReceipts(on: Boolean) {
        sendReceipts = on; readReceipts.value = on
        // Turning it on: send a receipt for whatever's on screen right now, so the peer's
        // "Read" tick updates immediately instead of waiting for the next message.
        if (on) viewModelScope.launch(Dispatchers.IO) { runCatching { MatrixRepo.onReadReceiptsToggled() } }
    }

    /** Human display name shown to paired peers above your messages (instead of your
     *  onion localpart). Cached locally to prefill the editor; the source of truth peers
     *  see is the federated profile set via [MatrixRepo.setDisplayName]. Blank = localpart. */
    private var displayNamePref: String
        get() = appPrefs().getString("display_name", "") ?: ""
        set(v) { appPrefs().edit().putString("display_name", v).apply() }
    val displayName = MutableStateFlow("").also { it.value = displayNamePref }
    fun setDisplayName(name: String) {
        val n = name.trim().take(64)
        displayNamePref = n; displayName.value = n
        viewModelScope.launch(Dispatchers.IO) { runCatching { MatrixRepo.setDisplayName(n) } }
        notice.value = if (n.isEmpty()) "Name cleared" else "Name updated"
    }

    /** Our own avatar (mxc:// URL) for the Profile header; peers see the same via federation. */
    val myAvatar = MatrixRepo.myAvatar
    fun setAvatar(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { MatrixRepo.setAvatar(getApplication(), uri) }
                .onSuccess { notice.value = "Profile picture updated" }
                .onFailure { error.value = "Couldn't set profile picture" }
        }
    }

    // ── Voice notes ─────────────────────────────────────────────────────────────
    private val voiceRecorder by lazy { ai.tournesol.pureprivacy.audio.VoiceRecorder(getApplication()) }
    /** True while recording — the composer shows the recording bar instead of the input. */
    val recording = MutableStateFlow(false)
    /** Elapsed recording time (ms), ticked while [recording] so the bar shows a live timer. */
    val recordElapsed = MutableStateFlow(0L)
    /** Set true when we need the RECORD_AUDIO permission — MainActivity observes it, asks,
     *  and calls [onMicPermission] with the result. */
    val micPermissionNeeded = MutableStateFlow(false)
    /** Key of the voice note currently playing (drives the play/pause icon), or null. */
    val playingVoice = MutableStateFlow<String?>(null)
    /** Key of the voice note currently downloading over Tor (drives a spinner on the play
     *  button) — the first tap fetches the clip, which can take a moment over Tor. */
    val loadingVoice = MutableStateFlow<String?>(null)
    private var recordTicker: kotlinx.coroutines.Job? = null

    fun canRecordVoice(): Boolean = voiceRecorder.supported()

    /** Start a voice note. If the mic permission isn't granted we raise
     *  [micPermissionNeeded] and let the UI request it, then it calls us back. */
    fun startRecording() {
        if (!voiceRecorder.supported()) { notice.value = "Voice notes need Android 10 or newer"; return }
        val ctx = getApplication<Application>()
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micPermissionNeeded.value = true; return
        }
        beginRecording()
    }

    /** Called by the UI after the RECORD_AUDIO prompt resolves. */
    fun onMicPermission(granted: Boolean) {
        micPermissionNeeded.value = false
        if (granted) beginRecording() else notice.value = "Microphone permission is needed for voice notes"
    }

    private fun beginRecording() {
        if (!voiceRecorder.start()) { notice.value = "Couldn't start recording"; return }
        recording.value = true; recordElapsed.value = 0L
        recordTicker = viewModelScope.launch {
            while (recording.value) { recordElapsed.value = voiceRecorder.elapsedMs; delay(100) }
        }
    }

    /** Discard the in-progress recording (user tapped the ✕). */
    fun cancelRecording() {
        recordTicker?.cancel(); recording.value = false; recordElapsed.value = 0L
        viewModelScope.launch(Dispatchers.IO) { runCatching { voiceRecorder.cancel() } }
    }

    /** Stop recording and send the voice note (user tapped the send arrow). */
    fun stopAndSendRecording() {
        recordTicker?.cancel(); recording.value = false
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching { voiceRecorder.stop() }.getOrNull()
            recordElapsed.value = 0L
            if (res == null) { notice.value = "Voice note too short"; return@launch }
            val (path, dur, wave) = res
            runCatching { MatrixRepo.sendVoiceMessage(path, dur, wave) }
                .onFailure { error.value = "Couldn't send voice note" }
        }
    }

    /** Play (or stop, if already playing) a received voice note. The first tap downloads
     *  the clip over Tor — which can take a moment — so we surface a [loadingVoice] spinner
     *  meanwhile (the bytes are cached, so replays are instant). Then [playingVoice] flips
     *  the icon to a stop control. */
    fun playVoice(m: ai.tournesol.pureprivacy.matrix.ChatMsg) {
        val media = m.media ?: return
        // Ignore repeat taps while it's already fetching this clip.
        if (loadingVoice.value == m.key) return
        viewModelScope.launch(Dispatchers.IO) {
            if (ai.tournesol.pureprivacy.audio.AudioPlayer.currentKey == m.key) {
                ai.tournesol.pureprivacy.audio.AudioPlayer.stop(); playingVoice.value = null; return@launch
            }
            loadingVoice.value = m.key
            val bytes = runCatching { MatrixRepo.mediaBytes(m.key, media) }.getOrNull()
            loadingVoice.value = null
            if (bytes == null) { notice.value = "Couldn't load voice note"; return@launch }
            playingVoice.value = m.key
            ai.tournesol.pureprivacy.audio.AudioPlayer.toggle(getApplication(), m.key, bytes) {
                playingVoice.value = null
            }
        }
    }

    /** Pause / "go dark": tear down sync + Tor and hide the chat list, WITHOUT signing
     *  out (session + keys stay). Peers' messages queue on your box (your computer) and
     *  arrive on Resume. Persisted so it holds across app restarts. */
    fun pause() {
        isPaused = true; paused.value = true
        val app = getApplication<Application>()
        screen.value = Screen.Paused
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { MatrixRepo.pauseSync() }   // stop the sync stream, keep the session
            runCatching { PpSyncService.stop(app) }  // drop the foreground service + its notification
            runCatching { TorManager.stop() }        // go offline — no circuits, nothing in or out
        }
    }

    /** Resume from [pause]: bring Tor back up, restore/resume the session, restart the
     *  background service, and return to the chats. Mirrors the cold-start restore. */
    fun resume() {
        isPaused = false; paused.value = false
        val app = getApplication<Application>()
        restorePhase.value = RestorePhase.Working
        screen.value = Screen.Splash
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { TorManager.start(app) }        // blocks until tor exits; fire-and-forget below
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Wait (bounded) for Tor to be Ready, then restore the session + resume sync.
            var waited = 0
            while (TorManager.state.value !is TorManager.State.Ready && waited < 90) { kotlinx.coroutines.delay(1000); waited++ }
            if (!MatrixRepo.isLoggedIn) runCatching { MatrixRepo.tryRestore(app) }
            runCatching { MatrixRepo.startSync() }
            runCatching { PpSyncService.start(app) }
            screen.value = if (MatrixRepo.isLoggedIn) Screen.Rooms else Screen.Login
        }
    }

    fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { MatrixRepo.logout(getApplication()) }
            PasscodeStore.clear(getApplication())   // forget the passcode -> re-sign-in re-prompts (feature C)
            isPaused = false; paused.value = false
            gate.value = Gate.Open
            screen.value = Screen.Login
        }
    }

    /** "Erase this phone": everything [logout] wipes (session + crypto store) PLUS the
     *  Tor data dir (guards / onion-descriptor cache) and app caches — a true local wipe
     *  that leaves no trace on the device. Your box + chats live on your computer, so a
     *  fresh sign-in restores them. Destructive; the UI gates it behind a confirm. */
    fun eraseDevice() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { PpSyncService.stop(app) }
            runCatching { MatrixRepo.logout(app) }          // session + crypto store
            runCatching { TorManager.stop() }
            runCatching { java.io.File(app.filesDir, "tor").deleteRecursively() }   // guards + descriptor cache
            runCatching { app.cacheDir.deleteRecursively() }                        // any cached media/thumbs
            PasscodeStore.clear(app)                        // forget the passcode too (feature C)
            isPaused = false; paused.value = false
            gate.value = Gate.Open
            // Re-boot Tor for the next sign-in (its data dir was just wiped → fresh guards).
            viewModelScope.launch(Dispatchers.IO) { runCatching { TorManager.start(app) } }
            screen.value = Screen.Login
        }
    }

    fun openRoom(id: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                MatrixRepo.openRoom(id)
                screen.value = Screen.Chat(id, name)
            } catch (t: Throwable) {
                Log.w("AppVM", "openRoom failed", t)
                error.value = mapError(t)
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { runCatching { MatrixRepo.send(text) } }
    }

    // Compose target: when set, the input bar shows a "Replying…" / "Editing…" banner and
    // the send action routes to reply/edit instead of a new message. Mutually exclusive.
    val replyTarget = MutableStateFlow<ai.tournesol.pureprivacy.matrix.ChatMsg?>(null)
    val editTarget = MutableStateFlow<ai.tournesol.pureprivacy.matrix.ChatMsg?>(null)
    fun startReply(m: ai.tournesol.pureprivacy.matrix.ChatMsg) { editTarget.value = null; replyTarget.value = m }
    fun startEdit(m: ai.tournesol.pureprivacy.matrix.ChatMsg) { replyTarget.value = null; editTarget.value = m }
    fun cancelCompose() { replyTarget.value = null; editTarget.value = null }

    /** Send the composer text — an EDIT if editing, a REPLY if replying, else a new
     *  message. Clears the compose target afterwards. */
    fun composeSend(text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        val edit = editTarget.value; val reply = replyTarget.value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                when {
                    edit?.eventId != null -> MatrixRepo.editMessage(edit.eventId, t)
                    reply?.eventId != null -> MatrixRepo.replyToMessage(reply.eventId, t)
                    else -> MatrixRepo.send(t)
                }
            }
        }
        replyTarget.value = null; editTarget.value = null
    }

    fun deleteMessage(key: String) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { MatrixRepo.deleteMessage(key) } }
    }
    fun toggleReaction(key: String, emoji: String) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { MatrixRepo.toggleReaction(key, emoji) } }
    }

    /** Re-send a message whose local echo is in the Failed state — tap-to-retry on a
     *  "Not sent" bubble. Drives the SDK's own resend path; the timeline re-emits the
     *  item (sending → sent / failed), so the bubble updates itself. */
    fun retrySend(key: String) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { MatrixRepo.retrySend(key) } }
    }

    /** Send a picked file/image as an attachment (E2EE, over Tor). */
    fun sendFile(uri: android.net.Uri) {
        notice.value = "Sending file over Tor…"
        viewModelScope.launch(Dispatchers.IO) { runCatching { MatrixRepo.sendFile(getApplication(), uri) } }
    }

    /** Download a received attachment and save it to Downloads. */
    fun saveAttachment(m: ai.tournesol.pureprivacy.matrix.ChatMsg) {
        val media = m.media ?: return
        notice.value = "Downloading over Tor…"
        viewModelScope.launch(Dispatchers.IO) {
            val ok = MatrixRepo.saveAttachment(getApplication(), media, m.fileName ?: "file", m.mime)
            notice.value = if (ok) "Saved to Downloads" else "Couldn't save the file"
        }
    }

    fun back() { MatrixRepo.currentRoomId = null; screen.value = Screen.Rooms }
    fun clearError() { error.value = null }

    /** Open a room from a tapped notification — wait for login/sync if we were
     *  cold-started by the tap. */
    fun openRoomFromNotif(id: String, name: String, answer: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            var waited = 0
            while (!MatrixRepo.isLoggedIn && waited < 120) { kotlinx.coroutines.delay(500); waited++ }
            waited = 0
            while (MatrixRepo.rooms.value.none { it.id == id } && waited < 30) { kotlinx.coroutines.delay(500); waited++ }
            runCatching {
                MatrixRepo.openRoom(id)
                screen.value = Screen.Chat(id, name)
                // Answering an incoming call: drop straight into the call once the
                // room is open. MainActivity observes this and launches the call UI.
                if (answer) launchCall.value = true
            }
        }
    }

    /** Set when the user answered an incoming-call notification; MainActivity reacts
     *  by launching the call once the chat is open. */
    val launchCall = MutableStateFlow(false)
}
