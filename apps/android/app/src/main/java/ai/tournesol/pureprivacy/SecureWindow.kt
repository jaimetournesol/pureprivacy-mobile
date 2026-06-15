package ai.tournesol.pureprivacy

import android.app.Activity
import android.view.WindowManager

/**
 * Apply FLAG_SECURE — keeps the login password, identity QR, chat, and call
 * surfaces out of screenshots, screen recordings, and the Recents thumbnail.
 *
 * Gated on BuildConfig.DEBUG so development/QA builds remain screenshottable
 * (and drivable by `adb screencap`). Release builds always harden.
 */
fun Activity.applyScreenSecurity() {
    if (BuildConfig.DEBUG) return
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
}
