# PurePrivacy — Privacy Policy

**Last updated: 23 July 2026**

PurePrivacy is a private, end-to-end-encrypted messenger and companion app for your own
**box** — a personal server you (or someone you trust) run on your own computer. This policy
explains exactly what the app does with your data. The short version: **we, Tournesol, run no
servers and collect nothing about you.** Your data lives on *your* box and your device.

## Who we are

The app is published by **Tournesol** ("we", "us"). Contact: **privacy@tournesol.ai**.

We are the app's developer. We are **not** a service provider in the usual sense: we do not
operate any messaging server, relay, analytics backend, or cloud that your data passes
through. Your box — the server this app talks to — is run by you, not by us.

## What data the app handles, and where it lives

All of the following stays either **on your device** or **on your own box**, and travels only
over the **Tor network** (encrypted, no clearnet):

- **Your account** — a username and password you choose during setup. These authenticate you
  to *your box*. On your device, the resulting login session token is stored **encrypted at
  rest** using the Android Keystore.
- **Messages, contacts, and media (photos, files, voice notes)** — **end-to-end encrypted**.
  They are stored on your box and cached on your device in an encrypted crypto store. They are
  transmitted only over Tor, to your box and to the boxes of the contacts you have paired with.
- **Voice/video calls** — routed over Tor via your box's call server. (Call media is not yet
  end-to-end encrypted *between participants* — your box's call server relays it; the app tells
  you this in the call screen. It is never sent to us or any third party.)
- **Your app passcode** — if you set one, it is stored only as a **salted cryptographic hash**
  (Keystore-backed), never in plain text, and never leaves your device.

## What we collect: nothing

- **No analytics, no telemetry, no crash reporting to us.** The app contains no advertising or
  analytics SDKs.
- **No Google Play Services, no Firebase, no push tokens.** The app does not use Google's push
  system; message delivery runs over your own box via Tor.
- The in-app call component's third-party telemetry is **actively blocked** — the app refuses
  any network egress that isn't to your box over Tor.

We never see your messages, contacts, media, calls, IP address, or usage. We have nothing to
sell, share, or hand over, because we never receive it.

## No sharing with third parties

We do not share your data with anyone, because we do not have it. The app does not integrate
any third-party service that receives your data. All connections are to your own box, over Tor.

## Permissions and why the app asks for them

- **Internet / network state** — to reach your box over Tor.
- **Notifications** — to tell you about new messages and incoming calls.
- **Foreground service** — to keep a connection to your box alive so messages arrive promptly
  (there is no Google push to fall back on).
- **Full-screen intent** — to show an incoming-call screen, like a normal phone call.
- **Microphone, camera** — only used when *you* start or answer a voice/video call.
- **Bluetooth / modify audio settings** — to route call audio (earpiece, speaker, headset).

The app does **not** request location, contacts, SMS, or broad storage access.

## Security

- Messages and media are **end-to-end encrypted**; only you and the people you pair with can
  read them.
- All network traffic runs over **Tor** to onion addresses — there is no clearnet exposure.
- On-device secrets (session token, passcode hash) are encrypted with the **Android Keystore**.
- The app supports a **duress passcode** that irreversibly erases all app data on your device,
  and (from PP Config) a **box reset** that wipes your box's identity — both are yours to use.

## Data retention and deletion

Because your data lives on your device and your box:

- **Uninstalling the app**, or using the app's **erase / duress** feature, removes all data
  from your device.
- **Resetting your box** (from PP Config, gated behind typing the box name) permanently erases
  your account and its data on the box.

We hold no copy of your data and therefore have nothing to retain or delete on our side. If you
have questions, contact **privacy@tournesol.ai**.

## Children

PurePrivacy is not directed to children under 13 (or the equivalent minimum age in your
jurisdiction), and we do not knowingly collect data from them (we collect no data at all).

## Changes to this policy

If we change this policy, we will update the "Last updated" date above and publish the new
version at the same URL. Material changes will be noted in the app's release notes.

## Contact

Questions about privacy: **privacy@tournesol.ai** · Tournesol.
