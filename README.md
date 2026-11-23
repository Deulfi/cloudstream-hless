# cloudstream-hless

A headless CloudStream extension host that serves as a Stremio addon ("Hless").
It runs community `.cs3` extensions on a plain JVM in a container and answers
Stremio's catalog, meta and stream requests from them: it searches the installed
providers for the title, extracts the links, probes them, ranks them and returns
the result. Meant for a small home server (the container needs about 500 MB of RAM with 100 providers loaded; the heap is sized to 60 % of the container's memory at start, so give it more RAM for more providers).

This is a "works for me" project with minimal maintenance. Issues may go
unanswered; pull requests and forks are welcome.

## Run

### With Docker

CI builds the image from the [Dockerfile](Dockerfile). Mount `/data` (extensions,
caches, state) and set at least `HLESS_REPOS`:

```bash
docker run -d --name hless --restart unless-stopped -p 7000:7000 -v hless-data:/data \
  -e HLESS_REPOS=https://example.org/repo.json \
  -e HLESS_PUBLIC_URL=https://hless.example.org \
  -e HLESS_SECRET=some-long-random-string \
  -e HLESS_LANGS=de,en \
  ghcr.io/deulfi/cloudstream-hless:latest
```

### Without Docker (Debian 12)

Builds the jar from source and runs it as a systemd service under its own user.
It needs JDK **17** exactly: the engine uses `jdk.incubator.foreign`, which newer
JDKs no longer have (Debian 13 only ships 21 and later; install Temurin 17 there
instead). The first build downloads Gradle and the dependencies and takes a few minutes.

```bash
sudo apt-get update
sudo apt-get install -y git openjdk-17-jdk-headless

# build
git clone --depth 1 https://github.com/Deulfi/cloudstream-hless.git ~/hless-src
cd ~/hless-src
./gradlew :engine:jar

# install
sudo useradd --system --home-dir /var/lib/hless --create-home hless
sudo install -d /opt/hless
sudo install -m 644 engine/build/libs/daemon.jar /opt/hless/daemon.jar
sudo install -m 755 scripts/hless-entrypoint.sh /opt/hless/hless-entrypoint.sh

# settings: edit this file later, then `sudo systemctl restart hless`
sudo tee /etc/hless.env >/dev/null <<'ENV'
HLESS_DATA=/var/lib/hless
HLESS_JAR=/opt/hless/daemon.jar
# On a bare machine the launcher would take 60 % of all RAM; set the heap yourself.
HLESS_HEAP_MB=768
HLESS_REPOS=https://example.org/repo.json
HLESS_LANGS=en
#HLESS_SECRET=some-long-random-string
#HLESS_PUBLIC_URL=https://hless.example.org
ENV
sudo chgrp hless /etc/hless.env && sudo chmod 640 /etc/hless.env

sudo tee /etc/systemd/system/hless.service >/dev/null <<'UNIT'
[Unit]
Description=Hless Stremio addon
After=network-online.target
Wants=network-online.target

[Service]
User=hless
EnvironmentFile=/etc/hless.env
ExecStart=/bin/sh /opt/hless/hless-entrypoint.sh com.lagradost.hless.HlessMainKt
Restart=on-failure

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable --now hless
journalctl -u hless -f   # wait for "engine ready", then open http://<host>:7000/
```

To update: `cd ~/hless-src && git pull && ./gradlew :engine:jar`, copy the jar
again as above, then `sudo systemctl restart hless`.

### Configure

Open `http://<host>:7000/` (with `/<secret>/` in front when a secret is set). That
is the configuration page: it shows the addon link to install in Stremio, and lets
you set language, resolution range, provider order and on/off, hosters to hide and
sorting. Those choices are stored in the addon link itself, not on the server.

No sources, streams or providers are included; you bring the repository URLs. See
[DISCLAIMER.md](DISCLAIMER.md).

## Settings (environment variables)

All optional except for the repositories. They are the server's defaults; the
configuration page overrides them per installed link.

| Variable | Default | Meaning |
| :--- | :--- | :--- |
| `HLESS_REPOS` | none | Comma-separated extension repository URLs (`repo.json`). Installed at start. |
| `HLESS_PLUGINS` | `*` | Which plugins of those repositories to install (`*` or a list of internal names). |
| `HLESS_PORT` | `7000` | Addon port. |
| `HLESS_ENGINE_PORT` | `17171` | The engine's own port, bound to 127.0.0.1 only. |
| `HLESS_SECRET` | none | Path prefix for every address (`/<secret>/...`). Set it when the addon is reachable from the internet. |
| `HLESS_PUBLIC_URL` | from request headers | Public base address, used in the manifest and for the icon. |
| `HLESS_LANGS` | `en` | Preferred audio languages, e.g. `de,en`; others are sorted last. |
| `HLESS_MIN_QUALITY` / `HLESS_MAX_QUALITY` | `0` / `0` | Resolution range in pixels of height; `0` is no limit. Unknown resolutions are never hidden. |
| `HLESS_HEAP_MB` / `HLESS_HEAP_PERCENT` | auto / `60` | JVM heap: a fixed size in MB, or the share of the container's memory (read from `/proc/meminfo`, at least 192 MB). Used by the image's launcher. |
| `HLESS_MAX_PER_PROVIDER` | `6` | Streams kept per provider. |
| `HLESS_MAX_STREAMS` | `40` | Streams kept in total. |
| `HLESS_PROVIDERS_ALLOW` / `_DENY` / `_FIRST` | none | Providers to use only / never / list first (names). |
| `HLESS_SORT` | `lang` | `lang`, `quality`, `provider` or `size`. |
| `HLESS_VARIANTS` | `fold` | `fold` shows one row per stream, `all` lists every HLS variant. |
| `HLESS_LABEL` | `大 Hless` | First line of every stream row (max 20 characters). |
| `HLESS_FLAGS` | `emoji` | Language flags: `emoji`, `code` or `none`. |
| `HLESS_ICONS` | `true` | Emoji icons in the rows. |
| `HLESS_HINT` | `true` | Add a "still loading" / "hidden by filter" note row to partial answers. |
| `HLESS_DEADLINE_MS` | `15000` | Longest wait before answering Stremio. |
| `HLESS_EMPTY_WAIT_MS` | `10000` | When nothing at all is ready at the deadline, keep waiting this much longer for the first link. |
| `HLESS_MIN_WAIT_MS` / `HLESS_QUIET_MS` | `4000` / `2000` | Earliest answer, and how long things must be quiet to answer early. |
| `HLESS_TMDB_KEY` | none | TMDB API key, for titles in other languages (the search then also uses them). |
| `HLESS_ANIME_AUDIO` | `sub` | Preferred audio variant for Kitsu (anime) ids. |
| `HLESS_MEDIAFLOW_URL` / `HLESS_MEDIAFLOW_PASSWORD` | none | A [MediaFlow Proxy](https://github.com/mhdzumair/mediaflow-proxy); lets streams that need request headers play in more players. The URL goes into every stream link, so it must be the address the **player** reaches (e.g. the reverse-proxy name over https), not an internal container IP. |
| `HLESS_SOLVER_URL` | none | A FlareSolverr-compatible service, for sites behind a Cloudflare challenge. Use its IP address if the container's resolver does not know its name. |
| `HLESS_SOLVER_TIMEOUT_MS` | `60000` | How long one solve may take. |
| `HLESS_SOLVER_COOLDOWN_MS` | `600000` | Pause for a site the solver could not pass. |
| `HLESS_SOLVER_CONCURRENCY` | `0` | Parallel solves (`0` is the solver's own limit). |
| `HLESS_MEDIAFLOW_ENCRYPT` | `true` | Hand out MediaFlow's encrypted, expiring URLs (`/generate_url`, valid 12 h) instead of ones with the password in them. |
| `HLESS_SHARED` | `false` | Several people use the server: the configuration page then shows no search statistics. See below. |
| `HLESS_RATE_LIMIT` | `0`, shared: `30` | Stream requests per minute per address (catalogs and meta: ten times that); `0` is no limit. Behind a reverse proxy the address it adds to `X-Forwarded-For` counts. |

## Hosting for several people

Hless was written for one household, but every preference lives in the person's
own addon link, not on the server. For a server that others use too:

- Set `HLESS_SHARED=true`. Otherwise the configuration page shows everyone how
  often each provider found something and how often each hoster came up, which
  says something about what the others watched.
- Set `HLESS_SECRET` and hand out the link with it. Anyone with the link can use
  the server and make it search every provider.
- Stream URLs through the server's MediaFlow are encrypted, so users never see
  its password. If MediaFlow cannot make one, a shared server hands the stream
  out without the proxy rather than with the password. Users still stream through
  your MediaFlow, with your bandwidth; leave `HLESS_MEDIAFLOW_URL` unset if that
  is not what you want. People can enter their own MediaFlow on the configuration page.
- Each address gets 30 stream requests a minute (`HLESS_RATE_LIMIT`); over that,
  the list shows a "busy" row.
- Shared on purpose: title matches and provider statistics (one person's search
  makes the next one's faster), and a limit of 150 provider searches running at
  once, for everybody together.
- Memory grows with the providers, not the people: the work per title is shared.

## How it works, briefly

- Stremio asks `/stream/<type>/<imdb id>.json`. Every installed provider searches
  for the title; the title match is cached, and so is the work per title, so a
  second open is instant.
- Providers that found something recently are asked first; ones that never do are
  asked last and never waited for. Sites the solver cannot pass are skipped for a while.
- Links are probed (resolution, size, bitrate); HLS variants are folded into one row.
- The answer goes out when the providers that matter are done, or at the deadline.
  A partial answer ends with a note row; the rest is ready when the title is opened again.
- Some providers are flaky (the site throttles, a hoster changes). Reopening the
  title is the first thing to try.

## Origin and licence

This is a fork of [CloudStream for macOS](https://github.com/krishnasinghrao/cloudstream-macos)
with the macOS client, player, downloader and packaging removed. What remains is
its Kotlin engine (`engine/`), the CloudStream extension library (`library/`) and
the addon in `engine/src/main/kotlin/com/lagradost/hless/`. The full upstream
history is kept in this repository.

Licensed under GPLv3, like upstream; see [LICENSE](LICENSE).

## Layout

| Path | What it is |
| :--- | :--- |
| `library/` | CloudStream extension API the plugins link against (upstream, keep unmodified) |
| `engine/` | Headless daemon: Android stubs, `.cs3` loading, repositories, search/details/links over HTTP |
| `engine/src/main/kotlin/com/lagradost/hless/` | The Stremio addon, ranking and configuration page |
| `docs/ipc-api.md` | The engine's HTTP contract |

## Build and test

Needs JDK 17.

```bash
./gradlew :engine:test --tests 'com.lagradost.hless.*' :engine:jar
```

The result is `engine/build/libs/daemon.jar`.
