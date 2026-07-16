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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.produceState
import ai.tournesol.pureprivacy.matrix.MatrixRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tournesol.pureprivacy.matrix.ChatMsg
import ai.tournesol.pureprivacy.matrix.RoomSummary
import ai.tournesol.pureprivacy.matrix.SendState
import ai.tournesol.pureprivacy.tor.TorManager
import ai.tournesol.pureprivacy.ui.theme.*
import ai.tournesol.pureprivacy.util.Qr
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    /** Ask for everything a call needs (mic + camera) plus notifications, up front —
     *  without these granted the Element Call WebView's getUserMedia throws
     *  NotReadableError and the call dies with "Something went wrong". */
    private fun requestCorePermissions() {
        val want = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.CAMERA,
        )
        if (Build.VERSION.SDK_INT >= 33) want += android.Manifest.permission.POST_NOTIFICATIONS
        val ask = want.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (ask.isNotEmpty()) perms.launch(ask.toTypedArray())
    }

    // Foreground = snappy backstop poll + live previews; background = gentle poll
    // (sliding sync still delivers in real time — this only changes wake cadence).
    override fun onResume() { super.onResume(); vm.setForeground(true) }
    override fun onStop() { super.onStop(); vm.setForeground(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the login password, identity QR and recovery info out of screenshots
        // and the Recents thumbnail (release builds only — see applyScreenSecurity).
        applyScreenSecurity()
        requestCorePermissions()   // mic + camera (for calls) + notifications
        handleNotifIntent(intent)
        handleDeepLink(intent)
        setContent {
            PurePrivacyTheme {
                // Answered an incoming call from the notification → launch the call UI
                // once the room is open.
                val launch by vm.launchCall.collectAsState()
                LaunchedEffect(launch) {
                    if (launch) {
                        startActivity(Intent(this@MainActivity, ElementCallActivity::class.java))
                        vm.launchCall.value = false
                    }
                }
                Surface(Modifier.fillMaxSize(), color = Ink) {
                    val screen by vm.screen.collectAsState()
                    when (val s = screen) {
                        is Screen.Splash -> SplashScreen(vm)
                        is Screen.Login -> LoginScreen(vm)
                        is Screen.Rooms -> RoomsScreen(vm)
                        is Screen.Profile -> ProfileScreen(vm)
                        is Screen.Paused -> PausedScreen(vm)
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
        handleDeepLink(intent)
    }

    /** A "pureprivacy://…" link was opened (a camera/QR scanner resolved a QR, or
     *  someone tapped the link). Two kinds ride this scheme — the desktop's
     *  "pureprivacy://connect?…" SETUP code and a contact's "pureprivacy:@name:onion"
     *  address. We hand the raw URI to the view model, which routes by type
     *  (onDeepLink): a setup code drives the sign-in pre-fill; a contact is added now
     *  or once a session is ready. */
    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        if (!data.scheme.equals("pureprivacy", ignoreCase = true)) return
        vm.onDeepLink(data.toString())
    }

    /** A tapped message/call notification (or "Answer" from the incoming-call
     *  screen) carries the room — open it, and join the call if answering. */
    private fun handleNotifIntent(intent: Intent?) {
        val roomId = intent?.getStringExtra(PpSyncService.EXTRA_ROOM_ID) ?: return
        val name = intent.getStringExtra(PpSyncService.EXTRA_ROOM_NAME) ?: "Chat"
        val answer = intent.getBooleanExtra(PpSyncService.EXTRA_ANSWER, false)
        vm.openRoomFromNotif(roomId, name, answer)
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

/** The Tor status affordance. When [onRetry] is supplied (the Login/Rooms/Chat
 *  badges all pass vm::retryTor), a FAILED or stuck badge becomes tappable: it opens
 *  a tiny sheet that narrates the slowness ("Connecting over Tor can take a moment")
 *  and offers a retry — turning an inert "Tor: failed" label into a recovery path. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TorBadge(modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val st by TorManager.state.collectAsState()
    val (label, color) = when (val s = st) {
        // Connected = a steady "you are protected" state → Success green, NOT gold.
        // Gold is reserved for actionable controls; a secure status shouldn't read as
        // "tap me". Connecting stays dim, failed stays Danger (those ARE actionable).
        is TorManager.State.Ready -> "Tor: connected" to Success
        is TorManager.State.Bootstrapping -> "Tor: ${s.percent}% ${s.message}" to PaperDim
        is TorManager.State.Failed -> "Tor: failed · tap to retry" to Danger
        else -> "Tor: starting…" to PaperDim
    }
    // Only the failed state is actionable (a healthy/booting Tor needs no nudge).
    val actionable = onRetry != null && st is TorManager.State.Failed
    var showSheet by remember { mutableStateOf(false) }

    Row(
        // [QW-ui] When actionable (tap-to-retry), expose it as a Button to a11y and give
        // it a 48dp min touch target so the small badge is comfortably tappable.
        modifier.then(
            if (actionable) Modifier
                .sizeIn(minHeight = 48.dp)
                .clickable(role = Role.Button) { showSheet = true }
            else Modifier
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Lock, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 12.sp)
    }

    if (showSheet && onRetry != null) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, containerColor = InkSoft) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
                Text("Reconnecting over Tor", color = Paper, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Connecting over Tor can take a moment — private, and a little slower, that's the deal. Tap to try again.",
                    color = PaperDim, fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { onRetry(); showSheet = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Sunflower, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(8.dp))
                    Text("Retry connection", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun SplashScreen(vm: AppViewModel) {
    val tor by vm.torState.collectAsState()
    val phase by vm.restorePhase.collectAsState()
    // While restore is progressing, narrate where we are. A RETURNING user is always
    // told we're "restoring your session" — never left staring at a frozen splash or
    // dumped onto a blank login that reads as "I got logged out".
    val status = when (val s = tor) {
        is TorManager.State.Bootstrapping -> if (s.percent > 0) "Connecting over Tor · ${s.percent}%" else "Connecting over Tor…"
        is TorManager.State.Ready -> "Restoring your session…"
        is TorManager.State.Failed -> "Couldn't reach Tor — keep waiting or try again"
        else -> "Starting Tor…"
    }
    val slow = phase == AppViewModel.RestorePhase.Slow
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SunflowerMark(size = 88)
        Spacer(Modifier.height(20.dp))
        Text("PurePrivacy", color = Paper, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(40.dp))
        CircularProgressIndicator(Modifier.size(28.dp), color = Sunflower, strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text(status, color = PaperDim, fontSize = 14.sp, textAlign = TextAlign.Center)

        // Restore has taken a while (slow Tor, flaky circuit). Surface an explicit,
        // RECOVERABLE state instead of silently falling through to a blank login:
        // narrate the slowness, let them keep waiting, retry, or choose to sign in.
        if (slow) {
            Spacer(Modifier.height(28.dp))
            Text(
                "Still connecting over Tor — this is the slow part.\nPrivate, and a little slower; that's the deal.",
                color = PaperDim, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { vm.retryRestore() },
                colors = ButtonDefaults.buttonColors(containerColor = Sunflower, contentColor = Ink),
                modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(8.dp))
                Text("Try again", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            // Keep waiting = just leave the splash up (restore is still running in the
            // background); offered for reassurance that waiting is a valid choice.
            Text("Keep waiting", color = PaperDim, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { vm.signInInstead() }) {
                Text("Sign in instead", color = Sunflower, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun LoginScreen(vm: AppViewModel) {
    var onion by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    // Reveal the manual onion/username/password fields. Hidden by default so the
    // primary path is "scan the desktop's setup code" — but a scanned code (which
    // pre-fills these) opens them automatically, as does "or sign in manually".
    var manual by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsState()
    val err by vm.error.collectAsState()
    // Heads-up after a setup-code scan ("Box found — enter your password…"). The
    // Login screen has no snackbar host, so we show it inline and clear it on the
    // next sign-in attempt / new scan.
    val notice by vm.notice.collectAsState()

    // A scanned/opened "Connect your phone" code pre-fills the box onion + username
    // (the hard-to-type parts). The password is still the user's to enter — the
    // QR's token is a pairing nonce, not a login credential (see loginFromConnectUri).
    val prefill by vm.loginPrefill.collectAsState()
    LaunchedEffect(prefill) {
        prefill?.let {
            onion = it.onion
            user = it.user
            manual = true               // show the fields so they can type the password
            vm.clearLoginPrefill()
        }
    }

    // Scan the desktop's "Connect your phone" QR. A setup code routes to
    // loginFromConnectUri (pre-fill); anything else is handled by onDeepLink, so a
    // stray contact code scanned here is never misrouted into a chat-with-myself.
    val scan = rememberScan { contents -> if (contents != null) vm.onDeepLink(contents) }

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
        TorBadge(onRetry = vm::retryTor)
        Spacer(Modifier.height(28.dp))

        // Primary affordance: put your box in your pocket by scanning the code the
        // desktop app shows under "Connect your phone" — no 56-char onion to type.
        Button(
            onClick = { scan.launch(scanOptions()) },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = Sunflower, contentColor = Ink),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(8.dp))
            Text("Scan setup code", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(14.dp))
        // Orientation for newcomers: the box lives on their computer.
        Text(
            "New here? Your box lives on your computer — set it up in the PurePrivacy desktop app, then sign in here.",
            color = PaperDim, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 17.sp
        )

        if (!manual) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { manual = true }) {
                Text("or sign in manually", color = Sunflower, fontSize = 13.sp)
            }
        } else {
            Spacer(Modifier.height(24.dp))
            PpField(onion, { onion = it }, "Your box (.onion)")
            Spacer(Modifier.height(12.dp))
            PpField(user, { user = it }, "Username")
            Spacer(Modifier.height(12.dp))
            PpField(pass, { pass = it }, "Password", password = true)
            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { vm.clearNotice(); vm.login(onion, user, pass) },
                enabled = !busy && onion.isNotBlank() && user.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Sunflower, contentColor = Ink),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Ink, strokeWidth = 2.dp)
                else Text("Connect over Tor", fontWeight = FontWeight.SemiBold)
            }
        }

        val status by vm.status.collectAsState()
        if (busy && status.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(status, color = PaperDim, fontSize = 13.sp) }
        if (notice != null && err == null) {
            Spacer(Modifier.height(14.dp))
            Text(notice!!, color = Sunflower, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        if (err != null) {
            Spacer(Modifier.height(14.dp))
            Text(err!!, color = Danger, fontSize = 13.sp, textAlign = TextAlign.Center)
            // Most sign-in failures here are Tor not being ready yet (slow/flaky
            // circuit). Give the error path its own recovery: re-kick Tor and clear
            // the error, so the user isn't stuck re-typing with nothing to try.
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { vm.clearError(); vm.retryTor() }) {
                Icon(Icons.Filled.Refresh, null, tint = Sunflower, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Retry connection over Tor", color = Sunflower, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SunflowerMark(size: Int = 72) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 3.6).dp)).background(InkCard), contentAlignment = Alignment.Center) {
        Image(
            androidx.compose.ui.res.painterResource(R.drawable.ic_sunflower),
            "PurePrivacy",
            modifier = Modifier.size((size * 0.62).dp)
        )
    }
}

/** A read-back identity affordance: a contact's full (or middle-truncated) @name:onion
 *  in monospace, in a tappable chip that copies the FULL address to the clipboard. Lets
 *  two people verify each other out-of-band — read the onion aloud, or paste it. Used on
 *  the profile ("my code") and wherever a contact identity is shown/confirmed.
 *
 *  - [wrap] true renders the whole id across lines (good when you want it fully legible,
 *    e.g. your own code); false middle-truncates a long onion to one line ("@bob:abcd…wxyz.onion").
 *  - [enabled] false greys it out and disables copy (e.g. while the id is still loading). */
@Composable
private fun IdentityChip(
    id: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    wrap: Boolean = true,
    fullWidth: Boolean = false,
) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val shown = if (wrap) id else middleTruncate(id)
    Row(
        modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(RoundedCornerShape(12.dp)).background(InkCard)
            .then(
                if (enabled) Modifier.clickable {
                    clip.setText(AnnotatedString(id))
                    android.widget.Toast.makeText(ctx, "Address copied", android.widget.Toast.LENGTH_SHORT).show()
                } else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            shown,
            color = if (enabled) PaperDim else PaperDim.copy(alpha = 0.5f),
            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            overflow = if (wrap) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (enabled) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Filled.ContentCopy, "copy", tint = Sunflower, modifier = Modifier.size(18.dp))
        }
    }
}

/** Keep both ends of a long @name:onion readable when it can't wrap: "@bob:abcd…wxyz.onion". */
private fun middleTruncate(s: String, head: Int = 14, tail: Int = 10): String =
    if (s.length <= head + tail + 1) s else s.take(head) + "…" + s.takeLast(tail)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PpField(value: String, onChange: (String) -> Unit, label: String, password: Boolean = false) {
    // Let a password field reveal its text — typing a long box password blind is
    // error-prone (and an onion box has no "forgot password" reset). Defaults hidden.
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password && !revealed) PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
        trailingIcon = if (!password) null else {
            {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (revealed) "Hide password" else "Show password",
                        tint = PaperDim,
                    )
                }
            }
        },
        // Onion address, username and password are all case-sensitive lowercase ASCII —
        // never let the IME autocapitalize the first letter or autocorrect them.
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Ascii,
            autoCorrect = false,
            capitalization = KeyboardCapitalization.None
        ),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Sunflower, unfocusedBorderColor = Outline,
            focusedLabelColor = Sunflower, unfocusedLabelColor = PaperDim,
            focusedTextColor = Paper, unfocusedTextColor = Paper,
            cursorColor = Sunflower
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsScreen(vm: AppViewModel) {
    val allRooms by vm.rooms.collectAsState()
    // Mutual consent, made legible. A row shows when it's live (both scanned), an
    // INCOMING request (someone scanned you — scan them back), or an OUTGOING request
    // you started (you scanned them; they haven't scanned back yet). The outgoing one
    // is a persistent, labelled "Pending" row now — not a snackbar that vanishes,
    // leaving the user on an empty "No chats yet" wondering if anything happened.
    val rooms = allRooms.filter { it.paired || it.invited || it.outgoing }
    val busy by vm.busy.collectAsState()
    val err by vm.error.collectAsState()
    val notice by vm.notice.collectAsState()
    val ctx = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var peer by remember { mutableStateOf("") }
    // The chat queued for removal (long-press → Remove), and whether to federate a
    // "left" event (default OFF = silent: they become unreachable, not invisible).
    var pendingRemove: RoomSummary? by remember { mutableStateOf(null) }
    var notify by remember { mutableStateOf(false) }
    BackHandler { (ctx as? android.app.Activity)?.moveTaskToBack(true) }

    val scan = rememberScan { contents -> if (contents != null) vm.addContact(contents) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); vm.clearNotice() }
    }

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
                            focusedBorderColor = Sunflower, unfocusedBorderColor = Outline,
                            focusedTextColor = Paper, unfocusedTextColor = Paper, cursorColor = Sunflower
                        )
                    )
                    if (err != null) { Spacer(Modifier.height(8.dp)); Text(err!!, color = Danger, fontSize = 12.sp) }
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

    pendingRemove?.let { target ->
        val canRemove = target.peerId != null
        AlertDialog(
            onDismissRequest = { if (!busy) { pendingRemove = null; notify = false } },
            containerColor = InkCard,
            // [QW-ui] A long contact name must not blow out the dialog title — cap at 2
            // lines and ellipsize.
            title = { Text("Remove ${target.name}?", color = Paper, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text(
                        "This cuts them off: their box can no longer reach yours, and " +
                            "this chat disappears from your list. You can reconnect later by " +
                            "scanning each other's code again.",
                        color = PaperDim, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(enabled = !busy) { notify = !notify }) {
                        Checkbox(
                            checked = notify, onCheckedChange = { notify = it }, enabled = !busy,
                            colors = CheckboxDefaults.colors(
                                checkedColor = Sunflower, uncheckedColor = Outline, checkmarkColor = Ink
                            )
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Let them know", color = Paper, fontSize = 14.sp)
                    }
                    Text(
                        if (notify)
                            "They'll see you left this chat."
                        else
                            "Silent: they won't be told. They'll just find you " +
                                "unreachable — their box keeps failing to reach yours.",
                        color = PaperDim, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.removeContact(target.peerId ?: return@TextButton, notify)
                        pendingRemove = null; notify = false
                    },
                    enabled = !busy && canRemove
                ) { Text("Remove", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null; notify = false }, enabled = !busy) {
                    Text("Cancel", color = PaperDim)
                }
            }
        )
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.clearError(); showSheet = true },
                containerColor = Sunflower, contentColor = Ink) {
                Icon(Icons.Filled.QrCodeScanner, "new chat")
            }
        },
        topBar = {
            TopAppBar(
                title = { Column { Text("Chats", color = Paper, fontWeight = FontWeight.Bold); TorBadge(onRetry = vm::retryTor) } },
                actions = {
                    IconButton(onClick = { vm.showProfile() }) {
                        Icon(Icons.Filled.AccountCircle, "profile — my code, pause, sign out", tint = Sunflower)
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
                items(rooms, key = { it.id }) { r ->
                    RoomRow(
                        r,
                        // A live chat opens on tap. A pending row no longer launches the
                        // camera on a surprise tap — it shows an explicit, labelled
                        // "Scan their code…" button (onScan) instead.
                        onOpen = { vm.openRoom(r.id, r.name) },
                        onScan = { scan.launch(scanOptions()) },
                        // Long-press → Remove → confirm dialog (built below).
                        onRemove = { pendingRemove = r },
                    )
                }
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

/** Compact relative time for the chat list: "now", "14:32" today, "Mon", or "3 Jun". */
private fun relativeTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 60_000 -> "now"
        android.text.format.DateUtils.isToday(ts) ->
            android.text.format.DateFormat.format("HH:mm", ts).toString()
        diff < 7 * 86_400_000L -> android.text.format.DateFormat.format("EEE", ts).toString()
        else -> android.text.format.DateFormat.format("d MMM", ts).toString()
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RoomRow(r: RoomSummary, onOpen: () -> Unit, onScan: () -> Unit, onRemove: () -> Unit) {
    val pending = !r.paired   // invited (incoming) or outgoing (waiting)
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        // Only a live (paired) chat is tappable-to-open as a whole row. Tap opens it;
        // a long-press opens a menu (Open / Remove) — the entry point for removal. A
        // pending row's action is the explicit labelled button below — tapping/long-
        // pressing the row body does nothing surprising (no silent camera launch, no
        // remove on a chat that isn't live yet).
        Modifier.fillMaxWidth()
            .then(
                if (r.paired) Modifier.combinedClickable(
                    onClick = onOpen,
                    onLongClick = { menuOpen = true }
                ) else Modifier
            )
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        // Long-press menu anchored to the row. Only meaningful for a live chat (the
        // long-press is gated on r.paired above).
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Open") },
                onClick = { menuOpen = false; onOpen() }
            )
            DropdownMenuItem(
                text = { Text("Remove", color = Danger) },
                onClick = { menuOpen = false; onRemove() }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(InkCard), contentAlignment = Alignment.Center) {
                Text(r.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
                    color = if (pending) PaperDim else Sunflower, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, color = if (pending) PaperDim else Paper, fontSize = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    // A live chat shows when it last had activity, right-aligned.
                    if (!pending && r.ts > 0L) {
                        Spacer(Modifier.width(8.dp))
                        Text(relativeTime(r.ts), color = PaperDim, fontSize = 11.sp, maxLines = 1)
                    }
                }
                // Mutual-consent states: incoming request (they scanned you), outgoing
                // (you scanned them, waiting). A live chat shows its last message preview.
                when {
                    r.invited -> Text("Wants to connect", color = Sunflower, fontSize = 12.sp)
                    // "outgoing" = I've scanned them and joined; we're waiting on the
                    // other box. That's EITHER they haven't scanned me back yet OR their
                    // box is still reachable-over-Tor catching up (a first-time pair can
                    // take a minute). Say so honestly instead of the misleading (and
                    // re-scan-inducing) "waiting for them to scan your code".
                    r.outgoing -> Text("Connecting over Tor — can take a minute · they may need to scan you too",
                        color = PaperDim, fontSize = 12.sp)
                    r.preview != null -> Text(r.preview!!, color = PaperDim, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (pending) { Spacer(Modifier.width(8.dp)); Box(Modifier.size(8.dp).clip(CircleShape).background(Sunflower)) }
        }

        // Pending rows: a read-back identity chip (verify the contact out-of-band) plus
        // an EXPLICIT, labelled scan action — never a surprise camera launch on tap.
        if (pending) {
            r.peerId?.let { pid ->
                Spacer(Modifier.height(10.dp))
                IdentityChip(pid, wrap = false, fullWidth = true)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Sunflower),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Sunflower)
            ) {
                Icon(Icons.Filled.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (r.invited) "Scan their code to connect" else "Scan their code to finish",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
    HorizontalDivider(color = Divider)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(vm: AppViewModel) {
    BackHandler { vm.openRooms() }
    val id = vm.myId
    val name = id.removePrefix("@").substringBefore(":")
    // Encode the QR as a "pureprivacy:@name:onion" deep link: ANY camera/QR scanner
    // opens PurePrivacy and adds this contact. Use the bare-scheme form (NOT
    // "pureprivacy://contact/...") so it stays backward-compatible — the original
    // address parser does substringAfter("pureprivacy:"), which yields the right id
    // for "pureprivacy:@id" but mangles a "//contact/" path. Older apps must still be
    // able to scan a newer phone's code, or pairing silently fails on their side.
    // While the id is still blank (session not restored yet) we DON'T encode a blank
    // " " into a broken, scannable-to-nothing QR — we show a "Preparing your code…"
    // placeholder and disable copy until the real address arrives.
    val ready = id.isNotBlank()
    val qr = remember(id) { if (ready) runCatching { Qr.bitmap("pureprivacy:$id", 640) }.getOrNull() else null }

    val scan = rememberScan { contents -> if (contents != null) vm.addContact(contents) }

    // Sign-out sheet — two levels: a plain sign-out vs a destructive full device wipe.
    // Cancel is the safe default (confirm slot); Erase is a distinct destructive action.
    var showSignOut by remember { mutableStateOf(false) }
    if (showSignOut) {
        AlertDialog(
            onDismissRequest = { showSignOut = false },
            containerColor = InkCard,
            icon = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = Sunflower) },
            title = { Text("Sign out?", color = Paper, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Your box and your chats live on your computer — signing back in restores them.",
                        color = PaperDim, fontSize = 13.sp, lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        onClick = { showSignOut = false; vm.logout() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, null, tint = Sunflower); Spacer(Modifier.width(8.dp))
                        Text("Sign out", color = Sunflower, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(
                        onClick = { showSignOut = false; vm.eraseDevice() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.DeleteForever, null, tint = Danger); Spacer(Modifier.width(8.dp))
                        Text("Erase this phone", color = Danger, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Erase also wipes Tor data + caches — nothing left on this device.",
                        color = PaperDim, fontSize = 11.sp, lineHeight = 15.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showSignOut = false }) { Text("Cancel", color = PaperDim) }
            }
        )
    }

    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.openRooms() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Paper) } },
                title = { Text("Profile", color = Paper, fontWeight = FontWeight.Bold) },
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
                val initial = name.firstOrNull()?.uppercase()
                if (initial != null) {
                    Text(initial, color = Sunflower, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                } else {
                    // No name yet (id still blank) — show the brand mark, never a stray glyph.
                    Image(androidx.compose.ui.res.painterResource(R.drawable.ic_sunflower), null, modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(name.ifBlank { "Preparing your code…" }, color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))

            // The QR — dark on white in a rounded card so it scans cleanly off-screen.
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).padding(18.dp)) {
                if (qr != null) {
                    Image(qr.asImageBitmap(), "your code", modifier = Modifier.size(240.dp))
                } else {
                    // Not ready (no id yet) OR still encoding: spinner + "preparing" copy,
                    // never a broken QR encoding a blank address.
                    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Ink)
                            Spacer(Modifier.height(12.dp))
                            Text("Preparing your code…", color = Ink, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Have a friend scan this to message you", color = PaperDim, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))

            // The raw address, copyable as a fallback to QR. Un-clipped: a friend can read
            // the whole @name:onion back to you out-of-band. Copy is disabled until the
            // id is ready (no point copying a blank).
            IdentityChip(
                id = if (ready) id else "Preparing your code…",
                enabled = ready,
                fullWidth = true,
            )

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
            if (err != null) { Spacer(Modifier.height(12.dp)); Text(err!!, color = Danger, fontSize = 13.sp, textAlign = TextAlign.Center) }

            Spacer(Modifier.height(36.dp))
            Text(
                "🔒 No telemetry, no analytics, no trackers.\nNothing leaves your phone but your messages — end-to-end encrypted, over Tor, to your own box.",
                color = PaperDim, fontSize = 12.sp, textAlign = TextAlign.Center,
                lineHeight = 17.sp
            )

            // ── Your name ───────────────────────────────────────────────────────
            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = InkCard)
            Spacer(Modifier.height(16.dp))
            val savedName by vm.displayName.collectAsState()
            var nameDraft by remember(savedName) { mutableStateOf(savedName) }
            Column(Modifier.fillMaxWidth()) {
                Text("Your display name", color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("Paired contacts see this above your messages instead of your onion address. Leave blank to stay anonymous.",
                    color = PaperDim, fontSize = 12.sp, lineHeight = 16.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = nameDraft, onValueChange = { nameDraft = it.take(64) },
                        singleLine = true, placeholder = { Text(name, color = PaperDim) },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Paper, unfocusedTextColor = Paper,
                            focusedBorderColor = Sunflower, unfocusedBorderColor = InkCard,
                            cursorColor = Sunflower)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { vm.setDisplayName(nameDraft) },
                        enabled = nameDraft.trim() != savedName,
                        colors = ButtonDefaults.buttonColors(containerColor = Sunflower, contentColor = Ink),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Save", fontWeight = FontWeight.SemiBold) }
                }
            }

            // ── Privacy ─────────────────────────────────────────────────────────
            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = InkCard)
            Spacer(Modifier.height(16.dp))
            val receipts by vm.readReceipts.collectAsState()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Send read receipts", color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Let contacts see when you've read their messages — and see when they've read yours. Off keeps your reading private.",
                        color = PaperDim, fontSize = 12.sp, lineHeight = 16.sp)
                }
                Switch(
                    checked = receipts, onCheckedChange = { vm.setReadReceipts(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Ink, checkedTrackColor = Sunflower,
                        uncheckedThumbColor = PaperDim, uncheckedTrackColor = InkCard)
                )
            }

            // ── Account ─────────────────────────────────────────────────────────
            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = InkCard)
            Spacer(Modifier.height(16.dp))
            // Pause / go dark: tear down Tor + sync, hide the chats, appear offline.
            OutlinedButton(
                onClick = { vm.pause() },
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Paper),
                border = androidx.compose.foundation.BorderStroke(1.dp, InkCard)
            ) {
                Icon(Icons.Filled.PauseCircleOutline, null, tint = Sunflower); Spacer(Modifier.width(8.dp))
                Text("Pause — go offline", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Text("Stops Tor and hides your chats. Messages wait on your box until you resume.",
                color = PaperDim, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 16.sp)

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { showSignOut = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Logout, null, tint = Danger); Spacer(Modifier.width(8.dp))
                Text("Sign out", color = Danger, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Full-screen "go dark" wall shown while [Screen.Paused]. Tor + sync are down and the
 *  chat list is hidden — a calm, unambiguous offline state with a single Resume action. */
@Composable
fun PausedScreen(vm: AppViewModel) {
    Scaffold(containerColor = Ink) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.PauseCircleOutline, null, tint = Sunflower, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(20.dp))
            Text("Paused — you're offline", color = Paper, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "No messages go in or out, and Tor is off. Your box holds anything sent to you until you resume.",
                color = PaperDim, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { vm.resume() },
                colors = ButtonDefaults.buttonColors(containerColor = Sunflower, contentColor = Ink),
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp))
                Text("Resume", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AppViewModel, roomId: String, roomName: String) {
    BackHandler { vm.back() }
    val ctx = LocalContext.current
    val messages by vm.messages.collectAsState()
    val notice by vm.notice.collectAsState()
    val replyTarget by vm.replyTarget.collectAsState()
    val editTarget by vm.editTarget.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); vm.clearNotice() } }
    val pickFile = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) vm.sendFile(uri) }

    // Voice notes: recording state + the RECORD_AUDIO permission dance.
    val recording by vm.recording.collectAsState()
    val recordElapsed by vm.recordElapsed.collectAsState()
    val micNeeded by vm.micPermissionNeeded.collectAsState()
    val playingVoice by vm.playingVoice.collectAsState()
    val askMic = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> vm.onMicPermission(granted) }
    LaunchedEffect(micNeeded) { if (micNeeded) askMic.launch(android.Manifest.permission.RECORD_AUDIO) }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Paper) }
                },
                title = { Column { Text(roomName, color = Paper, fontWeight = FontWeight.Bold, maxLines = 1); TorBadge(onRetry = vm::retryTor) } },
                actions = {
                    // Audio-only call (phone icon): no camera — a light voice call over Tor.
                    IconButton(onClick = {
                        ctx.startActivity(android.content.Intent(ctx, ElementCallActivity::class.java)
                            .putExtra(ElementCallActivity.EXTRA_AUDIO_ONLY, true))
                    }) { Icon(Icons.Filled.Call, "audio call", tint = Sunflower) }
                    // Video call (camera icon).
                    IconButton(onClick = {
                        ctx.startActivity(android.content.Intent(ctx, ElementCallActivity::class.java))
                    }) { Icon(Icons.Filled.Videocam, "video call", tint = Sunflower) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = InkSoft)
            )
        },
        bottomBar = {
          Column(Modifier.background(InkSoft)) {
            // Reply / edit banner: shows what you're replying to or editing, with a cancel.
            if (replyTarget != null || editTarget != null) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (editTarget != null) Icons.Filled.Edit else Icons.AutoMirrored.Filled.Reply,
                        null, tint = Sunflower, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    val label = if (editTarget != null) "Editing message" else replyTarget!!.let { r ->
                        val who = if (r.mine) "yourself" else r.senderName.ifBlank { r.sender.removePrefix("@").substringBefore(":") }
                        "Replying to $who· ${r.body.take(40)}"
                    }
                    Text(label, color = PaperDim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.cancelCompose(); draft = "" }) {
                        Icon(Icons.Filled.Close, "cancel", tint = PaperDim, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (recording) {
                // Recording bar — replaces the input while a voice note is being captured.
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { vm.cancelRecording() }) {
                        Icon(Icons.Filled.Delete, "discard", tint = Danger)
                    }
                    Box(Modifier.size(9.dp).clip(CircleShape).background(Danger))
                    Spacer(Modifier.width(10.dp))
                    val s = recordElapsed / 1000
                    Text("Recording  %d:%02d".format(s / 60, s % 60), color = Paper, fontSize = 15.sp,
                        modifier = Modifier.weight(1f))
                    Text("🗑 discard · ✓ send", color = PaperDim, fontSize = 10.sp)
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = { vm.stopAndSendRecording() },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Sunflower, contentColor = Ink)
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "send voice note") }
                }
            } else {
              Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { pickFile.launch("*/*") }) {
                    Icon(Icons.Filled.AttachFile, "attach a file", tint = Sunflower)
                }
                // [QW-ui] A trimmed draft is the real payload; blank/whitespace never sends.
                val canSend = draft.isNotBlank()
                val doSend = {
                    if (canSend) { vm.composeSend(draft); draft = "" }
                }
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    placeholder = { Text("Message", color = PaperDim) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(22.dp),
                    // [QW-ui] IME "Send" action submits the message from the keyboard.
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { doSend() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Sunflower, unfocusedBorderColor = Outline,
                        focusedTextColor = Paper, unfocusedTextColor = Paper, cursorColor = Sunflower
                    )
                )
                Spacer(Modifier.width(8.dp))
                // Empty draft → a mic button (record a voice note); typing → the send button.
                // WhatsApp-style swap keeps one primary action in the same spot.
                if (canSend || !vm.canRecordVoice()) {
                    FilledIconButton(
                        onClick = doSend,
                        enabled = canSend,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Sunflower, contentColor = Ink)
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "send") }
                } else {
                    FilledIconButton(
                        onClick = { vm.startRecording() },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Sunflower, contentColor = Ink)
                    ) { Icon(Icons.Filled.Mic, "record a voice note") }
                }
            }
            }
          }   // close the composer Column (reply/edit banner + input row)
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
                state = listState,
                // Anchor the conversation to the bottom: a short thread sits glued to
                // the input bar (not floating at the top), and the newest message stays
                // just above the composer. With windowSoftInputMode=adjustResize the
                // window shrinks for the keyboard, so the latest message rides up with
                // it instead of hiding behind the keyboard until it's dismissed.
                verticalArrangement = Arrangement.Bottom
            ) {
                items(messages, key = { it.key }) { m ->
                    Bubble(m, onAttachment = { vm.saveAttachment(m) }, onRetry = { vm.retrySend(m.key) },
                        onCallBack = { ctx.startActivity(android.content.Intent(ctx, ElementCallActivity::class.java)) },
                        onReply = { vm.startReply(m) },
                        onEdit = { vm.startEdit(m); draft = m.body },
                        onDelete = { m.eventId?.let { vm.deleteMessage(it) } },
                        onReact = { e -> m.eventId?.let { vm.toggleReaction(it, e) } },
                        isPlaying = playingVoice == m.key,
                        onPlayVoice = { vm.playVoice(m) })
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: ChatMsg,
    onAttachment: () -> Unit = {},
    onRetry: () -> Unit = {},
    onCallBack: () -> Unit = {},
    onReply: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onReact: (String) -> Unit = {},
    isPlaying: Boolean = false,
    onPlayVoice: () -> Unit = {},
) {
    // A deleted (redacted) message: a muted tombstone, no bubble, no actions.
    if (m.redacted) {
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp),
            horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
            Text("🚫 Message deleted", color = PaperDim, fontSize = 13.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                modifier = Modifier.padding(horizontal = 12.dp))
        }
        return
    }
    // Call-log entry: a centered chip recording that a call happened, tap to call back.
    if (m.isCall) {
        val declined = m.body.contains("decline", ignoreCase = true)
        val icon = when { m.mine -> Icons.AutoMirrored.Filled.CallMade; declined -> Icons.AutoMirrored.Filled.CallMissed; else -> Icons.AutoMirrored.Filled.CallReceived }
        val tint = if (declined) Danger else PaperDim
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).clickable { onCallBack() }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("${m.body} · tap to call back", color = tint, fontSize = 12.sp)
        }
        return
    }
    val align = if (m.mine) Alignment.End else Alignment.Start
    val isAttachment = m.media != null
    val failed = m.mine && m.sendState == SendState.Failed
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalAlignment = align) {
        if (!m.mine) Text(m.senderName.ifBlank { m.sender.removePrefix("@").substringBefore(":") },
            color = Sunflower, fontSize = 11.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
      Box {   // anchor for the long-press action menu
        Box(
            Modifier.widthIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (m.mine) BubbleMine else BubbleTheirs)
                // Tap: retry a failed send, or save an attachment. Long-press: message
                // actions (react / reply / edit / delete).
                .combinedClickable(
                    onClick = { if (failed) onRetry() else if (isAttachment) onAttachment() },
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            if (m.isVoice && m.media != null) {
                // Voice note: a play/pause control + duration. Tapping the disc downloads
                // the clip over Tor (cached) and plays it; tap again to stop.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (isPlaying) "stop" else "play voice note",
                        tint = if (m.mine) Ink else Sunflower,
                        modifier = Modifier.size(34.dp).clip(CircleShape)
                            .background(if (m.mine) Sunflower else InkCard).padding(5.dp)
                            .clickable { onPlayVoice() }
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Filled.GraphicEq, null, tint = if (m.mine) Ink.copy(alpha = 0.7f) else PaperDim,
                        modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    val secs = (m.voiceMs / 1000).toInt()
                    Text(if (secs > 0) "%d:%02d".format(secs / 60, secs % 60) else "Voice",
                        color = if (m.mine) Ink else Paper, fontSize = 13.sp)
                }
            } else if (m.isImage && m.media != null) {
                // Inline thumbnail: fetch the image bytes over Tor (cached by key) and
                // render them; fall back to the chip while loading / on failure.
                val bmp by produceState<ImageBitmap?>(null, m.key) {
                    value = withContext(Dispatchers.IO) {
                        MatrixRepo.mediaBytes(m.key, m.media!!)?.let { b ->
                            runCatching { android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull()
                        }
                    }
                }
                val img = bmp
                if (img != null) {
                    Column {
                        Image(img, m.fileName ?: "image", contentScale = ContentScale.Fit,
                            modifier = Modifier.widthIn(max = 240.dp).clip(RoundedCornerShape(12.dp)))
                        Text("Tap to save", color = PaperDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                } else {
                    Column {
                        Text(m.body, color = Paper, fontSize = 15.sp)
                        Text("Loading over Tor…", color = PaperDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            } else if (isAttachment) {
                Column {
                    Text(m.body, color = Paper, fontSize = 15.sp)
                    Text("Tap to save", color = PaperDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
            } else {
                Text(m.body, color = Paper, fontSize = 15.sp)
            }
        }
        // Long-press action menu (quick-reactions row + reply/edit/delete), anchored to the bubble.
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = InkCard) {
            Row(Modifier.padding(horizontal = 6.dp)) {
                listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { e ->
                    Text(e, fontSize = 22.sp, modifier = Modifier.padding(6.dp).clickable { menuOpen = false; onReact(e) })
                }
            }
            HorizontalDivider(color = Ink)
            DropdownMenuItem(text = { Text("Reply", color = Paper) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, null, tint = Paper) },
                onClick = { menuOpen = false; onReply() })
            if (m.mine && !isAttachment) DropdownMenuItem(text = { Text("Edit", color = Paper) },
                leadingIcon = { Icon(Icons.Filled.Edit, null, tint = Paper) },
                onClick = { menuOpen = false; onEdit() })
            if (m.mine) DropdownMenuItem(text = { Text("Delete", color = Danger) },
                leadingIcon = { Icon(Icons.Filled.DeleteForever, null, tint = Danger) },
                onClick = { menuOpen = false; onDelete() })
        }
      }   // close anchor Box
        // Reaction chips under the bubble — tap toggles our own.
        if (m.reactions.isNotEmpty()) {
            Row(Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp)) {
                m.reactions.forEach { r ->
                    Row(
                        Modifier.padding(end = 4.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (r.mine) Sunflower.copy(alpha = 0.25f) else InkCard)
                            .clickable { onReact(r.emoji) }.padding(horizontal = 7.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(r.emoji, fontSize = 12.sp)
                        if (r.count > 1) { Spacer(Modifier.width(3.dp)); Text("${r.count}", color = if (r.mine) Sunflower else PaperDim, fontSize = 11.sp) }
                    }
                }
            }
        }
        // Footer: timestamp, plus — for OUR messages — the real delivery state read
        // from the SDK local echo. "sending…" with a clock while it round-trips over
        // Tor, a small ✓ once the server echoes it back, and a tappable "Not sent ·
        // tap to retry" if the send failed. Honours the brand: slowness is narrated,
        // not hidden as silent data loss.
        Row(
            Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (m.ts > 0L) Text(
                remember(m.ts) {
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(m.ts))
                },
                color = PaperDim, fontSize = 10.sp
            )
            if (m.mine) {
                Spacer(Modifier.width(6.dp))
                when (m.sendState) {
                    SendState.Sending -> {
                        Icon(Icons.Filled.Schedule, "sending", tint = PaperDim, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("sending…", color = PaperDim, fontSize = 10.sp)
                    }
                    SendState.Sent ->
                        // A double-check "Read" (only when both sides opted in to receipts)
                        // else a single ✓ for "delivered to the server / federated".
                        if (m.readByPeer) {
                            Icon(Icons.Filled.DoneAll, "read", tint = Sunflower, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(3.dp)); Text("Read", color = Sunflower, fontSize = 10.sp)
                        } else {
                            Icon(Icons.Filled.Check, "sent", tint = PaperDim, modifier = Modifier.size(12.dp))
                        }
                    SendState.Failed ->
                        // [QW-ui] Retry exposed as a Button with a 48dp min touch target.
                        Text("Not sent · tap to retry", color = Danger, fontSize = 10.sp,
                            modifier = Modifier
                                .sizeIn(minHeight = 48.dp)
                                .clickable(role = Role.Button) { onRetry() }
                                .wrapContentHeight())
                }
            }
        }
    }
}
