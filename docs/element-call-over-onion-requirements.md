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

## Stock-client escape hatch (no branded client needed)

Element **Web in Tor Browser** treats `http://<onion>` as a secure context, so it
is the one stock client expected to complete an Element Call over Tor. Useful for
a demo before the branded client lands; not a shippable mobile UX.
