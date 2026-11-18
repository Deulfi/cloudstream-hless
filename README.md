# cloudstream-hless

A headless CloudStream extension host that serves as a Stremio addon ("Hless").
It runs community `.cs3` extensions on a plain JVM in a container and answers
Stremio's catalog, meta and stream requests from them: it searches the installed
providers for the title, extracts the links, probes them, ranks them and returns
the result. Meant for a small home server (about 200 MB of RAM).

This is a "works for me" project with minimal maintenance. Issues may go
unanswered; pull requests and forks are welcome.

## Run

CI builds the image from the [Dockerfile](Dockerfile). Mount `/data` (extensions,
caches, state) and set at least `HLESS_REPOS`:

```bash
docker run -d --name hless -p 7000:7000 -v hless-data:/data \
  -e HLESS_REPOS=https://example.org/repo.json \
  -e HLESS_PUBLIC_URL=https://hless.example.org \
  -e HLESS_SECRET=some-long-random-string \
  -e HLESS_LANGS=de,en \
  ghcr.io/<owner>/cloudstream-hless
```

Without Docker: `./gradlew :engine:jar`, then run it as in the Dockerfile
(`java --add-modules=jdk.incubator.foreign ... -Dcs.cloudstream.home=<dir> -cp daemon.jar com.lagradost.hless.HlessMainKt`). Needs JDK 17.

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
| `HLESS_MIN_WAIT_MS` / `HLESS_QUIET_MS` | `4000` / `2000` | Earliest answer, and how long things must be quiet to answer early. |
| `HLESS_TMDB_KEY` | none | TMDB API key, for titles in other languages (the search then also uses them). |
| `HLESS_ANIME_AUDIO` | `sub` | Preferred audio variant for Kitsu (anime) ids. |
| `HLESS_MEDIAFLOW_URL` / `HLESS_MEDIAFLOW_PASSWORD` | none | A [MediaFlow Proxy](https://github.com/mhdzumair/mediaflow-proxy); lets streams that need request headers play in more players. |
| `HLESS_SOLVER_URL` | none | A FlareSolverr-compatible service, for sites behind a Cloudflare challenge. Use its IP address if the container's resolver does not know its name. |
| `HLESS_SOLVER_TIMEOUT_MS` | `60000` | How long one solve may take. |
| `HLESS_SOLVER_COOLDOWN_MS` | `600000` | Pause for a site the solver could not pass. |
| `HLESS_SOLVER_CONCURRENCY` | `0` | Parallel solves (`0` is the solver's own limit). |

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
