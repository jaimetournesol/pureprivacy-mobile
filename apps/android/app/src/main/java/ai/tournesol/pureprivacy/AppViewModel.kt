package ai.tournesol.pureprivacy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.tournesol.pureprivacy.matrix.MatrixRepo
import ai.tournesol.pureprivacy.tor.TorManager
import ai.tournesol.pureprivacy.util.mapError
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed class Screen {
    /** Cold-start branded loading screen — shown while Tor boots and a saved
     *  session restores, so a returning user never sees an empty login form. */
    data object Splash : Screen()
    data object Login : Screen()
    data object Rooms : Screen()
    data object Profile : Screen()
    data class Chat(val roomId: String, val roomName: String) : Screen()
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
        // Tor runs for the lifetime of the app; start() blocks reading its log.
        viewModelScope.launch(Dispatchers.IO) { TorManager.start(getApplication()) }
        startRestore()
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
                        screen.value = Screen.Rooms
                        consumePendingContact()
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
                screen.value = Screen.Rooms
                consumePendingContact()
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

    fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { MatrixRepo.logout(getApplication()) }
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
