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

    val screen = MutableStateFlow<Screen>(Screen.Login)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    /** This user's Matrix address (@name:onion) — the payload behind "my code". */
    val myId: String get() = MatrixRepo.userId

    init {
        // Tor runs for the lifetime of the app; start() blocks reading its log.
        viewModelScope.launch(Dispatchers.IO) { TorManager.start(getApplication()) }
        // Restore a saved session (if any) once Tor is up, and jump to the chats.
        viewModelScope.launch(Dispatchers.IO) {
            var waited = 0
            while (TorManager.state.value !is TorManager.State.Ready && waited < 120) {
                if (TorManager.state.value is TorManager.State.Failed) return@launch
                kotlinx.coroutines.delay(1000); waited++
            }
            try {
                if (MatrixRepo.tryRestore(getApplication())) {
                    MatrixRepo.startSync()
                    PpSyncService.start(getApplication())
                    screen.value = Screen.Rooms
                }
            } catch (t: Throwable) {
                error.value = null  // no saved/valid session; stay on Login
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
        error.value = null
        busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val roomId = MatrixRepo.startChat(uid)
                kotlinx.coroutines.delay(1500)   // let the new room land in sync
                MatrixRepo.openRoom(roomId)
                screen.value = Screen.Chat(roomId, uid.removePrefix("@").substringBefore(":"))
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

    fun back() { MatrixRepo.currentRoomId = null; screen.value = Screen.Rooms }
    fun clearError() { error.value = null }

    /** Open a room from a tapped notification — wait for login/sync if we were
     *  cold-started by the tap. */
    fun openRoomFromNotif(id: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var waited = 0
            while (!MatrixRepo.isLoggedIn && waited < 120) { kotlinx.coroutines.delay(500); waited++ }
            waited = 0
            while (MatrixRepo.rooms.value.none { it.id == id } && waited < 30) { kotlinx.coroutines.delay(500); waited++ }
            runCatching {
                MatrixRepo.openRoom(id)
                screen.value = Screen.Chat(id, name)
            }
        }
    }
}
