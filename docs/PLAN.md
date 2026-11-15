# Project plan

Goal: one small container on Proxmox that runs CloudStream `.cs3` extensions
and exposes them to Stremio as an addon, behind Traefik, with MediaFlow Proxy
handling streams that need headers. "Works for me" is the bar.

## Ground rules

- **Do not edit `library/`.** Extensions are compiled against its ABI.
- **Edit `engine/` as little as possible.** Upstream keeps fixing extension
  compatibility there; the fewer local changes, the cleaner
  `git fetch upstream && git merge upstream/main` stays. Expect conflicts only
  in files deleted here (client, scripts, docs): resolve by deleting again.
- **New code goes in its own package inside the engine module**:
  `engine/src/main/kotlin/com/lagradost/hless/`. New files never conflict with
  upstream, it ships in the same jar and JVM, and no upstream build file needs
  changing. It talks to the engine over its loopback HTTP API.
- Step-by-step work plans are in [plans/](plans/README.md).
- GPLv3. Do not copy code from projects with incompatible or no-copy terms.

## What the engine already gives us

Verified on Linux (Debian 13, JDK 17) on 2026-11-15; details in
[plans/1-container.md](plans/1-container.md):

- Builds unchanged. Starts in about 2 seconds, about 80 MB idle, about 100 MB
  with one extension loaded.
- Launches without the Mac app: `java … -jar daemon.jar <port> <token>` and
  `-Dcs.cloudstream.home=<data dir>`.
- Extensions load **in-process** when no launch options are sent. The
  per-extension sandboxed workers are opt-in and macOS-specific, so headless
  needs no change there. The container is the sandbox.
- HTTP API on `127.0.0.1` with a bearer token ([ipc-api.md](ipc-api.md)):
  `/repos/sync`, `/repos/consent`, `/plugins/install`, `/providers`,
  `/search`, `/details`, `/links`, `/home`.
- Cloudflare challenges are already an out-of-process job: the engine parks
  the request and waits for cookies (`/solve/pending`, `/solve-callback`).
- Repositories re-sync by themselves every 6 hours.

Real community extensions were checked the same day: 25 of 25 installed and
started, and 9 of 28 providers returned playable links for one test query;
the failures were blocked or unreachable sites, not missing Android support.

## Phases

| Phase | What | Plans |
| :--- | :--- | :--- |
| 0 | Fork and strip | done |
| 1 | Engine runs headless on Linux, packaged as a container or service | [1](plans/1-container.md) |
| 2 | Stremio addon: launcher, bootstrap, HTTP server, title resolution, streams, translated titles, ordering | [2a](plans/2a-launcher-and-engine-client.md) [2b](plans/2b-bootstrap-repos.md) [2c](plans/2c-addon-server.md) [2d](plans/2d-title-resolution.md) [2e](plans/2e-streams.md) [2f](plans/2f-title-aliases.md) [2g](plans/2g-stream-ordering.md) |
| 3 | Only when needed: Cloudflare solver, provider catalogs, compatibility report, anime IDs, web-view extractors | [3a](plans/3a-cloudflare-solver.md) [3b](plans/3b-catalogs.md) [3c](plans/3c-compat-report.md) [3d](plans/3d-anime-kitsu.md) [3e](plans/3e-webview-extractors.md) |
| 4 | Keeping up with upstream automatically | [4a](plans/4a-upstream-sync.md) |

## How a stream request flows (phase 2)

```
Stremio → Traefik → addon server (0.0.0.0:7000)            2c
            parse "tt0944947:1:2"                           2d
            TMDB / Cinemeta: ID → titles, year              2f, 2d
            per provider, in parallel:                      2e
              engine /search → pick match (cached on disk)  2d
              engine /details → pick episode                2d
              engine /links                                 2e
            map links → Stremio streams, wrap in MediaFlow  2e
            rank and filter                                 2g
            answer at the deadline; the rest lands in a
            cache for the next request                      2e
```

## Staying current

```bash
git fetch upstream
```

```bash
git merge upstream/main
```
