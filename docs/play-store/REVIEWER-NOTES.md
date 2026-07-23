# Play Console — App access (instructions for Google reviewers)

The app is **login-gated**: on first launch it shows a sign-in screen and does nothing until
it connects to a **box** (a PurePrivacy server) over Tor. A reviewer with only the app cannot
proceed — **this is the single most common reason an app like this gets rejected.** So we must
give reviewers a working box to sign into, in Play Console → **App content → App access**
("All or some functionality is restricted" → add instructions).

## Prerequisite: a standing demo box

Before submitting, stand up a **demo box that stays online for the whole review** (and keep it
up for updates). Run it however is easiest — the published Docker image is simplest:

```bash
docker run -d --name pp-demo --restart unless-stopped -v pp-demo-data:/data \
  -p 127.0.0.1:8470:8470 -e PUREPRIVACY_SETUP_BIND=0.0.0.0 jaimemelon/pureprivacy-box:latest
# open http://127.0.0.1:8470/ → create username `demo` + a password → note the onion it shows
```

Optionally pair a second demo box so the reviewer can see a live conversation. Record the
onion + credentials and paste them into the fields below.

## Instructions to paste into "App access"

```
This app connects only to a self-hosted PurePrivacy "box" over the Tor network — it has no
central server, so a test box is provided below. There is no cost and no personal data.

To sign in:
1. Open the app and tap "sign in manually".
2. Box address (.onion):  <DEMO_BOX_ONION>.onion
3. Username:  demo
4. Password:  <DEMO_BOX_PASSWORD>
5. Tap "Connect over Tor" and wait ~30–60 seconds on first connect (Tor circuit setup).
6. You'll be asked to set a 6-digit unlock code and a second "emergency" code — pick any two
   different codes (e.g. 111111 and 222222). This is the app's screen-lock; the emergency code
   is a duress feature that wipes local app data.
7. After that you'll see the app home (Messaging, PP Config).

Notes for review:
- All traffic is over Tor to the box above; first connection can be slow. If it stalls, tap
  "Try again" — a fresh Tor circuit usually connects.
- USE_FULL_SCREEN_INTENT is used for the incoming-call screen (this is a calling app).
- The dataSync foreground service keeps the Tor message connection alive (the app uses no
  Google/FCM push).
- No ads, no analytics, no data sent to the developer.
```

Fill in `<DEMO_BOX_ONION>` and `<DEMO_BOX_PASSWORD>` from the box you stood up. Keep that box
running until the review completes (and for each future update review).

## Also declare, in App content

- **Advertising ID:** app does **not** use it.
- **Full-screen intent (Android 14+):** declare — the app's core function includes calling;
  it uses full-screen intent for the incoming-call screen.
- **Foreground service:** declare the `dataSync` service — keeps the Tor connection alive to
  deliver messages (the app has no FCM/Google push alternative).
