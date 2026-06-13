package ai.tournesol.pureprivacy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tournesol.pureprivacy.matrix.ChatMsg
import ai.tournesol.pureprivacy.matrix.RoomSummary
import ai.tournesol.pureprivacy.tor.TorManager
import ai.tournesol.pureprivacy.ui.theme.*

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PurePrivacyTheme {
                Surface(Modifier.fillMaxSize(), color = Ink) {
                    val screen by vm.screen.collectAsState()
                    when (val s = screen) {
                        is Screen.Login -> LoginScreen(vm)
                        is Screen.Rooms -> RoomsScreen(vm)
                        is Screen.Chat -> ChatScreen(vm, s.roomId, s.roomName)
                    }
                }
            }
        }
    }
}

@Composable
private fun TorBadge(modifier: Modifier = Modifier) {
    val st by TorManager.state.collectAsState()
    val (label, color) = when (val s = st) {
        is TorManager.State.Ready -> "Tor: connected" to Sunflower
        is TorManager.State.Bootstrapping -> "Tor: ${s.percent}% ${s.message}" to PaperDim
        is TorManager.State.Failed -> "Tor: failed" to Color(0xFFE5534B)
        else -> "Tor: starting…" to PaperDim
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Lock, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 12.sp)
    }
}

@Composable
fun LoginScreen(vm: AppViewModel) {
    var onion by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsState()
    val err by vm.error.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        SunflowerMark()
        Spacer(Modifier.height(16.dp))
        Text("PurePrivacy", color = Paper, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Private, and a little slower — that's the deal.",
            color = PaperDim, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        TorBadge()
        Spacer(Modifier.height(28.dp))

        PpField(onion, { onion = it }, "Your box (.onion)")
        Spacer(Modifier.height(12.dp))
        PpField(user, { user = it }, "Username")
        Spacer(Modifier.height(12.dp))
        PpField(pass, { pass = it }, "Password", password = true)
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { vm.login(onion, user, pass) },
            enabled = !busy && onion.isNotBlank() && user.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Sunflower, contentColor = Ink),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Ink, strokeWidth = 2.dp)
            else Text("Connect over Tor", fontWeight = FontWeight.SemiBold)
        }
        val status by vm.status.collectAsState()
        if (busy && status.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(status, color = PaperDim, fontSize = 13.sp) }
        if (err != null) {
            Spacer(Modifier.height(14.dp))
            Text(err!!, color = Color(0xFFE5534B), fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SunflowerMark() {
    Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(InkCard), contentAlignment = Alignment.Center) {
        Text("✿", color = Sunflower, fontSize = 44.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PpField(value: String, onChange: (String) -> Unit, label: String, password: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (password) KeyboardOptions.Default else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Sunflower, unfocusedBorderColor = Color(0xFF2D3742),
            focusedLabelColor = Sunflower, unfocusedLabelColor = PaperDim,
            focusedTextColor = Paper, unfocusedTextColor = Paper,
            cursorColor = Sunflower
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsScreen(vm: AppViewModel) {
    val rooms by vm.rooms.collectAsState()
    val ctx = LocalContext.current
    BackHandler { (ctx as? android.app.Activity)?.moveTaskToBack(true) }
    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = { Column { Text("Chats", color = Paper, fontWeight = FontWeight.Bold); TorBadge() } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = InkSoft)
            )
        }
    ) { pad ->
        if (rooms.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No chats yet — syncing over Tor…", color = PaperDim)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(rooms, key = { it.id }) { r -> RoomRow(r) { vm.openRoom(r.id, r.name) } }
            }
        }
    }
}

@Composable
private fun RoomRow(r: RoomSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(InkCard), contentAlignment = Alignment.Center) {
            Text(r.name.firstOrNull()?.uppercase() ?: "#", color = Sunflower, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Text(r.name, color = Paper, fontSize = 16.sp, maxLines = 1)
    }
    HorizontalDivider(color = Color(0xFF1B222B))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AppViewModel, roomId: String, roomName: String) {
    BackHandler { vm.back() }
    val messages by vm.messages.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Paper) }
                },
                title = { Column { Text(roomName, color = Paper, fontWeight = FontWeight.Bold, maxLines = 1); TorBadge() } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = InkSoft)
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(InkSoft).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    placeholder = { Text("Message", color = PaperDim) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Sunflower, unfocusedBorderColor = Color(0xFF2D3742),
                        focusedTextColor = Paper, unfocusedTextColor = Paper, cursorColor = Sunflower
                    )
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { vm.send(draft); draft = "" },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Sunflower, contentColor = Ink)
                ) { Icon(Icons.AutoMirrored.Filled.Send, "send") }
            }
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
            state = listState
        ) {
            items(messages, key = { it.key }) { m -> Bubble(m) }
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg) {
    val align = if (m.mine) Alignment.End else Alignment.Start
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = align) {
        if (!m.mine) Text(m.sender.removePrefix("@").substringBefore(":"), color = Sunflower, fontSize = 11.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
        Box(
            Modifier.widthIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (m.mine) BubbleMine else BubbleTheirs)
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) { Text(m.body, color = Paper, fontSize = 15.sp) }
    }
}
