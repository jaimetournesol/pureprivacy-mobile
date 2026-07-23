# Publishing PurePrivacy on Google Play — checklist

Everything needed to get the Android app (`ai.tournesol.pureprivacy`) onto Google Play, under
the **Tournesol** organization. Docs in this folder:

- [`PRIVACY-POLICY.md`](PRIVACY-POLICY.md) — the privacy policy (needs hosting at a public URL).
- [`DATA-SAFETY.md`](DATA-SAFETY.md) — exact answers for the Play Console Data-safety form.
- [`REVIEWER-NOTES.md`](REVIEWER-NOTES.md) — App-access instructions + demo box for reviewers.

## Account (longest lead time — start now)

- [ ] Company-owned **Google account** (a Workspace role account on `tournesol.ai`).
- [ ] **D-U-N-S number** for Tournesol (free, from Dun & Bradstreet; can take up to ~30 days). ⚠️ gating item
- [ ] Create Play Console account → **Organization**, pay **$25**, enter the D-U-N-S, verify.
- [ ] **Closed testing:** ≥12 testers opted-in for ≥14 continuous days before applying for
      production. Recruit testers in parallel with everything else.

## Technical — status

- [x] **16 KB native libs** — verified: tor, matrix-rust-sdk, JNA, graphics.path all 16 KB-aligned.
- [x] **App Bundle** — `./gradlew :app:bundleRelease` produces a valid signed `.aab`.
- [x] **Foreground-service audit** — one service (`PpSyncService`, `dataSync`); no mic/cam FGS.
- [x] **64-bit** — ships `arm64-v8a` (+ `x86_64` for emulators).
- [ ] **Upload key** — generate a dedicated secret key (see `apps/android/keystore.properties.example`),
      point `keystore.properties` at it, enroll in **Play App Signing**. *(Currently the release
      config signs with the debug cert — fine for sideloading, replace for Play.)*
- [ ] **`targetSdk` 34 → 35** — Play's floor is Android 15 now (`compileSdk` is already 36). Bump
      + test on-device (Android 15 turns on edge-to-edge + a ~6h `dataSync` FGS cap).
- [ ] (Optional) **R8/minify** — currently off; needs keep-rules for matrix-rust-sdk + JNA, tested.

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
