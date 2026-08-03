package ai.tournesol.pureprivacy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.tournesol.pureprivacy.matrix.MatrixRepo
import ai.tournesol.pureprivacy.matrix.RoomSummary
import ai.tournesol.pureprivacy.security.PasscodeStore
import ai.tournesol.pureprivacy.tor.TorManager
import ai.tournesol.pureprivacy.util.mapError
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    /** Backup Sync — sync phone files to the box + browse/download (feature F). */
    data object Files : Screen()
    /** Agents — the AI agents your box runs, grouped. Deliberately its OWN app, not a
     *  section of Messaging: people must always know whether they're talking to a human
     *  or an AI, and a shared list can't carry that guarantee. */
    data object Agents : Screen()
    /** Add agents — the only agent-shaped app you see before the add-on is set up, and
     *  where the owner chooses the password for their agents' control UI. */
    data object AddAgents : Screen()
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
    companion object {
        /** The picker exemption is PROCESS-level state, because the thing it defends against is
         *  process-level: [ProcessLifecycleOwner] fires for the whole app, and the observer that
         *  arms the auto-lock lives on MainActivity's view-model. Activities outside that scope
         *  (AgentSettingsActivity, which launches a chooser on the agent WebUI's behalf) get a
         *  DIFFERENT view-model instance, so an instance field there would set a flag nobody
         *  reads and the app would re-lock over their picker. Held here so every launcher in the
         *  process, whatever its scope, suppresses the same lock. */
        @Volatile private var pickerExemption = false

        /** Call immediately before launching a cross-process SAF picker from anywhere in the app. */
        fun beginExternalPick() { pickerExemption = true }
    }

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
    /** Set just before we launch an OUT-OF-PROCESS picker (any SAF open/create — file backup,
     *  chat attachment, avatar, backup-JSON save). Those run inside DocumentsUI, a *different*
     *  process, so [ProcessLifecycleOwner] sees the whole app go to background and would auto-lock
     *  — tearing down the picker's result callback before the file is ever delivered. This one-shot
     *  flag tells the next foreground pass "you came back from your own picker, don't re-lock",
     *  mirroring how same-process sub-activities (the call UI, the QR scanner) are already exempt.
     *  Consumed in [onEnterForeground]. */
    private var returningFromPicker: Boolean
        get() = pickerExemption
        set(v) { pickerExemption = v }
    /** Call immediately before launching a cross-process SAF picker. See [returningFromPicker]. */
    fun beginExternalPick() { returningFromPicker = true }

    /** After a successful sign-in with no passcode yet, force the user to set one. */
    private fun maybePromptSetup() {
        if (!PasscodeStore.isConfigured(getApplication())) gate.value = Gate.NeedsSetup
    }

    /** Process went to background (whole app, not an in-app activity hop). */
    fun onEnterBackground() {
        // Leaving to our OWN cross-process picker is not a real background — don't arm the
        // auto-lock, or the picker's result would be dropped on return (see returningFromPicker).
        if (returningFromPicker) return
        backgroundedAt = System.currentTimeMillis()
    }

    /** Process came to foreground. Re-lock if configured and the timeout has elapsed.
     *  Immediate by default (lockTimeoutMs = 0). Never locks over an in-progress setup. */
    fun onEnterForeground() {
        val app = getApplication<Application>()
        // Returning from our own SAF picker — consume the exemption and don't re-lock, so the
        // picked file is delivered to the (still-composed) screen instead of a torn-down callback.
        if (returningFromPicker) { returningFromPicker = false; return }
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
    fun openConfig() {
        error.value = null; screen.value = Screen.Config
        loadBoxStatus(); loadUpdateInfo()
    }

    // --- Agents app ---------------------------------------------------------------------
    /** Agent rooms, already held out of [rooms] by the repo — never merge these back. */
    val agentRooms = MatrixRepo.agentRooms
    val agents = MatrixRepo.agents
    val agentsLoading = MutableStateFlow(false)
    /** Where the agents' control UI lives; null until the add-on is installed. */
    val agentWebui = MatrixRepo.agentWebui

    /**
     * Has this box got agents at all?
     *
     * Drives which tiles the home grid shows: before setup there is nothing to talk to and
     * nothing to configure, so a single "Add agents" tile replaces both — two dead tiles
     * would be two invitations to tap something that can't work yet.
     *
     * Either signal is sufficient. A roster means agents exist; a published WebUI means the
     * add-on is running even if no agent has been provisioned yet. Requiring both would hide
     * the apps from a half-finished install, which is precisely when you need to get back in.
     */
    val agentsInstalled: StateFlow<Boolean> =
        combine(agents, agentWebui) { roster, webui -> roster.isNotEmpty() || webui != null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Take delivery of the agent onion's client-auth key as soon as the box publishes it.
     *
     * Tor reads its client-auth directory only at startup and we run it without a control
     * port, so a key that arrives mid-session needs a bounce. Done HERE, when the key lands,
     * rather than when the user opens Agent settings: the restart costs a Tor bootstrap, and
     * paying it in the background beats paying it while someone waits on a blank WebView.
     * It happens once — on the next cold start the key file is already in place and tor picks
     * it up on its own.
     */
    private fun watchAgentAuthKey() = viewModelScope.launch(Dispatchers.IO) {
        agentWebui.collect { w ->
            val key = w?.authKey.orEmpty()
            if (w == null || key.isBlank()) return@collect
            val changed = runCatching {
                TorManager.installClientAuth(getApplication(), w.onion, key)
            }.getOrDefault(false)
            if (changed) runCatching { TorManager.restart(getApplication()) }
        }
    }

    // Started HERE, not in the class's main `init` block: Kotlin runs initializers in
    // declaration order, and that block sits above `agentWebui`, so collecting it from there
    // dereferences a field that hasn't been assigned yet.
    init { watchAgentAuthKey() }

    /** One row in the Agents app. [roomId] is null until the agent's room is live. */
    data class AgentRow(
        val userId: String,
        val name: String,
        val group: String,
        val description: String,
        val roomId: String?,
        val preview: String?,
    )

    /**
     * Agents bucketed by group, ready to render.
     *
     * Driven by the REGISTRY, not by the room list. An agent exists the moment the box
     * publishes it; its room may lag (it has to be created, invited to, and joined) and on
     * a fresh setup it hasn't happened yet. Keying the UI off rooms meant a successfully
     * provisioned agent rendered as "No agents yet", which reads as a failed setup. Rooms
     * are joined in when present, so a row becomes tappable once there's somewhere to talk.
     *
     * Ungrouped agents collect under a trailing "Other" so nothing is silently dropped.
     */
    val agentGroups: StateFlow<List<Pair<String, List<AgentRow>>>> =
        combine(agents, agentRooms) { roster, rooms ->
            val byUser = rooms.associateBy { it.peerId }
            val byId = rooms.associateBy { it.id }
            val rows = roster.values.map { a ->
                // Same precedence as the repo's split: room id is authoritative, peer id
                // is the fallback for agents the box registered without a room.
                val room = a.roomId.takeIf { it.isNotBlank() }?.let { byId[it] } ?: byUser[a.userId]
                AgentRow(
                    userId = a.userId,
                    name = a.displayName,
                    group = a.group,
                    description = a.description,
                    roomId = room?.id,
                    preview = room?.preview,
                )
            }
            val byGroup = rows.groupBy { it.group.takeIf { g -> g.isNotBlank() } }
            val named = byGroup.filterKeys { it != null }
                .map { (g, rs) -> g!! to rs }
                .sortedBy { it.first.lowercase() }
            val ungrouped = byGroup[null].orEmpty()
            if (ungrouped.isEmpty()) named else named + ("Other" to ungrouped)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Conversations within one agent --------------------------------------------------
    //
    // Each conversation is its own room, which is what makes it its own history on the box.
    // The agent row keeps ONE main chat (the room the roster names); everything else the owner
    // starts is listed here.

    /** One conversation with an agent. [main] is the chat the agent was created with, which
     *  cannot be deleted on its own — removing the agent is what removes that. */
    data class SessionRow(
        val roomId: String,
        val title: String,
        val preview: String?,
        val main: Boolean,
    )

    /** Every conversation with [agentUser], main chat first. */
    fun sessionsFor(agentUser: String, mainRoomId: String?): List<SessionRow> {
        val byId = agentRooms.value.associateBy { it.id }
        val extra = MatrixRepo.agentSessions.value[agentUser].orEmpty()
            // The main room can also appear in the box's session list on a box where the
            // owner started conversations before this shipped. Listing it twice would offer
            // a delete for a chat that must go with the agent.
            .filter { it.roomId != mainRoomId }
            .map { SessionRow(it.roomId, it.title, byId[it.roomId]?.preview, main = false) }
        val main = mainRoomId?.let {
            SessionRow(it, "Main chat", byId[it]?.preview, main = true)
        }
        return listOfNotNull(main) + extra
    }

    /** Start another conversation with an agent. */
    fun newAgentSession(agentUser: String, title: String) =
        runAgentSessionCommand("Starting a new conversation…") {
            MatrixRepo.newAgentSession(agentUser, title)
        }

    /** Delete ONE conversation. The agent and its other chats are untouched. */
    fun deleteAgentSession(roomId: String) =
        runAgentSessionCommand("Deleting the conversation…") {
            MatrixRepo.deleteAgentSession(roomId)
        }

    /** Shared plumbing for the two session commands: both are quick, both report through the
     *  Agents screen's one status line, and both must refresh the room split afterwards —
     *  that split is what decides whether a room shows up as an agent or as a person. */
    private fun runAgentSessionCommand(opening: String, send: suspend () -> String?) {
        if (agentSetupBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            agentSetupBusy.value = true
            agentSetupNotice.value = opening
            val id = runCatching { send() }.getOrNull()
            if (id == null) {
                agentSetupNotice.value = "Couldn't reach your box."
                agentSetupBusy.value = false
                return@launch
            }
            var settled = false
            for (attempt in 1..24) {           // 24 × 5s ≈ 2 minutes
                kotlinx.coroutines.delay(5_000)
                val outcome = runCatching { MatrixRepo.readCommandOutcome(id) }.getOrNull()
                if (outcome != null) {
                    agentSetupNotice.value = outcome.message?.takeIf { it.isNotBlank() }
                        ?: if (outcome.ok) "Done." else "Your box couldn't do that."
                    settled = true
                    break
                }
            }
            if (!settled) agentSetupNotice.value = "Still working — check back shortly."
            runCatching { MatrixRepo.refreshAgents() }
            agentSetupBusy.value = false
        }
    }

    /** True while the box is provisioning the agent runtime (minutes, not seconds). */
    val agentSetupBusy = MutableStateFlow(false)
    /** Human-readable progress / outcome of the last setup attempt. */
    val agentSetupNotice = MutableStateFlow<String?>(null)
    fun clearAgentSetupNotice() { agentSetupNotice.value = null }

    /**
     * One-shot: setup finished successfully, so hand the owner straight to Agent settings.
     *
     * Saving a password is a step in getting agents working, not an end in itself — leaving
     * someone parked on the form they just submitted makes them guess what comes next. The
     * screen consumes this and clears it, so a config change (or coming back later) doesn't
     * re-fire it.
     */
    val agentSetupSucceeded = MutableStateFlow(false)
    fun consumeAgentSetupSucceeded() { agentSetupSucceeded.value = false }

    /** Open "Add agents" — also the change-the-password screen once agents exist. */
    fun openAddAgents() {
        error.value = null
        agentSetupNotice.value = null
        screen.value = Screen.AddAgents
    }

    fun openAgents() {
        error.value = null
        screen.value = Screen.Agents
        viewModelScope.launch(Dispatchers.IO) {
            agentsLoading.value = true
            runCatching { MatrixRepo.refreshAgents() }
            agentsLoading.value = false
        }
    }

    /**
     * One-tap setup of everything the agent runtime needs, done BY THE BOX: start the agent
     * container, provision an agent's Matrix account, and publish the roster. Rides the same
     * guarded command channel as restart / update-approve — the phone never touches Docker,
     * it just asks and watches.
     *
     * Deliberately patient: this pulls an image and provisions an account over Tor, so it
     * polls for up to ~10 minutes. The authoritative success signal is the ROSTER appearing
     * (that's what the whole app keys off), so we accept that even if the result key never
     * lands — a box that provisioned successfully but failed to publish an outcome should
     * not look like a failure.
     */
    fun setUpAgents(
        webuiPassword: String? = null,
        agentName: String? = null,
        provider: String? = null,
        apiKey: String? = null,
        baseUrl: String? = null,
        model: String? = null,
    ) {
        if (agentSetupBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            agentSetupBusy.value = true
            agentSetupSucceeded.value = false
            agentSetupNotice.value = if (agentName.isNullOrBlank())
                "Asking your box to set up agents…"
            else
                "Asking your box to add ${agentName.trim()}…"
            // The password rides the command channel, not account data — the box reads it and
            // clears the command immediately, exactly as it does for the backup passphrase.
            // Blank means "keep whatever the box has" (on a fresh install, the one the agent
            // container generates for itself).
            val id = runCatching {
                MatrixRepo.sendBoxCommand(
                    "agent_setup",
                    passphrase = webuiPassword?.takeIf { it.isNotBlank() },
                    agentName = agentName?.takeIf { it.isNotBlank() },
                    provider = provider?.takeIf { it.isNotBlank() },
                    apiKey = apiKey?.takeIf { it.isNotBlank() },
                    baseUrl = baseUrl?.takeIf { it.isNotBlank() },
                    model = model?.takeIf { it.isNotBlank() },
                )
            }.getOrNull()
            if (id == null) {
                agentSetupNotice.value = "Couldn't reach your box."
                agentSetupBusy.value = false
                return@launch
            }
            var settled = false
            // A `for` with `break`, not repeat{}: `return@repeat` would only skip the
            // current iteration, so the poll would keep running after it had an answer.
            for (attempt in 1..120) {           // 120 × 5s ≈ 10 minutes
                kotlinx.coroutines.delay(5_000)
                runCatching { MatrixRepo.readCommandProgress(id) }.getOrNull()
                    ?.let { agentSetupNotice.value = it }
                // Reuses the existing outcome reader (the update flow's), so the box's own
                // wording — or its refusal — reaches the user verbatim.
                val outcome = runCatching { MatrixRepo.readCommandOutcome(id) }.getOrNull()
                if (outcome != null) {
                    agentSetupNotice.value = outcome.message?.takeIf { it.isNotBlank() }
                        ?: if (outcome.ok) "Agents are ready." else "Setup failed on your box."
                    settled = true
                    if (outcome.ok) agentSetupSucceeded.value = true
                    break
                }
                // Roster showing up is success regardless of what the result key says.
                runCatching { MatrixRepo.refreshAgents() }
                if (MatrixRepo.agents.value.isNotEmpty()) {
                    agentSetupNotice.value = "Agents are ready."
                    settled = true
                    agentSetupSucceeded.value = true
                    break
                }
            }
            if (!settled) {
                agentSetupNotice.value =
                    "Still working — your box is taking a while. Check back shortly."
            }
            runCatching { MatrixRepo.refreshAgents() }
            agentSetupBusy.value = false
        }
    }

    // --- Device-code sign-in (Codex) -----------------------------------------------------
    //
    // Some providers can't be finished with a key typed here: Codex signs in through the
    // owner's ChatGPT account. The box runs the blocking half and relays a short code; this
    // holds the state the wizard renders while that happens.
    //
    // Kept OFF agentSetupBusy/Notice on purpose, unlike removeAgent: this one runs INSIDE the
    // add-agent dialog and has to coexist with it, so sharing that state would have the
    // wizard's own status line fighting the sign-in's.

    /** Where a device-code sign-in has got to. `challenge` is what the owner has to act on. */
    data class AuthFlow(
        val provider: String,
        val status: String,
        val challenge: MatrixRepo.AuthChallenge? = null,
        val done: Boolean = false,
        val ok: Boolean = false,
    )

    val authFlow = MutableStateFlow<AuthFlow?>(null)
    fun clearAuthFlow() { authFlow.value = null }

    /** Begin a device-code sign-in for [provider] and follow it to a verdict.
     *
     *  Polls rather than waits: the box publishes the code the moment the provider prints it,
     *  and the owner cannot start typing until they can see it. */
    fun startAgentAuth(provider: String) {
        if (authFlow.value?.done == false) return   // one at a time; a second would race the slot
        viewModelScope.launch(Dispatchers.IO) {
            authFlow.value = AuthFlow(provider, "Asking your box to start the sign-in…")
            val id = runCatching {
                MatrixRepo.sendBoxCommand("agent_auth", provider = provider)
            }.getOrNull()
            if (id == null) {
                authFlow.value = AuthFlow(provider, "Couldn't reach your box.", done = true)
                return@launch
            }
            var settled = false
            // 16 minutes: the provider's code expires around 15, and the box gives up at 16
            // and writes a verdict — so this outlasts the box rather than abandoning a flow
            // that is about to answer.
            for (attempt in 1..192) {              // 192 × 5s ≈ 16 minutes
                kotlinx.coroutines.delay(5_000)
                val outcome = runCatching { MatrixRepo.readCommandOutcome(id) }.getOrNull()
                if (outcome != null) {
                    authFlow.value = AuthFlow(
                        provider,
                        outcome.message?.takeIf { it.isNotBlank() }
                            ?: if (outcome.ok) "Signed in." else "Sign-in didn't complete.",
                        done = true, ok = outcome.ok,
                    )
                    settled = true
                    break
                }
                val challenge = runCatching { MatrixRepo.readAuthChallenge(id) }.getOrNull()
                val note = runCatching { MatrixRepo.readCommandProgress(id) }.getOrNull()
                // Keep the last known code: progress messages keep arriving after it, and
                // dropping it would make the code the owner is mid-way through typing vanish.
                authFlow.value = AuthFlow(
                    provider,
                    note ?: authFlow.value?.status.orEmpty(),
                    challenge ?: authFlow.value?.challenge,
                )
            }
            if (!settled) {
                authFlow.value = AuthFlow(
                    provider, "The sign-in timed out. You can try again.", done = true,
                )
            }
        }
    }

    /** Ask the box to remove an agent: clear its chat, retire its account, forget its profile.
     *
     *  Reuses the agent-setup busy/notice state so the Agents screen shows one status line
     *  whichever operation is running — they cannot overlap, and a second set of flows would
     *  only be two things to keep in sync.
     *
     *  Targets the Matrix id, never the display name. Two agents may be named the same in the
     *  roster; deleting the wrong one is not recoverable.
     */
    fun removeAgent(userId: String, displayName: String) {
        if (agentSetupBusy.value) return
        val target = userId.trim()
        if (target.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            agentSetupBusy.value = true
            agentSetupSucceeded.value = false
            agentSetupNotice.value = "Asking your box to remove $displayName…"
            val id = runCatching {
                MatrixRepo.sendBoxCommand("agent_remove", agentUser = target)
            }.getOrNull()
            if (id == null) {
                agentSetupNotice.value = "Couldn't reach your box."
                agentSetupBusy.value = false
                return@launch
            }
            var settled = false
            for (attempt in 1..24) {           // 24 × 5s ≈ 2 minutes; removal is quick
                kotlinx.coroutines.delay(5_000)
                runCatching { MatrixRepo.readCommandProgress(id) }.getOrNull()
                    ?.let { agentSetupNotice.value = it }
                val outcome = runCatching { MatrixRepo.readCommandOutcome(id) }.getOrNull()
                if (outcome != null) {
                    agentSetupNotice.value = outcome.message?.takeIf { it.isNotBlank() }
                        ?: if (outcome.ok) "$displayName is gone." else "Couldn't remove $displayName."
                    settled = true
                    break
                }
            }
            if (!settled) {
                agentSetupNotice.value = "Still working — check back shortly."
            }
            // Whatever happened, re-read the roster: it is what drives both the Agents list
            // and the human/AI split, so a stale copy is what made the removed agent show up
            // as a person in the first place.
            runCatching { MatrixRepo.refreshAgents() }
            agentSetupBusy.value = false
        }
    }

    // --- Backup Sync app (feature F) ----------------------------------------------------
    val backupFiles = MatrixRepo.backupFiles
    private val _libRoomId = MutableStateFlow<String?>(null)
    val libraryReady = MutableStateFlow(false)
    val backupBusy = MutableStateFlow(false)
    /** Count of uploads in flight, shown as progress. */
    val backupUploading = MutableStateFlow(0)
    /** Chunk progress for the file currently uploading: "part 7 of 42" (feature I). Empty
     *  when the current file is small enough to go in one piece. */
    val backupPartProgress = MutableStateFlow("")
    val backupNotice = MutableStateFlow<String?>(null)
    fun clearBackupNotice() { backupNotice.value = null }

    fun openFilesApp() {
        error.value = null
        screen.value = Screen.Files
        initBackupSync()
        viewModelScope.launch(Dispatchers.IO) {
            libraryReady.value = false
            val rid = runCatching { MatrixRepo.ensureBackupRoom() }.getOrNull()
            if (rid == null) { backupNotice.value = "Couldn't reach your box."; return@launch }
            _libRoomId.value = rid
            runCatching { MatrixRepo.openBackupLibrary(rid) }
            libraryReady.value = true
        }
    }

    fun closeFilesApp() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { MatrixRepo.closeBackupLibrary() } }
        goHome()
    }

    /** Upload the picked files to the box's library, at full fidelity. */
    fun syncFiles(uris: List<android.net.Uri>) {
        val rid = _libRoomId.value ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            backupUploading.value = uris.size
            var ok = 0
            for (u in uris) {
                val done = runCatching {
                    MatrixRepo.backupUpload(getApplication(), rid, u) { part, total ->
                        backupPartProgress.value = if (total > 1) "part $part of $total" else ""
                    }
                }.getOrDefault(false)
                backupPartProgress.value = ""
                if (done) ok++
                backupUploading.value = (backupUploading.value - 1).coerceAtLeast(0)
            }
            backupUploading.value = 0
            backupNotice.value =
                if (ok == uris.size) "Backed up $ok ${if (ok == 1) "file" else "files"} to your box."
                else "Backed up $ok of ${uris.size} — some were too large or failed."
        }
    }

    /** Stream a library file straight into the user's chosen location. Chunked files are
     *  written part-by-part, so a multi-GB download never has to fit in memory. */
    suspend fun downloadBackupTo(
        file: MatrixRepo.BackupFile,
        out: java.io.OutputStream,
    ): Boolean = runCatching {
        MatrixRepo.backupDownloadTo(file, out) { part, total ->
            backupPartProgress.value = if (total > 1) "part $part of $total" else ""
        }
    }.getOrDefault(false).also { backupPartProgress.value = "" }

    // --- Continuous Backup Sync (feature G) ---------------------------------------------
    val syncSources = ai.tournesol.pureprivacy.backup.BackupSyncStore.sources
    val syncWifiOnly = ai.tournesol.pureprivacy.backup.BackupSyncStore.wifiOnly
    val syncBatteryNotLow = ai.tournesol.pureprivacy.backup.BackupSyncStore.batteryNotLow
    val syncLastMs = ai.tournesol.pureprivacy.backup.BackupSyncStore.lastSyncMs
    val syncingCount = ai.tournesol.pureprivacy.backup.BackupSyncManager.syncingCount
    /** True while a PHOTOS source is being kept in sync (drives the toggle). */
    val photoBackupOn get() = syncSources.value.any {
        it.kind == ai.tournesol.pureprivacy.backup.BackupSyncStore.Kind.PHOTOS && it.enabled
    }

    fun initBackupSync() {
        ai.tournesol.pureprivacy.backup.BackupSyncStore.ensureLoaded(getApplication())
    }

    /** Turn the camera-roll auto-backup on/off. [includeExisting] = true backs up the photos
     *  already on the phone too (watermark 0); false (default) starts the watermark at *now*, so
     *  only NEW photos/videos flow up — never floods Tor with an existing 10k-photo camera roll. */
    fun setPhotoBackup(enable: Boolean, includeExisting: Boolean = false) {
        val app = getApplication<Application>()
        val store = ai.tournesol.pureprivacy.backup.BackupSyncStore
        if (enable) {
            store.upsert(app, ai.tournesol.pureprivacy.backup.BackupSyncStore.Source(
                id = "photos", kind = ai.tournesol.pureprivacy.backup.BackupSyncStore.Kind.PHOTOS, uri = null,
                label = "Photos & videos", enabled = true,   // whole MediaStore library, not just DCIM
                watermarkMs = if (includeExisting) 0L else System.currentTimeMillis(), boundaryKeys = emptySet(),
            ))
            backupNotice.value = if (includeExisting)
                "Backing up your photos & videos — this can take a while over Tor."
            else "New photos & videos will back up automatically."
        } else {
            store.remove(app, "photos")
        }
        ai.tournesol.pureprivacy.backup.BackupSyncWorker.applySchedule(app)
        if (enable) kickSync()
    }

    /** Add a folder to keep in sync: persist read access (survives reboots) and back up its
     *  current contents plus anything added later (watermark = 0). */
    fun addSyncFolder(uri: android.net.Uri) {
        val app = getApplication<Application>()
        val store = ai.tournesol.pureprivacy.backup.BackupSyncStore
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val label = runCatching {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(app, uri)?.name
        }.getOrNull() ?: "Folder"
        store.upsert(app, ai.tournesol.pureprivacy.backup.BackupSyncStore.Source(
            id = uri.toString(), kind = ai.tournesol.pureprivacy.backup.BackupSyncStore.Kind.FOLDER, uri = uri.toString(),
            label = label, enabled = true, watermarkMs = 0L, boundaryKeys = emptySet(),
        ))
        ai.tournesol.pureprivacy.backup.BackupSyncWorker.applySchedule(app)
        backupNotice.value = "“$label” is now kept in sync with your box."
        kickSync()
    }

    fun removeSyncSource(id: String) {
        val app = getApplication<Application>()
        ai.tournesol.pureprivacy.backup.BackupSyncStore.remove(app, id)
        ai.tournesol.pureprivacy.backup.BackupSyncWorker.applySchedule(app)
    }

    fun setSyncWifiOnly(v: Boolean) {
        val app = getApplication<Application>()
        val store = ai.tournesol.pureprivacy.backup.BackupSyncStore
        store.setConstraints(app, v, store.isBatteryNotLow(app))
        ai.tournesol.pureprivacy.backup.BackupSyncWorker.applySchedule(app)
    }

    fun setSyncBatteryNotLow(v: Boolean) {
        val app = getApplication<Application>()
        val store = ai.tournesol.pureprivacy.backup.BackupSyncStore
        store.setConstraints(app, store.isWifiOnly(app), v)
        ai.tournesol.pureprivacy.backup.BackupSyncWorker.applySchedule(app)
    }

    /** User tapped "Sync now" (or a source was just added). Run a warm pass immediately since the
     *  app is open + the client is live, and also enqueue the background worker as a backstop. */
    fun kickSync() {
        val app = getApplication<Application>()
        ai.tournesol.pureprivacy.backup.BackupSyncWorker.syncNow(app)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ai.tournesol.pureprivacy.backup.BackupSyncManager.runPass(app) }
        }
    }

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

    // --- Box update (feature H) ---------------------------------------------------------
    val updateInfo = MutableStateFlow<MatrixRepo.UpdateInfo?>(null)
    val autoUpdateCheck = MutableStateFlow(true)
    /** True while an update is being checked/installed, so the UI can show progress. */
    val updateBusy = MutableStateFlow(false)

    fun loadUpdateInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { MatrixRepo.readUpdateInfo() }.getOrNull()?.let { updateInfo.value = it }
            autoUpdateCheck.value = runCatching { MatrixRepo.readAutoUpdateCheck() }.getOrDefault(true)
        }
    }

    fun setAutoUpdateCheck(on: Boolean) {
        autoUpdateCheck.value = on
        viewModelScope.launch(Dispatchers.IO) { runCatching { MatrixRepo.setAutoUpdateCheck(on) } }
    }

    /** Ask the box to look for a new release now (it verifies the signature before believing it). */
    fun checkForUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            updateBusy.value = true
            configNotice.value = "Checking for updates over Tor…"
            val id = runCatching { MatrixRepo.sendBoxCommand("check_update") }.getOrNull()
            if (id == null) {
                configNotice.value = "Couldn't reach your box."; updateBusy.value = false; return@launch
            }
            var out: MatrixRepo.CommandOutcome? = null
            for (i in 1..30) {
                kotlinx.coroutines.delay(2000)
                out = runCatching { MatrixRepo.readCommandOutcome(id) }.getOrNull()
                if (out != null) break
            }
            updateBusy.value = false
            configNotice.value = out?.message
                ?: "Your box didn't answer — it may still be starting."
            loadUpdateInfo()
        }
    }

    /** Owner approved the update. The box installs ONLY the release it already verified;
     *  naming the version stops this approval being replayed against a different one. */
    fun approveUpdate() {
        val target = updateInfo.value?.latest.orEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            updateBusy.value = true
            configNotice.value = "Installing the update — your box will restart…"
            val id = runCatching {
                MatrixRepo.sendBoxCommand("update", targetVersion = target)
            }.getOrNull()
            if (id == null) {
                configNotice.value = "Couldn't reach your box."; updateBusy.value = false; return@launch
            }
            var out: MatrixRepo.CommandOutcome? = null
            // A native install downloads a whole binary over Tor — allow generous time.
            for (i in 1..90) {
                kotlinx.coroutines.delay(2000)
                out = runCatching { MatrixRepo.readCommandOutcome(id) }.getOrNull()
                if (out != null) break
            }
            updateBusy.value = false
            configNotice.value = out?.message
                ?: "Still working — your box will come back on its own."
            loadUpdateInfo()
            loadBoxStatus()
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

    /** Open an agent's room, returning to Agents (not Messaging) when it's closed. */
    fun openAgentRoom(id: String, name: String) {
        chatReturn = Screen.Agents
        openRoom(id, name)
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

    /** Where leaving a chat returns to. A chat can be reached from Messaging OR from the
     *  Agents app, and always returning to Messaging dumped you into the human chat list
     *  after talking to an agent — which is doubly wrong here, since keeping those two
     *  places distinct is the whole point of a separate Agents app. */
    private var chatReturn: Screen = Screen.Rooms

    fun back() {
        MatrixRepo.currentRoomId = null
        screen.value = chatReturn
        chatReturn = Screen.Rooms
    }
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
