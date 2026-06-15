#!/usr/bin/env bash
# PurePrivacy auto-demo driver. Drives both emulators through the full flow so it
# can be screen-recorded hands-free. Usage: demo-drive.sh <phase>
#   phases: reset login connect chat call hangup all
set -uo pipefail
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
A=emulator-5554   # ALICE
B=emulator-5556   # BOB
PKG=ai.tournesol.pureprivacy
ONION1=n7jzgndncojguozwp36arrdkeruf7jsgw6h6echfzwrksdfognexutyd.onion
ONION2=ttdu4rsuqadza7ezl4mh4kotsyyveqv2jmzm7wj6ffg45fzpmrpwdgyd.onion
ALICE_ID="@alice:${ONION1}"
BOB_ID="@bob:${ONION2}"
PASS=pureprivacy123

say(){ printf '\n\033[38;5;220m▶ %s\033[0m\n' "$*"; }
zzz(){ adb -s "$1" shell sleep "$2" >/dev/null 2>&1; }

ui(){ adb -s "$1" shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb -s "$1" shell cat /sdcard/u.xml 2>/dev/null; }

# echo "cx cy" for the first node whose serialized attrs match $2 (regex over text/content-desc)
findc(){
  local xml b; xml=$(ui "$1")
  b=$(printf '%s' "$xml" | tr '>' '\n' | grep -iE "$2" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+')
  [ -z "$b" ] && return 1
  local x1 y1 x2 y2; x1=$(echo "$b"|sed -n 1p); y1=$(echo "$b"|sed -n 2p); x2=$(echo "$b"|sed -n 3p); y2=$(echo "$b"|sed -n 4p)
  echo $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
}

tapm(){ # serial regex : tap first match
  local c; c=$(findc "$1" "$2") || { echo "   (tapm: not found: $2)"; return 1; }
  adb -s "$1" shell input tap $c
}

waitm(){ # serial regex timeoutSec : wait until a node matches
  local i; for i in $(seq 1 "${3:-30}"); do
    ui "$1" | grep -qiE "$2" && return 0; zzz "$1" 1
  done; return 1
}

hasm(){ ui "$1" | grep -qiE "$2"; }

# Only press BACK if the IME is actually shown — otherwise BACK exits the app
# (the login screen has no back handler), which broke earlier runs.
ime_up(){ adb -s "$1" shell dumpsys input_method 2>/dev/null | grep -q 'mInputShown=true'; }
hide_ime(){ if ime_up "$1"; then adb -s "$1" shell input keyevent 4 >/dev/null 2>&1; zzz "$1" 1; fi; }

login(){ # serial onion username
  local s="$1" onion="$2" user="$3"
  adb -s "$s" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1; zzz "$s" 2
  waitm "$s" 'text="Your box' 30 || true
  local c; c=$(findc "$s" 'text="Your box') || c="540 957"; adb -s "$s" shell input tap $c
  adb -s "$s" shell input text "$onion"
  c=$(findc "$s" 'text="Username"') || c="540 1166"; adb -s "$s" shell input tap $c
  adb -s "$s" shell input text "$user"
  c=$(findc "$s" 'text="Password"') || c="540 1366"; adb -s "$s" shell input tap $c
  adb -s "$s" shell input text "$PASS"
  hide_ime "$s"
  tapm "$s" 'text="Connect over Tor"' || adb -s "$s" shell input tap 540 1562
}

case "${1:-all}" in
  reset)
    say "Resetting both apps to a clean login screen"
    for s in "$A" "$B"; do
      adb -s "$s" shell pm clear "$PKG" >/dev/null 2>&1
      adb -s "$s" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
      adb -s "$s" shell pm grant "$PKG" android.permission.CAMERA >/dev/null 2>&1
      adb -s "$s" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
    done
    say "Waiting for Tor to bootstrap on both (first launch)…"
    waitm "$A" 'Tor: connected' 120 && echo "  alice Tor up" || echo "  alice Tor timeout"
    waitm "$B" 'Tor: connected' 120 && echo "  bob Tor up"   || echo "  bob Tor timeout"
    ;;
  login)
    say "ALICE → logging in to box1 over Tor"
    login "$A" "$ONION1" alice
    say "BOB → logging in to box2 over Tor"
    login "$B" "$ONION2" bob
    say "Waiting for both to reach their chat list (sync over Tor)…"
    waitm "$A" 'text="Chats"' 150 && echo "  alice in" || echo "  alice login timeout"
    waitm "$B" 'text="Chats"' 150 && echo "  bob in"   || echo "  bob login timeout"
    ;;
  connect)
    say "ALICE → shows her code (QR of @alice:onion)"
    tapm "$A" 'content-desc="my code"' || adb -s "$A" shell input tap 1006 214
    waitm "$A" 'text="My code"' 8 || true
    say "BOB → opens the scanner (camera), then connects by address"
    tapm "$B" 'content-desc="new chat"' || adb -s "$B" shell input tap 964 2221
    waitm "$B" 'text="Scan a code"' 6 || true
    tapm "$B" 'text="Scan a code"'; zzz "$B" 5     # show the live scanner
    adb -s "$B" shell input keyevent 4; zzz "$B" 1  # back out of scanner
    tapm "$B" 'content-desc="new chat"' || adb -s "$B" shell input tap 964 2221
    waitm "$B" 'text="Enter address"' 6 || true
    tapm "$B" 'text="Enter address"'; zzz "$B" 1
    # type alice's id into the dialog field
    c=$(findc "$B" 'text="@bob:xxxx') || c="540 1100"; adb -s "$B" shell input tap $c
    adb -s "$B" shell input text "$ALICE_ID"
    hide_ime "$B"
    tapm "$B" 'text="Start chat"'
    say "Waiting for the encrypted DM to open + alice to land in it…"
    waitm "$B" 'content-desc="send"' 40 && echo "  bob in DM"
    ;;
  chat)
    say "BOB → says hi"
    c=$(findc "$B" 'text="Message"') || c="465 2237"; adb -s "$B" shell input tap $c
    adb -s "$B" shell input text "Hi%sAlice%s-%sPurePrivacy%sover%sTor"
    tapm "$B" 'content-desc="send"' || adb -s "$B" shell input tap 991 1444
    hide_ime "$B"
    say "ALICE → opens the chat and replies"
    tapm "$A" 'text="bob &#128149;' || tapm "$A" 'text="bob"'
    waitm "$A" 'content-desc="send"' 20 || true
    c=$(findc "$A" 'text="Message"') || c="465 2237"; adb -s "$A" shell input tap $c
    adb -s "$A" shell input text "Hey%sBob%s—%sno%sservers%sbetween%sus"
    tapm "$A" 'content-desc="send"' || adb -s "$A" shell input tap 991 1444
    hide_ime "$A"
    ;;
  call)
    say "ALICE → starts a call (in the DM)"
    tapm "$A" 'content-desc="call"' || adb -s "$A" shell input tap 1006 213
    say "  …Element Call loading over Tor (~45s)…"; zzz "$A" 48
    say "BOB → joins the same call"
    tapm "$B" 'content-desc="call"' || adb -s "$B" shell input tap 1006 213
    say "  …bob bridging to the focus box + relay over Tor (~50s)…"; zzz "$B" 52
    say "Two-way call should be live (both media via TURN-relay over Tor)."
    ;;
  hangup)
    say "Hanging up both"
    for s in "$A" "$B"; do tapm "$s" 'content-desc="hang up"' || adb -s "$s" shell input tap 801 2244; done
    ;;
  all)
    "$0" login; "$0" connect; "$0" chat; "$0" call
    say "Demo flow done — call is live. Run '$0 hangup' to end."
    ;;
  *) echo "phase '$1' not implemented yet";;
esac
