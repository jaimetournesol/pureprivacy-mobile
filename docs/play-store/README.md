# Publishing PurePrivacy on Google Play — checklist

Everything needed to get the Android app (`ai.tournesol.pureprivacy`) onto Google Play, under
the **Tournesol** organization. Docs in this folder:

- [`PRIVACY-POLICY.md`](PRIVACY-POLICY.md) — the privacy policy (needs hosting at a public URL).
- [`DATA-SAFETY.md`](DATA-SAFETY.md) — exact answers for the Play Console Data-safety form.
- [`REVIEWER-NOTES.md`](REVIEWER-NOTES.md) — App-access instructions + demo box for reviewers.
- [`PHOTO-PERMISSIONS.md`](PHOTO-PERMISSIONS.md) — justification for broad photo/video access.

## Account (longest lead time — start now)

- [ ] Company-owned **Google account** (a Workspace role account on `tournesol.ai`).
- [ ] **D-U-N-S number** for Tournesol (free, from Dun & Bradstreet; can take up to ~30 days). ⚠️ gating item
- [ ] Create Play Console account → **Organization**, pay **$25**, enter the D-U-N-S, verify.
- [ ] **Closed testing (≥12 testers, ≥14 days)** — this rule targets **personal** developer
      accounts. An **organization** account is normally exempt; confirm in Console once the
      account exists rather than assuming either way. If it does apply, it becomes the long pole.

## Technical — status

- [x] **16 KB native libs** — verified: tor, matrix-rust-sdk, JNA, graphics.path all 16 KB-aligned.
- [x] **App Bundle** — `./gradlew :app:bundleRelease` produces a signed `.aab` (re-verified 2026-07-27, 68.7 MB at 0.1.46).
- [x] **Foreground-service audit** — **two** now, both `dataSync`: `PpSyncService` (keeps Tor +
      Matrix sync alive; no FCM is possible on a Tor-only app) and WorkManager's
      `SystemForegroundService` (chunked/continuous backup uploads). Both must be justified in
      the FGS declaration; Android 15 also caps `dataSync` at ~6h/day, which continuous sync
      must tolerate.
- [x] **64-bit** — ships `arm64-v8a` (+ `x86_64` for emulators).
- [x] **`targetSdk` 35** — done in 0.1.36; edge-to-edge handled (chat composer inset fix, 0.1.42).
- [ ] **Upload key — DECIDE FIRST, it's a one-way door.** The release config signs with the
      debug-derived cert `96a71aa6…` (alias `androiddebugkey`) that EVERY sideloaded build and
      GitHub release has used. Two options:
      - **Recommended:** upload that existing key to Play as the *app signing key*. Play-signed
        and GitHub-sideloaded builds then have the same signature, so users can move between
        them freely (Arnaud, jaimephone, testers).
      - Generate a fresh upload key: cleaner hygiene, but Play builds and our GitHub APKs become
        **mutually un-installable** — switching requires uninstall + re-pair. Don't do this
        without deciding sideloading is going away.
- [ ] (Optional) **R8/minify** — currently off; needs keep-rules for matrix-rust-sdk + JNA, tested.

## NEW policy surface added since this list was written (0.1.39 → 0.1.46)

- [x] **Photo & video permissions declaration** — drafted in [`PHOTO-PERMISSIONS.md`](PHOTO-PERMISSIONS.md);
      paste into Console. *Highest new rejection risk.* Continuous backup
      (feature G) added `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`, i.e. **broad** media access.
      Google's default expectation is the Android Photo Picker, and broad access needs a written
      justification in Console. Ours is genuine — the app must *detect new photos on its own* to
      back them up continuously, which the picker cannot do — but it has to be argued, and it may
      draw scrutiny. (The one-time "Back up files" path already uses the system picker.)
- [x] **Data-safety answers updated for Backup Sync** — `DATA-SAFETY.md` now covers it.
      *(Was: predates Backup Sync.)* It must now
      cover photos/videos and files. Key framing: they are uploaded **to the user's own box**,
      end-to-end encrypted, never to us, and we operate no server — so "collected/shared" is
      **no**, with the transfer explained.
- [x] **"Self-updating app" pre-empted** in `REVIEWER-NOTES.md`. The box-update feature (0.1.43+)
      makes the app show an update for the **user's own server** and send an approval. The APK
      never downloads or runs code — that stays entirely on the box. Say so explicitly; a
      reviewer skimming "update available → install" could misread it as violating the
      Device & Network Abuse policy.

## Store listing & policy

- [ ] **Privacy policy** hosted at a public URL (host [`PRIVACY-POLICY.md`](PRIVACY-POLICY.md)
      on tournesol.ai or GitHub Pages) → paste the URL into the listing.
- [ ] **Data safety** form → fill per [`DATA-SAFETY.md`](DATA-SAFETY.md).
- [ ] **App access** (reviewer login) → per [`REVIEWER-NOTES.md`](REVIEWER-NOTES.md) + a demo box kept up.
- [ ] **Content rating** — IARC questionnaire (Communication app; users interact; no ads/location).
- [ ] **Declarations** — Advertising ID: not used; Full-screen intent: declare (calling app);
      Government app: no; News app: no.
- [ ] **Listing assets** — app icon 512×512, feature graphic 1024×500, ≥2 phone screenshots,
      short + full description, category **Communication**, contact email.
- [ ] **Account deletion** — point Google's deletion policy at the in-app erase / box-reset
      (and, if hosting a deletion web page, its URL).

## Review risks specific to this app (pre-empt them)

1. **Can't test without a box** → provide the demo box + reviewer instructions (above). #1 rejection cause.
2. **No FCM / always-on Tor sync** → the `dataSync` FGS; explain it; expect Android-15 FGS scrutiny.
3. **Bundled Tor executed at runtime** → allowed (Orbot/Briar precedent); explain if asked.

## Build the artifact to upload

```bash
cd apps/android
cp keystore.properties.example keystore.properties   # then edit to point at your UPLOAD key
./gradlew :app:bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```
