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
| Dockerfile and compose file | open (A below) |
| Service without Docker | open (B below), alternative to A |

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

## A. Dockerfile and compose (open)

Create `Dockerfile` in the repository root:

```dockerfile
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :engine:jar --console=plain --no-daemon

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 --home /data hless && mkdir /data && chown hless /data
COPY --from=build /src/engine/build/libs/daemon.jar /app/daemon.jar
USER hless
VOLUME /data
EXPOSE 7000
ENTRYPOINT ["java", "--add-modules=jdk.incubator.foreign", "--enable-native-access=ALL-UNNAMED", \
  "-XX:TieredStopAtLevel=1", "-Xms16m", "-Xmx192m", "-XX:MaxDirectMemorySize=64m", \
  "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-Dcs.cloudstream.home=/data", \
  "-cp", "/app/daemon.jar", "com.lagradost.hless.HlessMainKt"]
```

Checks to make, in order:

1. `docker run --rm eclipse-temurin:17-jre java --list-modules | grep jdk.incubator.foreign`
   must print a line. If it prints nothing, use `eclipse-temurin:17-jdk` for
   the second stage too.
2. Add a `.dockerignore` with `.git`, `.gradle`, `**/build`, `docs`.
3. Until 2a exists, test the image with the entrypoint from "Run the engine
   alone" instead (`-jar /app/daemon.jar 17171 <token>`).

Create `docker-compose.yml`:

```yaml
services:
  hless:
    build: .
    restart: unless-stopped
    environment:
      HLESS_REPOS: ""
      HLESS_SECRET: ""
      HLESS_MEDIAFLOW_URL: ""
      HLESS_MEDIAFLOW_PASSWORD: ""
    volumes:
      - hless-data:/data
    mem_limit: 512m
    labels:
      - traefik.enable=true
      - traefik.http.routers.hless.rule=Host(`hless.example.org`)
      - traefik.http.routers.hless.tls=true
      - traefik.http.services.hless.loadbalancer.server.port=7000
volumes:
  hless-data:
```

The Traefik labels are a template: the maintainer's entry point and
certificate resolver names are not known here. Do not publish port 7000 on
the host when Traefik is used.

Done when: `docker compose up --build` starts, and `/healthz` (2c) or
`/health` on the engine port answers from inside the container.

## B. Service without Docker (alternative to A)

For running directly in an LXC container.

1. Install Temurin 17 (Adoptium apt repository, package `temurin-17-jre`).
2. Copy `daemon.jar` to `/opt/hless/daemon.jar`; create user `hless` and
   `/var/lib/hless` owned by it.
3. `/etc/hless.env` holds the `HLESS_*` variables, mode `0600`.
4. `/etc/systemd/system/hless.service`:

```ini
[Unit]
Description=cloudstream-hless
After=network-online.target
Wants=network-online.target

[Service]
User=hless
EnvironmentFile=/etc/hless.env
ExecStart=/usr/bin/java --add-modules=jdk.incubator.foreign --enable-native-access=ALL-UNNAMED -XX:TieredStopAtLevel=1 -Xms16m -Xmx192m -XX:MaxDirectMemorySize=64m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Dcs.cloudstream.home=/var/lib/hless -cp /opt/hless/daemon.jar com.lagradost.hless.HlessMainKt
Restart=on-failure
RestartSec=5
MemoryMax=512M
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/hless
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

Done when: `systemctl enable --now hless` leaves the service `active` and it
survives a reboot.

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
  swapping), not a Linux defect, but that is not proven: see whether it
  passes on the GitHub runner before deciding anything.
