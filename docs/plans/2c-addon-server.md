# 2c. Stremio addon HTTP server

Read [README.md](README.md) first. Depends on: 2a. 2d and 2e plug into it.

## Goal

A second HTTP listener, reachable from the network, that speaks the Stremio
addon protocol.

## File to create

`engine/src/main/kotlin/com/lagradost/hless/AddonServer.kt` with
`fun startAddonServer(streams: StreamService)`. `StreamService` comes from 2e;
until it exists, use an interface with one method
`suspend fun streams(type: String, id: String): List<StremioStream>` and a
stub that returns an empty list.

## Server

Ktor is already a dependency. Use
`embeddedServer(Netty, host = "0.0.0.0", port = Config.port)` with
`ContentNegotiation { json(Json { encodeDefaults = false; explicitNulls = false }) }`.

Every response, including errors, must carry:

- `Access-Control-Allow-Origin: *`
- `Access-Control-Allow-Headers: *`

Answer `OPTIONS` on any path with 204 and those headers. Without them the
Stremio web and TV clients refuse the addon.

## Routes

`P` is `""` when `Config.secret` is unset, otherwise `"/<secret>"`. When a
secret is set, any path not starting with `P` returns 404 with an empty body.

| Route | Response |
| :--- | :--- |
| `GET P/manifest.json` | The manifest below |
| `GET P/stream/{type}/{id}.json` | `{"streams": [...]}` from `streams.streams(type, id)` |
| `GET /healthz` | `200 ok` (no secret; for the container health check) |
| anything else | 404 |

Route details:

- `{id}` arrives URL-encoded and with the `.json` suffix; strip the suffix and
  decode. Examples: `tt0111161`, `tt0944947:1:2` (series: IMDb ID, season,
  episode).
- `type` other than `movie` or `series`: return `{"streams": []}`.
- An exception inside `streams.streams` is logged and answered with
  `{"streams": []}` and status 200. Stremio treats non-200 as a broken addon.
- Add `Cache-Control: max-age=60` to stream responses that contain at least
  one stream, and `no-store` to empty ones, so a retry asks again.

## Manifest

```json
{
  "id": "community.cloudstream.hless",
  "version": "0.1.0",
  "name": "CloudStream hless",
  "description": "Streams resolved by CloudStream extensions on your own server.",
  "resources": ["stream"],
  "types": ["movie", "series"],
  "idPrefixes": ["tt"],
  "catalogs": [],
  "behaviorHints": { "configurable": false }
}
```

Take `version` from a constant in `AddonServer.kt`. Catalogs are added in 3b.

## Data classes

In `engine/src/main/kotlin/com/lagradost/hless/StremioModels.kt`, all
`@Serializable`:

```kotlin
data class StremioStream(
    val name: String,
    val description: String,
    val url: String,
    val behaviorHints: StreamHints? = null,
    val subtitles: List<StremioSubtitle>? = null,
)
data class StreamHints(
    val notWebReady: Boolean? = null,
    val bingeGroup: String? = null,
    val proxyHeaders: ProxyHeaders? = null,
)
data class ProxyHeaders(val request: Map<String, String>)
data class StremioSubtitle(val id: String, val url: String, val lang: String)
data class StreamsResponse(val streams: List<StremioStream>)
```

## Tests

`engine/src/test/kotlin/com/lagradost/hless/AddonServerTest.kt` using Ktor's
`testApplication`. Factor the routing into
`fun Application.addonModule(streams: StreamService, secret: String?)` so the
test can install it. Cases:

- manifest has `resources == ["stream"]` and the CORS header;
- `/stream/series/tt0944947%3A1%3A2.json` calls the service with
  `("series", "tt0944947:1:2")`;
- with a secret, `/manifest.json` is 404 and `/<secret>/manifest.json` is 200;
- a throwing service yields 200 and `{"streams":[]}`.

If `ktor-server-test-host` is not on the test classpath, add
`testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")` to
`engine/build.gradle.kts`. That is one of the two permitted edits to that file
(see README).

## Done when

- Tests pass.
- From another machine, `curl http://<container>:7000/manifest.json` returns
  the manifest.
- Stremio accepts `http://<container>:7000/manifest.json` as an addon (it
  installs and shows no error; no streams yet).
