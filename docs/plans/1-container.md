# 1. Engine runs headless on Linux

Read [README.md](README.md) first.

## State

| Step | State |
| :--- | :--- |
| Build `daemon.jar` on Linux | **done**, verified 2026-11-15 |
| Run the engine alone, API answers | **done**, verified 2026-11-15 |
| An extension loads in-process on Linux | **done** with the repo's test extension |
| A real extension searches and resolves links | open: needs a repository URL from the maintainer |
| Engine test suite on Linux | **done**: 490 run, 474 passed, 15 skipped, 1 failed (see Findings) |
| Image built by CI and pushed to GHCR | in the repository (A below) |
| LXC created from the image on Proxmox | open (A below), maintainer |

## Verified facts

Measured on Debian 13, Temurin JDK 17.0.21, 2 cores, 1 GB RAM.

- `./gradlew :engine:jar` succeeds unchanged. It took 8.5 minutes with Gradle
  capped at 640 MB heap; give a build machine 2 GB or more.
- The engine starts in about 2 seconds and uses about 80 MB resident memory
  idle, about 100 MB with one extension loaded.
- With no launch options it runs extensions in-process
  (`"isolation":{"mode":"in-process"}` in `/health`).
- It binds `127.0.0.1` only, answers 401 without the token and 403 when the
  `Host` header is not a loopback name.
- It logs `storage.fullfsync_unavailable … not macOS` once at start. That is
  harmless.
- `java -jar daemon.jar --self-test` fails on Linux
  (`Sandbox is required by policy but macOS sandbox-exec is not available`).
  It tests the macOS worker sandbox; ignore it.
- Debian 13 has no JDK 17 package. Use Temurin from Adoptium's apt
  repository, or a Temurin container image.

## Run the engine alone

This is the reference command. Token: any string of 8 or more characters.

```bash
java --add-modules=jdk.incubator.foreign --enable-native-access=ALL-UNNAMED \
  -XX:TieredStopAtLevel=1 -Xms16m -Xmx192m -XX:MaxDirectMemorySize=64m \
  -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError \
  -Dcs.cloudstream.home=/data \
  -jar daemon.jar 17171 <token> </dev/null
```

Arguments: port, then token. `/data` receives `cache/`, `logs/`, `state/` and
`daemon.lock`; it must persist across restarts.

Probe it:

```bash
curl -s http://127.0.0.1:17171/health
```

```bash
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:17171/providers
```

Once phase 2a exists, the start command changes only at the end:
`-cp daemon.jar com.lagradost.hless.HlessMainKt` instead of
`-jar daemon.jar 17171 <token>`.

Warning for scripts: `pkill -f daemon.jar` also kills the shell that runs it,
because the pattern matches its own command line. Use `pkill -x java`.

## Check a real extension by hand

Needs a repository URL (a `repo.json` address) from the maintainer. `T` is the
token, `R` the repository URL.

```bash
curl -s -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d "{\"repoUrl\":\"$R\",\"allow\":true}" http://127.0.0.1:17171/repos/consent
```

```bash
curl -s -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d "{\"repoUrl\":\"$R\",\"installAll\":true}" http://127.0.0.1:17171/repos/sync
```

```bash
curl -s -H "Authorization: Bearer $T" http://127.0.0.1:17171/plugins
```

```bash
curl -s -H "Authorization: Bearer $T" 'http://127.0.0.1:17171/search?q=inception'
```

If consent is refused because the repository is unknown, run the sync first,
then consent, then sync again. From a search result take `providerId` and
`url` for `/details`, then `movieData` (or an episode's `data`) for
`POST /links`; shapes are in [2a](2a-launcher-and-engine-client.md).

Expected: `/plugins` lists extensions with `"state":"running"`. Extensions in
state `failed` carry the reason in `lastError`; write those down, do not fix
them here.

## A. Image from CI, installed as an OCI LXC

Decided 2026-11-15: GitHub Actions builds the image and pushes it to GHCR;
Proxmox runs it as an LXC created from that OCI image. No Docker on the host,
no compose file.

Already in the repository:

- `Dockerfile`: runtime only. It copies `engine/build/libs/daemon.jar`, which
  must be built first (`./gradlew :engine:jar`). It runs as root on purpose:
  an unprivileged LXC already maps root to an unprivileged host user, and a
  second user only complicates the `/data` mount.
- `.github/workflows/ci.yml`, job `image`: builds the jar and the image,
  checks the runtime has `jdk.incubator.foreign`, starts the engine inside
  the image and waits for `/health` to report `ready`, then pushes
  `ghcr.io/<owner>/<repo>:latest` and `:<commit>` on pushes to `main`.

The image's default command is `com.lagradost.hless.HlessMainKt` (plan 2a).
Until 2a is merged the image only runs with the command overridden to
`com.lagradost.macos.MainKt <port> <token>`, which is what CI does.

Open steps, for the maintainer on Proxmox (not verified here):

1. GHCR packages of a private repository are private. Either set the package
   to public in its GitHub settings, or pull with a token that has
   `read:packages`. Check whether your Proxmox version's "pull from OCI
   registry" dialog accepts credentials; if not, make the package public or
   pull with `skopeo` into the template storage.
2. Create the LXC from the image. Unprivileged, 1 core, 512 MB RAM.
3. Add a mount point at `/data` (1 GB is plenty). It must survive
   re-creating the container from a newer image.
4. Set the `HLESS_*` environment variables in the container's options
   (tables in [2a](2a-launcher-and-engine-client.md) and
   [3a](3a-cloudflare-solver.md)).
5. Point Traefik at port 7000 of the container.
6. Updating: pull the new image and re-create the container with the same
   mount point and environment.

Done when: the container starts from the GHCR image and `/healthz` (plan 2c)
answers through Traefik.

## Tests

`./gradlew :engine:test` is the upstream suite. It needs about 8 minutes on
the 1 GB test container. Do not edit upstream tests. If one fails, add it to
"Findings" with the assertion that failed; exclude it in CI only after the
maintainer agrees.

## Findings

- 2026-11-15, Debian 13, 1 GB RAM: `StateCrashConsistencyTest > killing a
  writer mid-commit leaves a loadable, monotonic state` failed at its last
  line, `assertTrue(lastGeneration > 0)`. The test starts a child JVM 20
  times and kills it after 20–250 ms; that assertion only says the child
  never finished a write before being killed. Every consistency assertion
  inside the loop passed. Most likely the container was too slow (it was
  swapping), not a Linux defect. Confirmed the same day: the whole suite,
  this test included, passed on the GitHub runner. Nothing to do.
