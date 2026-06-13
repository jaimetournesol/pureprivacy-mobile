# Element Call over Tor — requirements for the branded client (Phase 2)

**Why this doc exists:** a 2-emulator test (2026-06-13) proved every layer of
cross-install voice over Tor *except* the Element Call WebView on **stock Element
X**, which is blocked by its own web-security policy — not by Tor or the media
path. Full findings: `pureprivacy-private/docs/redesign/2026-06-emulator-voice-test.md`.
The media plane itself is **proven** to traverse Tor (TURN-relay-at-onion, 0%
loss: `pureprivacy-private/docs/redesign/2026-06-media-over-tor.md`). So the
branded client's job is to remove the **client-side** barriers below.

## What's already proven (don't re-litigate)

- Tor + Matrix + onion homeserver login from a real Element X over Orbot ✅
- Cross-install federation/invite/join over Tor ✅
- tuwunel focus advertisement (`org.matrix.msc4143.rtc_foci`) ✅
- Media bytes over Tor via a TURN relay published at an `.onion` ✅
- Desktop box already force-relays the SFU through coturn-at-onion (config.rs) ✅

## The barrier to fix in the branded client

Element Call runs as a **web app in an embedded WebView** (Element X serves it
from a secure `appassets` origin). Over a plain `.onion` it then can't reach the
box's call endpoints because:

1. **Mixed content.** The secure-context call page can't `fetch()` the advertised
   `http://<onion>:8082` (lk-jwt) — Chromium blocks it.
2. **Cert trust.** A self-signed `https://<onion>` (lk-jwt or the `wss://<onion>`
   SFU) is rejected — Android WebView has no Tor-Browser "http://.onion is secure"
   rule and no per-onion cert exception.

## What the branded client must do (pick the combination)

- [ ] **Embed Tor** (arti-client) and route the call WebView through it (already
      the plan — drops the Orbot dependency).
- [ ] **Make the embedded call surface trust the box's onion**, via one of:
  - Pin/trust the box's self-signed onion TLS cert for the embedded WebView
    (`WebViewClient.onReceivedSslError` → proceed only for the paired onion;
    Android), and serve lk-jwt + SFU over `https://<onion>` (Caddy already has the
    cert + a wss site — add an lk-jwt https site too). This removes BOTH the mixed-
    content and the cert problems at once. **Preferred.**
  - OR treat `http://<onion>` as a secure/trustworthy origin for the call WebView
    and allow mixed content (`WebSettings.MIXED_CONTENT_ALWAYS_ALLOW` scoped to the
    call surface) — weaker, http-only.
  - OR serve the call surface + endpoints under a single **privileged app scheme**
    so the whole call context is first-party-secure.
- [ ] iOS (WKWebView): the analogous fixes are a custom `URLSchemeHandler` /
      `WKNavigationDelegate` server-trust override pinned to the paired onion cert.

## Box-side change this implies (small, do alongside)

- Advertise lk-jwt over **`https://<onion>`** (add a Caddy TLS site →
  `reverse_proxy 127.0.0.1:8082`, map an onion port to it) and set
  `[global.well_known].livekit_url = https://<onion>:<port>` in `config.rs`. The
  SFU is already `wss://<onion>:7443` via Caddy. Then a cert-trusting client has a
  clean all-https call path with no mixed content.

## UPDATE 2026-06-13 — branded client built; the REAL wall found + measured

The branded client now drives Element Call itself (`ElementCallActivity`): SDK
widget driver (`newVirtualElementCallWidget` + `generateWebviewUrl` +
`makeWidgetDriver` + `getElementCallRequiredPermissions`) in a Tor-proxied,
cert-trusting, mic/cam-granting WebView with a postMessage bridge. **Proven live:**
EC v0.20.1 loads over Tor, the widget handshake completes (after fixing an
echo-loop: only forward `fromWidget` *requests* and `toWidget` *responses* to the
driver — never re-forward what we inject), the embedded ("matryoshka") client
**finishes initial sync** through the bridge, and **MatrixRTCSession runs**. Box
side: lk-jwt + client API now served HTTPS on the onion (no mixed content).

