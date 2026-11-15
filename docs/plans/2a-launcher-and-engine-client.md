# 2a. Launcher and engine client

Read [README.md](README.md) first. Depends on: phase 1 (engine runs on Linux).

## Goal

One JVM process. Our `main` starts the upstream engine on a loopback port,
waits until it is healthy, and gives the rest of our code a small typed client
for the engine's HTTP API.

## Files to create

All under `engine/src/main/kotlin/com/lagradost/hless/`:

| File | Contents |
| :--- | :--- |
| `Config.kt` | `object Config` reading environment variables (table below) |
| `HlessMain.kt` | `fun main()` |
| `EngineClient.kt` | `class EngineClient(baseUrl, token)` |

Do not edit any file outside `com/lagradost/hless/`.

## Config

`object Config` with one `val` per row, read once from `System.getenv`.

| Variable | Type | Default | Meaning |
| :--- | :--- | :--- | :--- |
| `HLESS_PORT` | Int | `7000` | Port the Stremio addon listens on (all interfaces) |
| `HLESS_ENGINE_PORT` | Int | `17171` | Loopback port for the engine |
| `HLESS_SECRET` | String? | none | If set, every addon URL starts with `/<secret>` |
| `HLESS_PUBLIC_URL` | String? | none | Public base URL, e.g. `https://hless.example.org` (used in logs only) |
| `HLESS_REPOS` | list, comma-separated | empty | Repository URLs to add at start (2b) |
| `HLESS_PLUGINS` | list, comma-separated | `*` | Extension internal names to install; `*` means all (2b) |
| `HLESS_DEADLINE_MS` | Long | `15000` | Time budget for one stream request (2e) |
| `HLESS_MEDIAFLOW_URL` | String? | none | Base URL of MediaFlow Proxy, no trailing slash (2e) |
| `HLESS_MEDIAFLOW_PASSWORD` | String? | none | MediaFlow `api_password` (2e) |

Rules: trim values; an empty string counts as not set; strip one trailing `/`
from URLs; a non-numeric number is a startup error with a clear message.

## HlessMain

Steps, in order:

1. Generate a token: 32 random bytes from `java.security.SecureRandom`, hex
   encoded (64 characters).
2. Start the engine on a daemon thread named `engine`:
   `com.lagradost.macos.main(arrayOf(Config.enginePort.toString(), token))`.
   The engine reads its data directory from the JVM property
   `cs.cloudstream.home`; that is set on the command line (phase 1), not here.
3. Create `EngineClient("http://127.0.0.1:${Config.enginePort}", token)`.
4. Call `client.awaitReady(timeoutMs = 120_000)`. On timeout: print the error
   and `exitProcess(1)`.
5. Run bootstrap (2b), then start the addon server (2c) and block.

Do not set `CS_DAEMON_TOKEN` or `CS_DAEMON_OPTIONS` in the environment. With no
options the engine loads extensions in-process, which is what we want.

## EngineClient

Use OkHttp (`okhttp3.OkHttpClient`, already a dependency) and
`kotlinx.serialization.json.Json { ignoreUnknownKeys = true }`. Reuse the
engine's DTO classes from `com.lagradost.macos.models` for decoding; do not
copy them.

Every request except `/health` sends `Authorization: Bearer <token>`. Always
use the host `127.0.0.1` in the URL: the engine answers 403 to any other
`Host`.

All methods are `suspend` and run the blocking call on `Dispatchers.IO`.
Each takes a `timeoutMs` and applies it as the OkHttp call timeout.

| Method | Request | Returns |
| :--- | :--- | :--- |
| `health()` | `GET /health` | `HealthDto` |
| `awaitReady(timeoutMs)` | polls `health()` every 500 ms | returns when `extensions.phase` is `ready` or `safe_mode`; throws on timeout |
| `providers()` | `GET /providers` | list of provider DTOs (use `providerId`, `name`, `tvTypes`) |
| `search(providerId, query, timeoutMs)` | `GET /search?q=<query>&plugins=<providerId>` | `List<SearchResultDto>` |
| `details(providerId, url, timeoutMs)` | `GET /details?plugin=<providerId>&url=<url>` | `DetailsDto` |
| `links(providerId, data, budgetMs)` | `POST /links` body `{"plugin","data","budgetMs"}` | `LinksResponseDto` |
| `repos()` | `GET /repos` | list of repository DTOs |
| `syncRepo(url, installAll)` | `POST /repos/sync` body `{"repoUrl","installAll"}` | repository DTO |
| `consent(url)` | `POST /repos/consent` body `{"repoUrl","allow":true}` | nothing |
| `repoPlugins(url)` | `GET /repos/plugins?repoUrl=<url>` | `List<RepoPluginDto>` |
| `install(repoUrl, internalName)` | `POST /plugins/install` body `{"repoUrl","internalName"}` | `PluginDto` |
| `plugins()` | `GET /plugins` | `List<PluginDto>` |

Details:

- Build query strings with `HttpUrl.Builder.addQueryParameter`; never
  concatenate.
- `budgetMs` for `/links` must be between 1000 and 45000; clamp it.
- A non-2xx response throws `EngineException(status, code, message)`. Read
  `code` from the JSON body: `body.failure.code` if present, else `body.code`,
  else null. Read `message` from `body.error`.
- HTTP 429 means the engine's rate limiter refused: wait 250 ms and retry, at
  most 3 times, before throwing.

## Tests

`engine/src/test/kotlin/com/lagradost/hless/ConfigTest.kt`: parsing of lists,
defaults, trailing slash. Make `Config` testable by putting the parsing in a
`class ConfigValues(env: Map<String, String>)` and letting `object Config`
delegate to `ConfigValues(System.getenv())`.

`EngineClient` is tested against the real engine in 2b's manual check.

## Done when

- `./gradlew :engine:test --tests 'com.lagradost.hless.*'` passes.
- Started with
  `java <flags from phase 1> -cp daemon.jar com.lagradost.hless.HlessMainKt`,
  the process logs that the engine is ready and stays up.
