# Play Console — Photo & video permissions declaration

`READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` (and `READ_EXTERNAL_STORAGE` on Android ≤ 12) are
**broad** media permissions. Google's default expectation is the **Android Photo Picker**, and
broad access must be justified in Console → **App content → Photo and video permissions**.

**This is the most likely rejection point for this app**, so the justification has to be exact,
and — importantly — it has to be *true of the shipped build*. It is: the app uses the system
picker everywhere a picker can do the job, and asks for broad access only for the one feature
that a picker fundamentally cannot implement.

## What the app actually does (verify before submitting)

| Feature | Access used | Why |
|---|---|---|
| Attaching a file/photo to a chat | **System picker** (`GetContent`) | user picks, one-off |
| Profile photo | **System picker** | user picks, one-off |
| "Back up files" (one-time) | **System picker** (`OpenMultipleDocuments`) | user picks, one-off |
| "Add a folder" to keep in sync | **System folder picker** (`OpenDocumentTree`, persisted) | user picks the folder |
| **"Auto-back up photos & videos"** | **Broad media access** (`READ_MEDIA_IMAGES/VIDEO`) | ← the only broad use |

Broad access is requested **only** when the user turns on that one switch, at the moment they
turn it on — not at install, and not for any other feature.

## The justification (paste into Console)

```
PurePrivacy is a self-hosted backup and messaging app. Users run their own private server
(a "box") and the app backs their data up to it over Tor, end-to-end encrypted. The developer
operates no servers and receives no user data.

Broad photo and video access is used for exactly one optional, user-enabled feature:
"Auto-back up photos & videos". When the user turns this on, the app must, on its own and
without further interaction, detect photos and videos newly added to the device so it can copy
them to the user's own server — this is the core function of an automatic photo-backup feature.

The Android Photo Picker cannot serve this purpose. The picker returns a one-off, user-driven
selection; it provides no way for an app to discover that a new photo has been taken since the
last backup. An automatic backup that required the user to manually re-select their new photos
would not be automatic, and would not be a backup.

Everywhere a picker CAN do the job, we use it: chat attachments, profile photos, one-time
"Back up files", and choosing a folder to keep in sync all use the system picker or the system
folder picker. Broad access is requested only when the user enables automatic photo backup,
and only at that moment.

The permission is used solely to read the user's own media in order to upload it to the user's
own server. Media is end-to-end encrypted, travels only over the Tor network, is never sent to
the developer or any third party, and is never used for advertising, analytics or tracking.
Turning the feature off stops all access.
```

## Points to have ready if Google pushes back

1. **"Use the Photo Picker instead."** The picker cannot detect *new* media — it only returns
   what a user manually selects in that moment. Automatic backup is definitionally continuous.
   Comparable accepted apps: Google Photos, Nextcloud, Proton Drive, Syncthing.
2. **"Why not ask only when backing up?"** We do — the permission prompt appears when the user
   turns the switch on, never before.
3. **"Where does the media go?"** Only to the user's own box, over Tor, E2EE. No developer
   server exists to receive it. See `DATA-SAFETY.md` and the privacy policy.
4. **Scope honesty.** Enabling the feature backs up media added *from that point on* by
   default; the user can opt to include existing photos. Either way it is user-initiated.

## If the declaration is refused

The feature degrades rather than dies: drop `READ_MEDIA_IMAGES/VIDEO` and keep the
folder-based sync (`OpenDocumentTree` is persisted and needs no broad media permission — the
camera folder can be added as a watched folder). Automatic *camera-roll* backup would be lost
on Play builds; the GitHub build could retain it. Decide before shipping a half-working switch.
