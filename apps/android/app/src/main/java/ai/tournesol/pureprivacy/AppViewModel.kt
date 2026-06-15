package ai.tournesol.pureprivacy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.tournesol.pureprivacy.matrix.MatrixRepo
import ai.tournesol.pureprivacy.tor.TorManager
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

    /** This user's Matrix address (@name:onion) — the payload behind "my code". */
    val myId: String get() = MatrixRepo.userId

    /** Tell the repo whether our UI is on screen, so the background notification
     *  poll can slow down when the app isn't visible (battery). */
    fun setForeground(foreground: Boolean) = MatrixRepo.onForeground(foreground)

    init {
        // Tor runs for the lifetime of the app; start() blocks reading its log.
        viewModelScope.launch(Dispatchers.IO) { TorManager.start(getApplication()) }
        // Restore a saved session (if any) once Tor is up, and jump to the chats.
        // We open on Splash; a first-run user (no saved session) drops straight to
        // Login, while a returning user waits on the branded splash until restore
        // resolves — never the empty login form.
        viewModelScope.launch(Dispatchers.IO) {
            if (!MatrixRepo.hasSavedSession(getApplication())) {
                screen.value = Screen.Login          // nothing to restore — sign in
                return@launch
            }
            var waited = 0
            while (TorManager.state.value !is TorManager.State.Ready && waited < 120) {
                if (TorManager.state.value is TorManager.State.Failed) { screen.value = Screen.Login; return@launch }
                kotlinx.coroutines.delay(1000); waited++
            }
            // Restore can stall when Tor is flaky (the SDK runs a networked sliding-sync
            // discovery during client build). Bound each attempt and retry once before
            // dropping to the login form — never sit on the splash forever.
            var restored = false
            for (attempt in 1..2) {
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
                }.onFailure { error.value = null; screen.value = Screen.Login }
            } else {
                error.value = null
                screen.value = Screen.Login          // couldn't restore — let them sign in
            }
        }
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
            } catch (t: Throwable) {
                error.value = t.message ?: t.toString()
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
                error.value = t.message ?: t.toString()
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
        // accept "pureprivacy:@bob:onion", "matrix:u/bob:onion", or a bare "@bob:onion"
        var id = raw.substringAfter("pureprivacy:", raw)
            .substringAfter("matrix:u/", raw.substringAfter("pureprivacy:", raw))
            .trim()
        if (!id.startsWith("@") && id.contains(":") && id.contains(".")) id = "@$id"
        startChat(id)
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
                error.value = t.message
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { runCatching { MatrixRepo.send(text) } }
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
