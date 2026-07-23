# Play Console — Data safety form answers

How to fill the **Data safety** section (Play Console → App content → Data safety). The app's
real behaviour: user data is **end-to-end encrypted**, travels **only over Tor**, and goes
**only to the user's own box** — never to Tournesol or any third party. We (the developer)
receive nothing.

> **Disclosure stance.** Google defines "collected" as *data transmitted off the device*. The
> app *does* transmit messages/media off the device (to the user's own box), so the safe,
> non-removable answer is to **declare those data types as collected but NOT shared**, purpose
> *App functionality*, encrypted in transit, and user-deletable. Under-disclosing ("collects
> nothing") is the thing that gets apps pulled — so we over-disclose and explain the E2EE /
> self-hosted reality in the privacy policy. Do **not** tick any Analytics/Advertising purpose.

## Overview questions

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (Tor + end-to-end encryption) |
| Do you provide a way for users to request that their data is deleted? | **Yes** (in-app erase / duress wipe; box reset from PP Config) — plus the deletion URL / instructions |
| Is your app's data collection independently reviewed against a security standard? | No (optional; leave unticked) |

## Data types to declare

For **every** row below: **Collected = Yes**, **Shared = No**, **Processed ephemerally = No**
(it's stored), **Required = Yes**, **Purpose = App functionality** *only* (never Analytics,
Advertising, Personalization, or Account management beyond functionality). Mark **not** used
for tracking.

| Data type (Google category) | Why it applies | Notes |
|---|---|---|
| **Messages → In-app messages** | The core feature — chats | E2EE; only to the user's box + paired peers |
| **Photos and videos** | Sending images/video in chat | E2EE |
| **Audio → Voice or sound recordings** | Voice notes + voice/video calls | Voice notes E2EE; call media relayed by the user's own box's SFU |
| **Files and docs** | Sending files in chat | E2EE, 15 MB cap |
| **Personal info → User IDs** | The username you pick at sign-in | Authenticates you to your own box; not a real name |

## Do NOT declare (the app does not touch these)

- **Location** — never requested.
- **Contacts** (the device address book) — the app never reads your phone contacts; pairing
  uses QR/onion identities only.
- **Device or other IDs / Ad ID** — none; no advertising, no Google Play Services.
- **Financial info, Health, Browsing history, Installed apps** — none.
- **App activity / diagnostics to the developer** — none (no analytics or crash SDK).

## Free-text clarification (paste where allowed)

> PurePrivacy is a self-hosted, Tor-only, end-to-end-encrypted messenger. All user data is
> end-to-end encrypted and is transmitted only over the Tor network to a server ("box") that
> the user themselves runs. The developer operates no servers and receives, stores, or shares
> no user data. Data is deletable by uninstalling, by the in-app erase feature, or by resetting
> the box.