**The wall (measured):** Chromium **WebView won't reach `.onion` hostnames**
directly — RFC 7686 special-use handling (exactly what Tor Browser patches).
EC's direct fetch to `https://<onion>:8009/_matrix/client/versions` (and it'd be
the same for the SFU `wss://<onion>:7443` and lk-jwt) just hangs; `call.element.io`
(clearnet) loads fine through the same Tor proxy. Switching the WebView proxy from
HTTP-tunnel to SOCKS5 (remote DNS) did **not** help — the block is above the proxy.

**The fix (scoped next step): local TLS-terminating Tor forwarders.** Make the
WebView only ever see `127.0.0.1:<port>` (allowed) while the app tunnels each to
the onion over Tor:
- raw TCP forwarder `127.0.0.1:P → onion:port` via the app's Tor SOCKS (handles the
  SFU `wss` too, which `shouldInterceptRequest` can't);
- string-replace the onion baseUrl → `https://127.0.0.1:P` in the generated EC URL;
- rewrite the focus/SFU URLs `.onion → 127.0.0.1` in the `/.well-known` and lk-jwt
  responses (needs a TLS-terminating rewriting proxy, since `shouldInterceptRequest`
  can't read POST bodies and the traffic is end-to-end TLS to the onion);
- `onReceivedSslError` proceed covers the CN-vs-127.0.0.1 mismatch.
This is ~150 lines + cert handling. Until then, calls reach "Loading…" (EC up over
Tor) but don't connect media.

## ✅ SOLVED 2026-06-14 — Element Call CONNECTS over Tor in the branded client

Verified live on the emulator: alice joined the "alice & bob" call entirely over
Tor. LiveKit logged `participant active @alice:<onion>`; lk-jwt logged `Got user
info for @alice:<onion>` (token validated over Tor). The whole chain — EC app,
widget bridge, homeserver `/versions`, lk-jwt `/sfu/get`+`/get_token`, SFU join —
ran over Tor. Implementation: `apps/android/.../net/TorNet.kt` + `ElementCallActivity.kt`.

The architecture that made it work (the WebView only ever sees 127.0.0.1):
1. **Serve Element Call ITSELF from `127.0.0.1`** (local NanoHTTPD proxy → call.element.io
   over Tor). This is the crux — Chromium WebView refuses `.onion` (RFC 7686) AND blocks
   Private Network Access from a *public* origin (call.element.io → 127.0.0.1). With EC at
   `http://127.0.0.1`, its origin is **private + secure** (Chromium special-cases localhost),
   so all its calls to our other 127.0.0.1 bridges are private→private and getUserMedia works.
2. **Local proxies** for the homeserver client API + lk-jwt (NanoHTTPD → onion over Tor),
   rewriting `.onion`→`127.0.0.1` in TEXT responses (binary passes byte-exact).
3. **TLS forwarder** for the SFU WebSocket (ws://127.0.0.1 → wss://onion over Tor SOCKS5).
4. **shouldInterceptRequest** serves EC's direct `.onion` server-name `/.well-known` over Tor.
5. **EC in an iframe at the `/room` route** — EC only enters widget/room mode when embedded
   (`window.parent !== window`); root `/` is its standalone "start new call" home.
6. CSP/X-Frame/CORP stripped; CORS + `Access-Control-Allow-Private-Network: true` added;
   `onReceivedSslError` proceed; mixed-content allowed.
Box side: homeserver client API + lk-jwt now served HTTPS on the onion.

### Remaining refinements (call connects; these harden it)
- **Media RTP over Tor.** WebRTC negotiated direct-UDP (it can't use a `turn:<onion>`
  server — same `.onion` block). For onion-pure media add a `turn:127.0.0.1:<port>`
  localhost bridge → onion coturn, and get the client to use it (rewrite the SFU's
  advertised TURN URI, which needs a TLS-terminating WS proxy on the SFU bridge, or a
  client-injected ICE server).
- **Two-way cross-install.** The joiner must bridge to the call's FOCUS onion (the peer's
  box, where the SFU+lk-jwt live), not its own — discover the focus onion from the
  `wss://<onion>:7443`/`https://<onion>:8443` URLs in the widget-API call-state messages,
  start bridges to it dynamically, and rewrite those URLs → localhost in the bridge.

## Stock-client escape hatch (no branded client needed)

Element **Web in Tor Browser** treats `http://<onion>` as a secure context, so it
is the one stock client expected to complete an Element Call over Tor. Useful for
a demo before the branded client lands; not a shippable mobile UX.
