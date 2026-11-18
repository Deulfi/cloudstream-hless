# 5b. Link probing and folding

Read [README.md](README.md) first. Depends on: 2e. Feeds
[5a](5a-stream-display.md) (labels) and [2g](2g-stream-ordering.md)
(ordering).

**State: done 2026-11-18** (`LinkFacts.kt`, `SafeFetch.kt`, `LinkProbe.kt`,
`LinkFolding.kt`; tests `LinkFactsTest`, `SafeFetchTest`, `LinkProbeTest`,
`LinkFoldingTest`, `StreamPipelineTest`). See "Findings" at the end for what the
live run showed and what changed from the text below.

## Why this plan exists

The engine reports a `quality` for each link, but it is only what the
extension wrote, and often it is wrong or missing. The real facts are in the
playlist or in the HTTP headers. Observed on 2026-11-18 on a real film, from
the maintainer's LAN (`scripts/title_links.py --probe`):

| Link as the engine reports it | What the link really is |
| :--- | :--- |
| `M3U8`, quality 400, source `Voe` | an HLS **master** playlist with variants 800p, 720p, 480p |
| `M3U8`, quality 800 / 720 / 480, source `Voe`, same host | the three **variant** playlists of that same master, listed again as separate links |
| `M3U8`, quality 400, source `Vidara` | master, one variant, 720p |
| `M3U8`, quality 400, source `Firestream` | master, one variant, 800p |
| `M3U8`, quality 400, source `Vixeo` | a media playlist, 145 minutes, 2611 segments; took 0.8–9 s to fetch |
| `VIDEO`, quality 400, source `Voe MP4` | a file, 1.61 GB, `Accept-Ranges: bytes` |
| `VIDEO`, quality 1080, source `DoodStream` | a file, 2.07 GB |

So today the user sees the same Voe stream four times, and the label `400p`
for something that is 720p or 800p. Two things fix it: **probe** each link to
learn the facts, and **fold** the variants into their master.

Findings that shape the design:

- `HEAD` worked on every direct-file host seen (200, `Content-Length`,
  `Accept-Ranges: bytes`) in 0.1–0.2 s. Keep a `Range: bytes=0-0` GET as the
  fallback for hosts that refuse `HEAD`.
- A master playlist lists *trick-play* streams too:
  `#EXT-X-I-FRAME-STREAM-INF` lines with the same resolutions and tiny
  bandwidths (23–54 kbps). They must not be counted as variants.
- Media playlists give the exact runtime (sum of `#EXTINF`): 145 minutes,
  where the provider data said 150. Direct files have no runtime; use one
  found elsewhere for the same title (below).
- Every link observed needed request headers (`User-Agent`, often `referer`,
  sometimes `Origin`). Send them when probing.
- A slow media playlist (2611 segments) can take seconds. The probe must be
  time-boxed and its failure must cost nothing.

## Files

Under `engine/src/main/kotlin/com/lagradost/hless/`:

| File | Contents |
| :--- | :--- |
| `LinkFacts.kt` | `LinkFacts` and the pure parsers |
| `SafeFetch.kt` | the HTTP client with the address rules |
| `LinkProbe.kt` | probing, cache |
| `LinkFolding.kt` | pure folding of variants into masters |

## LinkFacts

```kotlin
enum class Kind { MASTER, MEDIA, FILE }
data class LinkFacts(
    val kind: Kind,
    val heights: List<Int>,        // MASTER: variant heights, highest first, distinct. Otherwise empty
    val topBandwidthKbps: Int?,    // MASTER: highest bandwidth of a variant
    val durationSec: Double?,      // MEDIA with #EXT-X-ENDLIST: sum of #EXTINF. Otherwise null
    val sizeBytes: Long?,          // FILE: Content-Length or the total of Content-Range
    val live: Boolean,             // MEDIA without #EXT-X-ENDLIST
)
```

## Pure parsers (write and test these first)

`fun parseHls(text: String): LinkFacts?`. `null` when the text does not
start (after optional whitespace and a BOM) with `#EXTM3U`.

- If any line starts with `#EXT-X-STREAM-INF`: kind `MASTER`.
  - For **those lines only** (not `#EXT-X-I-FRAME-STREAM-INF`): read
    `RESOLUTION=<w>x<h>` and take `h`; read `AVERAGE-BANDWIDTH=` if present,
    else `BANDWIDTH=`, in bits per second.
  - `heights` = distinct heights, descending. `topBandwidthKbps` = the
    largest bandwidth ÷ 1000, or null.
