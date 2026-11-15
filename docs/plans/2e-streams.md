# 2e. Streams (links → Stremio, deadline, MediaFlow)

Read [README.md](README.md) first. Depends on: 2a, 2c, 2d.

## Goal

Implement `StreamService.streams(type, id)`: ask every provider in parallel,
answer within the deadline with whatever is ready, and keep the rest for the
next request.

## Files to create

Under `engine/src/main/kotlin/com/lagradost/hless/`:

| File | Contents |
| :--- | :--- |
| `StreamMapper.kt` | pure: engine link → `StremioStream` |
| `MediaFlow.kt` | pure: build proxy URLs |
| `StreamServiceImpl.kt` | orchestration and cache |

## StreamServiceImpl

State: `val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`
and a cache `ConcurrentHashMap<String, Entry>` keyed by `"<type>/<id>"`.

```kotlin
class Entry(
    val startedAt: Long,
    val results: ConcurrentHashMap<String, List<StremioStream>>, // by providerId
    val job: Job,                                                 // all provider work
)
```

`streams(type, id)`:

1. `parseStremioId` (2d). Null → empty list.
2. If the cache has an entry younger than 5 minutes, use it. Otherwise create
   one (steps 3–5) and put it in the cache.
3. `Cinemeta.lookup`. Null → empty list, and do not cache.
4. `client.providers()`, keep those whose `tvTypes` intersect
   `wantedTypes(type)` (2d).
5. In `scope`, launch one coroutine per provider, all children of `entry.job`.
   Each does, inside `withTimeout(45_000)` and a `try/catch` that logs and
   swallows everything except `CancellationException`:
   - `data = resolveData(...)` (2d) with `timeoutMs = 20_000`; null → stop.
   - `response = client.links(providerId, data, budgetMs = 25_000)`.
   - `entry.results[providerId] = response.links.mapNotNull { StreamMapper.map(...) }`.
6. Wait for `entry.job` to finish, but no longer than
   `Config.deadlineMs - (now - entry.startedAt)`; if that is zero or negative,
   do not wait. Use `withTimeoutOrNull`. Do **not** cancel the job on timeout:
   it keeps filling `entry.results` for the next request.
7. Return all lists in `entry.results`, flattened and sorted (below).

Because the coroutines run in `scope`, not in the request, a client that
disconnects does not stop them.

Housekeeping: every 60 seconds remove entries older than 5 minutes and cancel
their jobs. Limit concurrent provider coroutines across all requests with a
`Semaphore(16)`.

Sorting: by quality descending (unknown last), then provider name.

## StreamMapper

`fun map(provider: String, providerId: String, link: StreamLinkDto, subtitles: List<SubtitleDto>): StremioStream?`

Input fields (engine `StreamLinkDto`): `url`, `name`, `source`, `quality`
(pixel height, e.g. `1080`; values ≤ 0 or unknown mean "unknown"), `type`
(`VIDEO`, `M3U8`, `DASH`, `TORRENT`, `MAGNET`), `headers`, `audioTracks`,
`drm`.

Drop (return null):

- `type` is `TORRENT` or `MAGNET`;
- `url` does not start with `http://` or `https://`;
- `audioTracks` is not empty (split video and audio; Stremio cannot play it);
- `drm != null` and MediaFlow is not configured.

Output:

- `name`: `"CS " + qualityLabel`, where the label is `"2160p"`, `"1080p"`,
  … or `"?"`.
- `description`: `"<provider> · <link.source>\n<link.name>"`.
- `behaviorHints.bingeGroup`: `"hless|<providerId>|<link.source>|<quality>"`.
- `subtitles`: every `SubtitleDto` whose URL is http(s), as
  `StremioSubtitle(id = "<index>", url, lang = language)`.
- `url` and the remaining hints depend on the case:

| Case | `url` | hints |
| :--- | :--- | :--- |
| MediaFlow configured | `MediaFlow.wrap(link)` | `notWebReady = false` omitted (leave null) |
| no MediaFlow, `headers` empty, URL is `https` | `link.url` | none |
| no MediaFlow, otherwise | `link.url` | `notWebReady = true`, `proxyHeaders.request = link.headers` |

The last case only plays in Stremio clients that have a local streaming server
(desktop, Android). That is acceptable without MediaFlow.

## MediaFlow

`fun wrap(link: StreamLinkDto): String`, used only when both
`Config.mediaflowUrl` and `Config.mediaflowPassword` are set.

| `link.type` | Endpoint |
| :--- | :--- |
| `M3U8` | `/proxy/hls/manifest.m3u8` |
| `DASH` | `/proxy/mpd/manifest.m3u8` |
| `VIDEO` | `/proxy/stream` |

Query parameters, built with `HttpUrl.Builder` so everything is encoded:

- `d` = `link.url`
- `api_password` = the password
- for each header: `h_<lowercase header name>` = value
- DASH with `drm`: `key_id` = `drm.kid`, `key` = `drm.key`

Example result:

```
https://mediaflow.example.org/proxy/hls/manifest.m3u8?d=https%3A%2F%2Fcdn.example%2Fa.m3u8&api_password=secret&h_referer=https%3A%2F%2Fsite.example%2F
```

Known limits, leave as they are:

- The password is visible in the URL Stremio receives. MediaFlow has
  `POST /generate_url` to make encrypted, expiring URLs; that is a later
  improvement.
- The engine gives ClearKey keys as hex. Check on a real DASH stream whether
  MediaFlow accepts hex; if not, convert to unpadded base64url.
- MediaFlow fetches from its own IP. A link tied to the IP that resolved it
  fails unless MediaFlow and this container leave the network the same way.

## Tests

`StreamMapperTest.kt`: each drop rule; the three URL cases; quality labels.

`MediaFlowTest.kt`: endpoint per type; header names lowercased; characters
`&`, `=`, space and `%` in the destination URL survive a round trip (parse
the result with `HttpUrl` and read `d` back).

`StreamServiceImplTest.kt` with a fake `EngineClient` (make `EngineClient` an
interface in 2a if it is not one already):

- two providers, one answering after 100 ms, one after 3 s, deadline 1 s:
  the first call returns one provider's streams, a second call 3 s later
  returns both, and the fake saw each provider's `links` exactly once;
- a provider that throws does not affect the other.

## Done when

- Tests pass.
- On the test container with at least one working extension, this returns
  streams for a well-known film, the second run faster than the first:

  ```bash
  curl -s http://127.0.0.1:7000/stream/movie/tt0111161.json
  ```

- In Stremio, the same film lists `CS …` streams and one of them plays.
