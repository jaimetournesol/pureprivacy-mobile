package ai.tournesol.pureprivacy.util

/**
 * Translate raw SDK / Tor / reqwest exceptions into calm, human copy.
 *
 * The matrix-rust-sdk surfaces failures verbatim — a 56-char onion that couldn't be
 * reached over a half-built Tor circuit reads as `ClientException.MatrixApi`,
 * `ClientBuildException.ServerUnreachable`, or a bare reqwest `error sending request …
 * Connection refused (os error 111)`. None of that means anything to someone who just
 * wants to sign in. Map the cases we can recognise to one of three reassuring lines,
 * with a generic Tor-slowness fallback for everything else. The RAW text is still
 * logged (callers keep `Log.x(..., t)`) — only the on-screen copy is softened.
 *
 * Matching is intentionally string/keyword based on the throwable + its cause chain,
 * not a `when` over sealed SDK types: the same user-facing failure arrives as several
 * different classes depending on which layer (auth, client build, plain IO over the
 * SOCKS proxy) noticed it first, and the keywords are stable across SDK bumps.
 */
/** Flatten a throwable's message + every cause + class names (and the SDK's M_… code)
 *  into one lowercase haystack for keyword matching. Shared by [mapError] and
 *  [isAuthError] so both classify off the exact same text. */
private fun haystackOf(t: Throwable): String = buildString {
    var cur: Throwable? = t
    var hops = 0
    while (cur != null && hops < 8) {
        val e = cur
        append(e.javaClass.simpleName).append(' ')
        e.message?.let { append(it).append(' ') }
        // The SDK's MatrixApi error carries the M_… code on a `code` property; pull
        // it in too so we can match on the Matrix error code, not just the message.
        runCatching {
            e.javaClass.methods.firstOrNull { it.name == "getCode" && it.parameterCount == 0 }
                ?.invoke(e)?.let { append(it).append(' ') }
        }
        cur = e.cause.takeIf { it !== e }
        hops++
    }
}.lowercase()

/** True when [t] is a wrong-username/password (auth) failure — which retrying will
 *  NEVER fix. Login uses this to surface bad credentials immediately instead of looping
 *  its internal cold-Tor retry; everything else (Tor warm-up, unreachable box, flaky
 *  circuit) is transient and worth another attempt. */
fun isAuthError(t: Throwable): Boolean =
    haystackOf(t).let { h ->
        listOf("m_forbidden", "forbidden", "invalid username", "invalid password",
            "m_unauthorized", "wrong password", "unauthorized").any { h.contains(it) }
    }

fun mapError(t: Throwable): String {
    val haystack = haystackOf(t)

    fun has(vararg needles: String) = needles.any { haystack.contains(it) }

    return when {
        // Wrong credentials. Matrix returns M_FORBIDDEN / a Forbidden ErrorKind; the
        // box authenticates the phone with m.login.password, which is case-sensitive.
        has("m_forbidden", "forbidden", "invalid username", "invalid password",
            "m_unauthorized", "wrong password", "unauthorized") ->
            "That username or password didn't match — they're case-sensitive."

        // Tor isn't ready yet — the SOCKS proxy is up but the circuit/onion descriptor
        // isn't, or we bailed because TorManager wasn't Ready. Distinct from the box
        // being unreachable: here it's our OWN side that's still warming up.
        has("tor", "socks", "bootstrap", "not ready", "still connecting", "circuit",
            "onion descriptor", "proxy") ->
            "Still connecting over Tor — give it a moment and try again."

        // The box (or the peer's box) couldn't be reached: timeout, refused, DNS/onion
        // lookup failed, server unreachable. Usually the box is asleep or offline.
        has("serverunreachable", "wellknownlookupfailed", "unreachable", "timeout",
            "timed out", "timeelapsed", "refused", "connection reset", "connectionfailed",
            "connectiontimeout", "could not reach", "error sending request",
            "no route", "host", "os error 111", "os error 110", "dns", "not found",
            "name or service") ->
            "Couldn't reach your box. It may be asleep — make sure it's running, then try again."

        // Everything else: most likely just slow/flaky Tor on this attempt.
        else ->
            "Something didn't go through — it's often just slow Tor. Try once more."
    }
}
