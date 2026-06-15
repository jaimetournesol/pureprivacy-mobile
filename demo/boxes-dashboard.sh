#!/usr/bin/env bash
# PurePrivacy — live appliance-box dashboard (for the demo screen-recording).
# Headless Docker "boxes" rendered as a visual TUI. Ctrl-C to quit.
Y='\033[38;5;220m'; G='\033[38;5;46m'; R='\033[38;5;203m'; D='\033[38;5;245m'; W='\033[97m'; B='\033[1m'; X='\033[0m'
CY='\033[38;5;208m'
svc(){ # name container label
  local st; st=$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)
  if [ "$st" = "true" ]; then printf "   ${G}●${X} %-26s ${G}up${X}\n" "$2"
  else printf "   ${R}○${X} %-26s ${R}down${X}\n" "$2"; fi
}
panel(){ # box userlabel
  local box="$1" who="$2" onion
  onion=$(cat /tmp/boxes/${box}.onion 2>/dev/null || echo "(not provisioned)")
  printf "${B}${Y} ╭───────────────────────────────────────────────╮${X}\n"
  printf "${B}${Y} │${X} ${B}${W}%-12s${X} ${D}appliance box${X}            ${Y}✿${X}      ${B}${Y}│${X}\n" "$who"
  printf "${B}${Y} ╰───────────────────────────────────────────────╯${X}\n"
  printf "   ${D}onion${X}  ${CY}%s${X}\n" "${onion:0:42}"
  printf "          ${CY}%s${X}\n" "${onion:42}"
  echo
  svc "${box}-tor"   "Tor hidden service"
  svc "${box}-hs"    "Matrix homeserver (tuwunel)"
  svc "${box}-turn"  "coturn — media relay"
  svc "${box}-lk"    "LiveKit — group-call SFU"
  svc "${box}-lkjwt" "lk-jwt — call auth"
  svc "${box}-caddy" "Caddy — onion TLS gateway"
  echo
}
while true; do
  clear
  printf "${B}${Y}   ✿  P U R E P R I V A C Y${X}   ${D}— self-hosted appliance boxes (all traffic over Tor)${X}\n"
  printf "   ${D}%s${X}\n\n" "$(date '+%A %H:%M:%S')"
  panel box1 "ALICE"
  panel box2 "BOB"
  printf "   ${D}no servers between them — each owns their box; messages & calls federate box→box over Tor${X}\n"
  sleep 2
done
