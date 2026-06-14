package ai.tournesol.pureprivacy

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tournesol.pureprivacy.matrix.ChatMsg
import ai.tournesol.pureprivacy.matrix.RoomSummary
import ai.tournesol.pureprivacy.tor.TorManager
import ai.tournesol.pureprivacy.ui.theme.*
import ai.tournesol.pureprivacy.util.Qr
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        handleNotifIntent(intent)
        setContent {
            PurePrivacyTheme {
                Surface(Modifier.fillMaxSize(), color = Ink) {
                    val screen by vm.screen.collectAsState()
                    when (val s = screen) {
                        is Screen.Login -> LoginScreen(vm)
                        is Screen.Rooms -> RoomsScreen(vm)
                        is Screen.Profile -> ProfileScreen(vm)
                        is Screen.Chat -> ChatScreen(vm, s.roomId, s.roomName)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotifIntent(intent)
    }

    /** A tapped message/call notification carries the room — open it. */
    private fun handleNotifIntent(intent: Intent?) {
        val roomId = intent?.getStringExtra(PpSyncService.EXTRA_ROOM_ID) ?: return
        val name = intent.getStringExtra(PpSyncService.EXTRA_ROOM_NAME) ?: "Chat"
        vm.openRoomFromNotif(roomId, name)
    }
}

/** A QR-scan launcher wired to the journeyapps scanner; calls [onResult] with the
 *  decoded text. The CaptureActivity requests the camera permission itself. */
@Composable
private fun rememberScan(onResult: (String?) -> Unit) =
    rememberLauncherForActivityResult(ScanContract()) { onResult(it?.contents) }

private fun scanOptions() = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt("Point at a friend's PurePrivacy code")
    setBeepEnabled(false)
    setOrientationLocked(false)
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
private fun SunflowerMark(size: Int = 72) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 3.6).dp)).background(InkCard), contentAlignment = Alignment.Center) {
        Text("✿", color = Sunflower, fontSize = (size * 0.6).sp)
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
    val busy by vm.busy.collectAsState()
    val err by vm.error.collectAsState()
    val ctx = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var peer by remember { mutableStateOf("") }
    BackHandler { (ctx as? android.app.Activity)?.moveTaskToBack(true) }

    val scan = rememberScan { contents -> if (contents != null) vm.addContact(contents) }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, containerColor = InkSoft) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text("New chat", color = Paper, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    modifier = Modifier.padding(start = 22.dp, top = 4.dp, bottom = 8.dp))
                SheetAction(Icons.Filled.QrCodeScanner, "Scan a code", "Point your camera at a friend's code") {
                    showSheet = false; scan.launch(scanOptions())
                }
                SheetAction(Icons.Filled.QrCode2, "Show my code", "Let someone scan you to start a chat") {
                    showSheet = false; vm.showProfile()
                }
                SheetAction(Icons.Filled.Edit, "Enter address", "Type a @name:onion address") {
                    showSheet = false; peer = ""; vm.clearError(); showNew = true
                }
            }
        }
    }

    if (showNew) {
        AlertDialog(
            onDismissRequest = { if (!busy) showNew = false },
            containerColor = InkCard,
            title = { Text("New chat", color = Paper) },
            text = {
                Column {
                    Text("Enter your contact's address.", color = PaperDim, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = peer, onValueChange = { peer = it },
                        singleLine = true,
                        placeholder = { Text("@bob:xxxx.onion", color = PaperDim) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Sunflower, unfocusedBorderColor = Color(0xFF2D3742),
                            focusedTextColor = Paper, unfocusedTextColor = Paper, cursorColor = Sunflower
                        )
                    )
                    if (err != null) { Spacer(Modifier.height(8.dp)); Text(err!!, color = Color(0xFFE5534B), fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.startChat(peer); showNew = false }, enabled = !busy && peer.isNotBlank()) {
                    Text("Start chat", color = Sunflower)
                }
            },
            dismissButton = { TextButton(onClick = { showNew = false }, enabled = !busy) { Text("Cancel", color = PaperDim) } }
        )
    }

    Scaffold(
        containerColor = Ink,
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.clearError(); showSheet = true },
                containerColor = Sunflower, contentColor = Ink) {
                Icon(Icons.Filled.QrCodeScanner, "new chat")
            }
        },
        topBar = {
            TopAppBar(
                title = { Column { Text("Chats", color = Paper, fontWeight = FontWeight.Bold); TorBadge() } },
                actions = {
                    IconButton(onClick = { vm.showProfile() }) {
                        Icon(Icons.Filled.QrCode, "my code", tint = Sunflower)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = InkSoft)
            )
        }
    ) { pad ->
        if (rooms.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.QrCodeScanner, null, tint = Sunflower, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("No chats yet", color = Paper, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Scan a friend's code, or show yours — that's all it takes to connect across boxes, over Tor.",
                    color = PaperDim, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { scan.launch(scanOptions()) },
                    colors = ButtonDefaults.buttonColors(containerColor = Sunflower, contentColor = Ink),
                    shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan a code", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { vm.showProfile() }) { Text("Show my code", color = Sunflower) }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(rooms, key = { it.id }) { r -> RoomRow(r) { vm.openRoom(r.id, r.name) } }
            }
        }
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(InkCard), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Sunflower, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, color = Paper, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = PaperDim, fontSize = 12.sp)
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
            Text(r.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#", color = Sunflower, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, color = Paper, fontSize = 16.sp, maxLines = 1)
            if (r.invited) Text("Invite · tap to join", color = Sunflower, fontSize = 12.sp)
        }
        if (r.invited) Box(Modifier.size(8.dp).clip(CircleShape).background(Sunflower))
    }
    HorizontalDivider(color = Color(0xFF1B222B))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(vm: AppViewModel) {
    BackHandler { vm.openRooms() }
    val clip = LocalClipboardManager.current
    val id = vm.myId
    val name = id.removePrefix("@").substringBefore(":")
    val qr = remember(id) { runCatching { Qr.bitmap(if (id.isBlank()) " " else id, 640) }.getOrNull() }

    val scan = rememberScan { contents -> if (contents != null) vm.addContact(contents) }

    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.openRooms() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Paper) } },
                title = { Text("My code", color = Paper, fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { vm.logout() }) { Icon(Icons.AutoMirrored.Filled.Logout, "sign out", tint = PaperDim) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = InkSoft)
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(28.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(InkCard), contentAlignment = Alignment.Center) {
                Text(name.firstOrNull()?.uppercase() ?: "✿", color = Sunflower, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text(name, color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))

            // The QR — dark on white in a rounded card so it scans cleanly off-screen.
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).padding(18.dp)) {
                if (qr != null) {
                    Image(qr.asImageBitmap(), "your code", modifier = Modifier.size(240.dp))
                } else {
                    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Ink) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Have a friend scan this to message you", color = PaperDim, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))

            // the raw address, copyable as a fallback to QR
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(InkCard)
                    .clickable { clip.setText(AnnotatedString(id)) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(id, color = PaperDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Filled.ContentCopy, "copy", tint = Sunflower, modifier = Modifier.size(18.dp))
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { scan.launch(scanOptions()) },
                colors = ButtonDefaults.buttonColors(containerColor = Sunflower, contentColor = Ink),
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(8.dp))
                Text("Scan a friend's code", fontWeight = FontWeight.SemiBold)
            }
            val busy by vm.busy.collectAsState()
            val err by vm.error.collectAsState()
            if (busy) { Spacer(Modifier.height(14.dp)); CircularProgressIndicator(color = Sunflower) }
            if (err != null) { Spacer(Modifier.height(12.dp)); Text(err!!, color = Color(0xFFE5534B), fontSize = 13.sp, textAlign = TextAlign.Center) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AppViewModel, roomId: String, roomName: String) {
    BackHandler { vm.back() }
    val ctx = LocalContext.current
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
                actions = {
                    IconButton(onClick = {
                        ctx.startActivity(android.content.Intent(ctx, ElementCallActivity::class.java))
                    }) { Icon(Icons.Filled.Videocam, "call", tint = Sunflower) }
                },
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
        if (messages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Lock, null, tint = Sunflower, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("End-to-end encrypted, over Tor", color = PaperDim, fontSize = 13.sp)
                    Text("Say hi 👋", color = PaperDim, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
                state = listState
            ) {
                items(messages, key = { it.key }) { m -> Bubble(m) }
            }
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
