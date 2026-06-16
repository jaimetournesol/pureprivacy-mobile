package ai.tournesol.pureprivacy

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import ai.tournesol.pureprivacy.matrix.MatrixRepo
import ai.tournesol.pureprivacy.ui.theme.Danger
import ai.tournesol.pureprivacy.ui.theme.Ink
import ai.tournesol.pureprivacy.ui.theme.InkCard
import ai.tournesol.pureprivacy.ui.theme.Paper
import ai.tournesol.pureprivacy.ui.theme.PaperDim
import ai.tournesol.pureprivacy.ui.theme.Success
import ai.tournesol.pureprivacy.ui.theme.Sunflower
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen incoming-call screen — the ringing experience. Launched by the
 * call notification's full-screen intent (so it wakes the phone over the lock
 * screen) and by its Answer/Decline actions. Owns the ringtone + vibration, and
 * routes Answer into MainActivity (which opens the room and joins the call).
 */
class IncomingCallActivity : ComponentActivity() {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var ringing = false
    private var timeoutJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenSecurity()  // release-only screenshot block (see SecureWindow.kt)
        // Wake the screen + show over the lock screen, like any phone call.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
                ?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                0x00080000 /*FLAG_SHOW_WHEN_LOCKED*/ or 0x00200000 /*FLAG_TURN_SCREEN_ON*/ or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Answer/Decline actions arrive here while the ring screen is showing.
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val roomId = intent?.getStringExtra(PpSyncService.EXTRA_ROOM_ID)
        val caller = intent?.getStringExtra(PpSyncService.EXTRA_ROOM_NAME) ?: "Someone"
        if (roomId == null) { finish(); return }
        when {
            intent.getBooleanExtra(EXTRA_DECLINE, false) -> decline(roomId)
            intent.getBooleanExtra(EXTRA_ANSWER, false) -> answer(roomId, caller)
            else -> ring(roomId, caller)
        }
    }

    private fun ring(roomId: String, caller: String) {
        if (ringing) return
        ringing = true
        startRinging()
        setContent { IncomingCallScreen(caller, onAnswer = { answer(roomId, caller) }, onDecline = { decline(roomId) }) }
        // Stop ringing after ~45s of no answer → missed call.
        timeoutJob = lifecycleScope.launch {
            delay(45_000)
            missed(roomId, caller)
        }
        // If the caller hangs up before we answer, stop ringing → missed call.
        lifecycleScope.launch {
            MatrixRepo.callEnded.collect { if (it == roomId) missed(roomId, caller) }
        }
    }

    private fun answer(roomId: String, caller: String) {
        cleanup(roomId)
        setContent { ConnectingScreen(caller) }
        // Open the room (over Tor, waiting for sync if cold-started) then drop straight
        // into the call. Self-contained — no cross-activity hop, which proved flaky.
        lifecycleScope.launch {
            var w = 0
            while (!MatrixRepo.isLoggedIn && w < 60) { delay(500); w++ }
            w = 0
            while (MatrixRepo.rooms.value.none { it.id == roomId } && w < 40) { delay(500); w++ }
            runCatching { MatrixRepo.openRoom(roomId) }
            // Put the chat behind the call so ending the call returns there.
            startActivity(Intent(this@IncomingCallActivity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(PpSyncService.EXTRA_ROOM_ID, roomId)
                putExtra(PpSyncService.EXTRA_ROOM_NAME, caller)
            })
            startActivity(Intent(this@IncomingCallActivity, ElementCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            finish()
        }
    }

    private fun decline(roomId: String) { cleanup(roomId); finish() }

    private fun missed(roomId: String, caller: String) {
        cleanup(roomId)
        runCatching {
            val n = androidx.core.app.NotificationCompat.Builder(this, PpSyncService.CH_MSG)
                .setContentTitle("Missed call")
                .setContentText("$caller called — over Tor")
                .setSmallIcon(R.drawable.ic_sunflower)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(this).notify(("missed:$roomId").hashCode(), n)
        }
        finish()
    }

    private fun cleanup(roomId: String) {
        timeoutJob?.cancel()
        stopRinging()
        runCatching { NotificationManagerCompat.from(this).cancel(roomId.hashCode()) }
    }

    private fun startRinging() {
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
        runCatching {
            vibrator = (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.apply {
                val pattern = longArrayOf(0, 800, 1000)
                vibrate(VibrationEffect.createWaveform(pattern, 0))
            }
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }; ringtone = null
        runCatching { vibrator?.cancel() }; vibrator = null
    }

    override fun onDestroy() {
        super.onDestroy()
        timeoutJob?.cancel()
        stopRinging()
    }

    companion object {
        const val EXTRA_ANSWER = "pp_answer"
        const val EXTRA_DECLINE = "pp_decline"
    }
}

@Composable
private fun IncomingCallScreen(caller: String, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val s by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )
    Column(
        Modifier.fillMaxSize().background(Ink).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.28f))
        Box(
            Modifier.size(120.dp).scale(s).clip(CircleShape).background(InkCard),
            contentAlignment = Alignment.Center
        ) {
            Text(
                caller.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                color = Sunflower, fontSize = 48.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(caller, color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Incoming call · routed over Tor", color = PaperDim, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        // Honest about the crypto: the media is hidden over Tor, but it is NOT yet
        // end-to-end encrypted between participants (the SFU on the host's box can see
        // it). No padlock — that would falsely imply E2EE.
        Text(
            "Hidden over Tor — voice isn't yet end-to-end encrypted.",
            color = PaperDim, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().padding(bottom = 56.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CallAction("Decline", Icons.Filled.CallEnd, Danger, onDecline)
            CallAction("Answer", Icons.Filled.Call, Success, onAnswer)
        }
    }
}

@Composable
private fun ConnectingScreen(caller: String) {
    Column(
        Modifier.fillMaxSize().background(Ink),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(120.dp).clip(CircleShape).background(InkCard), contentAlignment = Alignment.Center) {
            Text(caller.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                color = Sunflower, fontSize = 48.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        Text(caller, color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Connecting · over Tor…", color = PaperDim, fontSize = 14.sp)
    }
}

@Composable
private fun CallAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(color)
                .clickableNoRipple(onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(label, color = PaperDim, fontSize = 13.sp)
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = src, indication = null, onClick = onClick)
}
