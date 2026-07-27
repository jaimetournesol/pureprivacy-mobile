# Third-party software in PurePrivacy

A PurePrivacy box is the PurePrivacy application **plus** several independent programs that it
bundles and runs. Those programs are the property of their authors and are used under their own
licences, reproduced or linked below. Nothing here is claimed as Tournesol's work.

This file ships **inside the installers and the Docker image**, alongside the binaries it
describes, so anyone who receives a box also receives its licence terms.

## Components

| Component | Purpose in the box | Licence | Upstream source |
|---|---|---|---|
| **Element Call** | the in-call user interface, served by your box | **AGPL-3.0-or-later** | https://github.com/element-hq/element-call |
| **lk-jwt-service** | mints call tokens from a Matrix login | **AGPL-3.0-or-later** | https://github.com/element-hq/lk-jwt-service |
| **tuwunel** | the Matrix homeserver — your messages | Apache-2.0 | https://github.com/matrix-construct/tuwunel |
| **LiveKit Server** | routes group-call audio/video | Apache-2.0 | https://github.com/livekit/livekit |
| **Caddy** | TLS termination + reverse proxy | Apache-2.0 | https://github.com/caddyserver/caddy |
| **Tor** | the anonymity network the box lives on | BSD-3-Clause | https://gitlab.torproject.org/tpo/core/tor |
| **coturn** | TURN relay for 1:1 calls | BSD-3-Clause | https://github.com/coturn/coturn |
| **matrix-rust-sdk** | Matrix client + E2EE (phone app) | Apache-2.0 | https://github.com/matrix-org/matrix-rust-sdk |

Full licence texts are in [`licenses/`](licenses/) next to this file.

## Source offer for the AGPL components (§13 / §6)

**Element Call** and **lk-jwt-service** are licensed under the **GNU Affero General Public
License v3**. Your box *runs* Element Call and serves it to you over the network, which is
exactly the situation AGPL §13 addresses: you are entitled to its complete corresponding
source.

**We distribute these programs unmodified.** The exact versions a box ships are pinned in
[`scripts/stage-sidecars.sh`](scripts/stage-sidecars.sh) (`EC_VER`) and
[`scripts/fetch-sidecars.sh`](scripts/fetch-sidecars.sh) (`LKJWT_IMAGE`), and the corresponding
source for each is the upstream release of that exact version:

- Element Call — https://github.com/element-hq/element-call/releases (tag matching `EC_VER`)
- lk-jwt-service — https://github.com/element-hq/lk-jwt-service/releases

If we ever modify either, the modified source will be published in this repository and linked
here **before** the change ships. Should you prefer to receive the corresponding source
directly, or have trouble obtaining it upstream, write to **jaime.melon@tournesol.ai** and we
will provide it at no charge, by a means of your choosing, for as long as we distribute the
binary.

Nothing in PurePrivacy's own licence restricts your rights under the AGPL for these components.

## Why this matters here

PurePrivacy asks you to trust software that handles your private messages. That trust depends
on the software being inspectable — which is precisely what these licences guarantee. Honouring
them is the same commitment we make to you, kept toward the people whose work we build on.
