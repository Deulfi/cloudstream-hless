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
- **New code goes in a new Gradle module** (`stremio/`).
- GPLv3. Do not copy code from projects with incompatible or no-copy terms.

## What the engine already gives us

Read from the source, not yet run in this fork:

- Launch without the Mac app: `CS_DAEMON_PORT`, `CS_DAEMON_TOKEN` (or
  `main(port, token)` arguments) and `-Dcs.cloudstream.home=<data dir>`.
- Extensions load **in-process by default** when no launch options are sent.
  The per-extension sandboxed workers are opt-in (`isolation: "worker"`) and
  macOS-specific, so headless needs no change there. The container is the
  sandbox.
- HTTP API on `127.0.0.1` with a bearer token ([ipc-api.md](ipc-api.md)):
  `/repos/sync`, `/repos/consent`, `/plugins/install`, `/search`, `/details`,
  `/links`, `/home`, `/extract`.
- Cloudflare challenges are already an out-of-process job: the engine parks the
  request and waits for cookies on `/solve/*`.
- JVM flags the Mac app used, a sane starting point:
  `--add-modules=jdk.incubator.foreign --enable-native-access=ALL-UNNAMED
  -XX:TieredStopAtLevel=1 -Xms16m -Xmx192m -XX:MaxDirectMemorySize=64m
  -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError`
- `jlink` modules for a small runtime image: `java.base, java.logging,
  java.naming, java.net.http, java.sql, java.xml, java.desktop, jdk.unsupported,
  jdk.crypto.ec, jdk.zipfs, jdk.charsets, java.management, jdk.management,
  jdk.incubator.foreign`

## Phases

### 0. Fork and strip — done

Client, player, downloader, packaging, macOS scripts and client docs removed.
CI reduced to the engine job on Linux.

### 1. Engine runs headless in a container

- [ ] `./gradlew :engine:test :engine:jar` passes on Linux (CI will tell; some
      tests may assume macOS and need excluding).
- [ ] `Dockerfile`: build stage with JDK 17, runtime stage with a `jlink` image
      and `daemon.jar`; data dir as a volume.
- [ ] Start it, add one repository, install one extension, and get a result
      from `/search`, `/details` and `/links` with `curl`.
- [ ] Note real idle memory with a handful of extensions loaded.

Known unknowns: `SystemTrust` and `FullFsync` are Mac-flavoured and may need a
no-op path on Linux.

### 2. Stremio addon

New module `stremio/`, same JVM as the engine. Simplest wiring that leaves
upstream untouched: its `main` starts the engine's `main(port, token)` on a
thread with a random token, then talks to it over loopback HTTP and serves the
addon on `0.0.0.0`.

- [ ] `/manifest.json`, `/stream/{type}/{id}.json` first; catalogs and meta
      later.
- [ ] ID mapping: IMDb ID → title, year, season/episode (Cinemeta) → search
      enabled providers in parallel → match on title and year → `/details` →
      pick the episode → `/links`.
- [ ] Hard deadline per stream request (Stremio gives up after a few seconds
      of nothing): return what resolved in time, keep resolving into a cache
      for the retry.
- [ ] Cache ID → provider URL matches on disk; they rarely change.
- [ ] Streams needing headers become MediaFlow Proxy URLs; plain ones pass
      through.
- [ ] Config from environment: MediaFlow URL and password, public base URL,
      repository list, enabled providers, an addon secret in the path.
- [ ] `docker-compose.yml` with Traefik labels.

### 3. Later, only when something needs it

- Cloudflare: a small loop that takes the engine's pending solves, asks
  FlareSolverr or Byparr, and posts the cookies back. Engine, solver and
  MediaFlow must share an egress IP, and the engine must use the solver's
  user agent.
- WebView-based extractors (extensions that load a page and sniff its network
  requests): needs a real headless browser with request interception. Skip
  until an extension that matters depends on it.
- Provider catalogs in Stremio (`/home` → catalog) with a custom ID prefix.
- Adapt `scripts/catalog_compat_run.py` (currently expects the packaged Mac
  app) to run against the container and report which extensions work.
- Repository auto-update is already in the engine (`BackgroundRefresh`).

## Staying current

```bash
git fetch upstream
```

```bash
git merge upstream/main
```