- Otherwise kind `MEDIA`: `durationSec` = sum of all `#EXTINF:<seconds>`
  values when the text contains `#EXT-X-ENDLIST`, else null and
  `live = true`.

`fun parseFileHeaders(status: Int, contentLength: String?, contentRange: String?): Long?`

- status 200 with `Content-Length` → that number.
- status 206 with `Content-Range: bytes 0-0/<total>` → `<total>`.
- anything else, or a non-numeric or `*` total → null.

Test with real shapes: a master with three `STREAM-INF` and three
`I-FRAME-STREAM-INF` lines (expect three heights, not six); a VOD media
playlist; a live playlist; a 404 body; a file that is not a playlist.

## SafeFetch: the address rules

A provider is third-party code. The addon must not become a way to make
requests into the maintainer's LAN. `SafeFetch` is an OkHttp client with:

- only `http` and `https`;
- `followRedirects(false)`; follow at most 3 redirects by hand, and apply
  every rule below to each hop;
- a custom `Dns` that resolves the host and **throws `UnknownHostException`
  if any resolved address is not public**: loopback, any-local, link-local,
  site-local (10/8, 172.16/12, 192.168/16), IPv6 unique-local (`fc00::/7`),
  multicast, and `169.254.0.0/16`;
- the same check applied by hand to a URL whose host is an IP literal
  (OkHttp does not call `Dns` for literals);
- connect timeout 3 s, call timeout 4 s, response body read limited to
  512 KiB;
- request headers copied from the link, **except** `Host`, `Content-Length`,
  `Transfer-Encoding`, `Connection`, `Upgrade`, `TE`, `Trailer`, `Range`,
  `Proxy-*`, and any header whose name or value contains a control
  character. Set a default `User-Agent` when the link has none.

MediaFlow and the solver are not fetched through `SafeFetch`; they are
configured by the maintainer.

Test `isPublicAddress` with `127.0.0.1`, `10.1.2.3`, `172.16.0.1`,
`192.168.1.1`, `169.254.1.1`, `::1`, `fd00::1`, `0.0.0.0`, and with public
addresses `93.184.216.34`, `2606:4700::1111`.

## LinkProbe

```kotlin
suspend fun probe(link: StreamLinkDto): LinkFacts?   // null = unknown, never throws
```

- `M3U8`: GET with the link's headers; `parseHls` on the body (UTF-8,
  lenient). Status other than 200 → null.
- `VIDEO`: `HEAD` with the link's headers; `parseFileHeaders`. If that gives
  null, `GET` with `Range: bytes=0-0` and the same function (206 case). Any
  failure → null. Do not read the body.
- `DASH`, torrents, anything else: return null without a request.
- Catch **everything** except cancellation and return null. Log only the
  count of failures, never the URL or an exception message.

Cache: in memory, key = SHA-256 of `url` plus the sorted header names and
values, value = `LinkFacts?` (cache nulls too, for 60 s), TTL 10 minutes for
facts. At most 500 entries (drop the oldest). Never on disk: these URLs are
usually signed and short-lived.

Limit concurrent probes with `Semaphore(8)` across the process.

## Where it runs

In `StreamServiceImpl`, per provider, right after `client.links(...)`:

1. Publish the candidates **without** facts first (`results[providerId] = …`),
   so a slow probe never costs a stream at the deadline.
2. Probe all links of that provider in parallel (`async` per link inside a
   `coroutineScope`), each inside `withTimeout(4_000)`.
3. Fold (below), build the display strings (5a), and publish again,
   replacing the first entry.

The whole provider coroutine still lives inside the existing 45 s timeout.

## Runtime and bitrate

Bitrate of a file = `sizeBytes × 8 ÷ runtimeSec`. Runtime sources, first one
available wins:

1. a sibling `MEDIA` link of the **same title** with `durationSec` (any
   provider in the same entry; observed 144.9–145.0 min across providers);
2. TMDB `runtime` in minutes for a movie: add `runtimeMin: Int?` to
   `TitleInfo`, read from the `/movie/<id>` response the code already
   requests (field `runtime`; null or 0 means unknown). Include it in the
   title cache entry and bump nothing else.

No runtime: show the size only. Series: skip rule 2 (no per-episode runtime
is requested); rule 1 still applies.

