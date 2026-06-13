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
        // HTTPS so the embedded WebView (Element Call) reaches the box via Tor's
        // CONNECT tunnel with no mixed content; the SDK trusts the onion's
        // self-signed cert via disableSslVerification().
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        val afterScheme = s.substringAfter("://")
        if (!afterScheme.contains(":")) s = "$s:8009"
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

    fun back() { screen.value = Screen.Rooms }
    fun clearError() { error.value = null }
}