For a master: show only `~<top bandwidth> Mbps`, no size.

## Folding

Pure function in `LinkFolding.kt`:

```kotlin
data class Probed(val link: StreamLinkDto, val facts: LinkFacts?)
fun fold(items: List<Probed>, keepVariants: Boolean): List<Probed>
```

Group the items of one provider by `(link.source, link.type, host of link.url)`.
Within a group of type `M3U8`:

1. Masters = items whose facts have kind `MASTER`. If there are none: change
   nothing for this group.
2. If there are masters: keep the master with the most `heights` (ties: the
   first), and drop every other item of the group whose facts are `MEDIA`,
   or whose facts are null and whose `knownHeight(quality)` is in the
   master's `heights`. Other masters of the group are dropped as duplicates.
3. `keepVariants = true` (setting `HLESS_VARIANTS=all`, default `fold`) skips
   the dropping.

Never fold across providers, sources or hosts. Never fold when the probe
failed for the master (no facts): showing duplicates is better than hiding a
working link.

Expected, from the table at the top: Megakino's four Voe lines become one
(`800p · 720p · 480p`), Filmpalast's Vidara, Voe and Firestream stay as one
line each with the right resolution, Vixeo stays (a lone media playlist,
label `Auto`).

## Ordering and caps

After folding, `rank` (2g) sorts by the top known height of each entry
(`facts.heights.max()` when probed, else `knownHeight(quality)`), highest
first, unknown last. The per-provider cap now counts folded entries, so
raise the default `HLESS_MAX_PER_PROVIDER` from 4 to 6 (see
[5c](5c-coverage-and-latency.md)).

## Tests

- `LinkFactsTest.kt`: the parser cases above.
- `SafeFetchTest.kt`: address rules (pure `isPublicAddress`), header
  filtering, redirect limit with `MockWebServer` if it is on the test
  classpath, otherwise only the pure parts.
- `LinkFoldingTest.kt`: the Megakino shape (one master with `800,720,480`
  plus three media links and a `Voe MP4` file → two entries); a master that
  failed to probe → nothing dropped; two providers with the same source name
  → not merged; `keepVariants`.
- `LinkProbeTest.kt` with a fake fetcher interface: nulls on failure,
  cache hit does not call the fetcher twice.

## Done when

Tests pass, and on the test container, for the film used in
[5c](5c-coverage-and-latency.md), the addon shows one entry per Voe master
with its variants listed, real resolutions instead of `400p`, and sizes on
direct files. Compare with `scripts/title_links.py --probe`, which prints
what each probe should find.

## Findings

- **Heights are quality classes, not pixel rows.** A master's variant
  `1920x800` is what a "1080p" scope film really is, and the other bridge labels
  it 1080p. `qualityClass(width, height)` (in `LinkFacts.kt`) maps a picture size
  to 144/240/360/480/720/1080/1440/2160 by width **or** height, whichever
  reaches the higher class. So the Megakino master reads
  `1080p · 720p · 480p`, not `800p · 720p · 480p`. Wherever the text above says
  "heights" for a master, read "quality classes". Links that are not probed keep
  the number the extension gave.
- **Probing costs time.** On the test container (2 slow cores) the probe phase
  was what kept the first answer at the deadline. Final settings: per probe
  3 s (`SafeFetch`: connect 2 s, read 3 s, whole call 3 s; `LinkProbe`
  3 s), at most 16 probes at once, and 4 s per provider for all its probes
  together; a probe that is not done is simply shown without facts.
- **Failures are logged, once per host per ten minutes**, with the stage the
  call reached and the milliseconds, never a URL
  (`hless: probe failed host=x: InterruptedIOException, call@0 dns@0 dns done@5043 now@5043`).
  What this showed on the test container: some hosts answer a `HEAD` in
  0.1 s, others stall because of a name lookup that took 5 s once, a response
  that never came (a media playlist of 2600 segments), or a server that was
  simply slow at that moment. None of it was a bug in the probe.
- **Not folded without a confirmed master**, as designed. In the live list the
  duplicates that remain are exactly the links whose master probe failed.
- **Bitrate** is shown for a file when a runtime is known (median of the VOD
  media playlists seen, else TMDB's `runtime`) and for a master from its
  `AVERAGE-BANDWIDTH` or `BANDWIDTH`; the numbers match the other bridge's
  (`7.90 GB · ~7.3 Mbps` for the same file).
