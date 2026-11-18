# 5c. Coverage and latency

Read [README.md](README.md) first. Depends on: 2e, 2g. Independent of 5a and
5b, but the cap change in step 1 assumes 5b's folding.

## Why this plan exists

Measured on 2026-11-18 with a film that five providers have, comparing the
engine's raw answer with what the addon returned
(`scripts/title_links.py`, described below):

| | Links |
| :--- | :--- |
| Engine returned, five providers | 15 (19 on an earlier run, when one more host answered) |
| Addon showed | 12 |
| Dropped | 3 (7 on the earlier run) |

Every dropped link was dropped by one rule, the cap of 4 streams per
provider: Megakino lost its fifth link, Filmpalast its fifth and sixth. No
link was lost to split audio, DRM or an invalid URL (the script counts
those: 0 of 15). The other bridge shows more entries mainly because it has no
such cap and splits one host into several quality entries.

Latency: a film requested for the first time took **15.1 s**, which is
exactly `HLESS_DEADLINE_MS`. Some provider (the Cloudflare-protected ones
never answer without a solver) holds every first request to the full
deadline, even when the useful providers finished in two seconds.

Also seen in the code, not yet in the field:

- An entry with no streams is cached for 5 minutes, so one bad moment (site
  down, solver restart) makes a title empty for 5 minutes.
- When a link is dropped, nothing says why. The only way to find out was to
  run a second engine by hand.

## 1. Defaults after folding

With [5b](5b-link-probing.md) folding, one host is one entry, so 4 per
provider is too tight for providers that offer several hosts.

| Variable | Old | New |
| :--- | :--- | :--- |
| `HLESS_MAX_PER_PROVIDER` | 4 | 6 |
| `HLESS_MAX_STREAMS` | 30 | 40 |

Change the defaults in `Config.kt` and in the `RankSettings` constructor
(`StreamRanker.kt`), and the two tests that assert them. Do not change the
ranking logic.

## 2. Say why a link was dropped

`StreamMapper.map` returns `null` for several reasons. Return a result type
instead:

```kotlin
sealed interface MapResult {
    data class Stream(val stream: StremioStream) : MapResult
    data class Dropped(val reason: DropReason) : MapResult
}
enum class DropReason { TORRENT, NOT_HTTP, SPLIT_AUDIO, DRM_NO_MEDIAFLOW, BELOW_MIN_QUALITY, DUPLICATE, OVER_CAP }
```

`StreamRanker.rank` counts the last three. In `StreamServiceImpl`, after
ranking, log **one line per request**:

```
hless: tt22084616 movie: 5 providers answered, 15 links, 12 shown, dropped {OVER_CAP=3} in 2.3s (complete)
```

Rules: only counts and reason names, never a URL, a title, a header or an
exception message. Print `{}` when nothing was dropped. Do not change what is
dropped; only count it. If the counts later show `SPLIT_AUDIO` on a repository
that matters, decide then whether to keep HLS links that have extra audio
tracks (HLS masters normally carry their audio themselves). There is no
evidence yet, so do not guess.

## 3. Answer early, keep working

Rule: do not wait for providers that are clearly stuck.

Config:

| Variable | Default | Meaning |
| :--- | :--- | :--- |
| `HLESS_MIN_WAIT_MS` | `4000` | Never answer earlier than this after the request started, unless every provider is done |
| `HLESS_QUIET_MS` | `3000` | After the minimum wait, answer once at least one stream exists and no provider has published anything for this long |

`HLESS_DEADLINE_MS` stays as the hard limit.

In `StreamServiceImpl`:

- `Entry` gets `@Volatile var lastActivityAt: Long`, set at creation and each
  time a provider publishes results (including the first, unprobed, publish
  of 5b) or finishes (success, no match, failure).
- Replace the one `withTimeoutOrNull(remaining) { entry.job.join() }` in
  `settle` with a loop that checks every 100 ms:
  1. `entry.job.isCompleted` → answer, `complete = true`;
  2. elapsed ≥ `deadlineMs` → answer, `complete = false`;
  3. elapsed ≥ `minWaitMs`, at least one result published, and
     `now - lastActivityAt ≥ quietMs` → answer, `complete = false`.
- Providers that are still running keep going in the shared scope and fill
  the cache, exactly as today.

The interface changes so the server can tell the client:

```kotlin
data class StreamResult(val streams: List<StremioStream>, val complete: Boolean)
interface StreamService { suspend fun streams(type: String, id: String): StreamResult }
```

In `AddonServer.kt`: `Cache-Control: max-age=60` only when `complete` and the
list is not empty, otherwise `no-store`. Stremio then asks again the next
time the title is opened and gets the providers that finished meanwhile.
Update `StreamService` fakes in tests.

## 4. Do not cache emptiness for long

An entry is kept for 5 minutes only when it has at least one stream. An entry
with none is kept for **30 seconds** (so a refresh in the same breath does
not start the whole fan-out again, but a bad moment heals fast). Apply the
rule in the places that compare against `ENTRY_TTL` (`streams` and the
cleanup loop): compute the age limit from `entry.results.isEmpty()`.

## 5. How to measure

`scripts/title_links.py` prints the engine's raw links per provider, with
counts of unknown quality, split audio and DRM, and (with `--addon`) the
addon's list for comparison. An engine's data directory is locked by the
running process, so use a **copy**:

```bash
rm -rf /root/hless-probe && cp -r /root/hless-live /root/hless-probe && rm -f /root/hless-probe/daemon.lock
```

```bash
nohup java --add-modules=jdk.incubator.foreign --enable-native-access=ALL-UNNAMED -Xmx192m -XX:+UseSerialGC -Dcs.cloudstream.home=/root/hless-probe -jar /root/cloudstream-hless/engine/build/libs/daemon.jar 17172 probe-token-0123456789 </dev/null >/root/engine-probe.out 2>&1 &
```

```bash
python3 scripts/title_links.py --engine http://127.0.0.1:17172 --token probe-token-0123456789 --title "Spider-Man: Brand New Day" --providers Megakino Filmpalast Kellerkino FilmFrei24 EinschaltenIn --addon http://127.0.0.1:7000/stream/movie/tt22084616.json
```

(Adjust the paths and the film; the film and IMDb ID above are the ones the
numbers in this plan come from. Stop the probe engine afterwards with
`pkill -x java` only if the live addon is not running on the same machine;
otherwise kill the one process by its PID.)

Time a request with `time curl -s -o /dev/null <addon stream url>` on a title
that was not requested in the last 5 minutes.

## Tests

- `StreamRankerTest`: the new defaults; a provider with 6 entries keeps 6,
  with 8 keeps 6; counts of `OVER_CAP`, `DUPLICATE`, `BELOW_MIN_QUALITY`.
- `StreamMapperTest`: each `DropReason`.
- `StreamServiceImplTest` with fake providers:
  - one fast provider (100 ms) and one that never finishes: answer after
    about `minWaitMs + quietMs` with `complete = false`, not at the deadline;
  - both fast: answer immediately with `complete = true`;
  - nothing finishes: answer at the deadline with an empty list;
  - the slow provider finishing later shows up in the next call, and the
    fake saw its `links` exactly once;
  - an entry with no streams is asked again after 30 s (inject the clock).
- `AddonServerTest`: `complete = false` → `no-store`.

## Done when

Tests pass, and on the test container the same film as above:

- shows every entry the five providers deliver, with no cap drops in the log
  line;
- answers in well under the 15 s deadline when only the Cloudflare-protected
  providers are slow;
- a second request right after is instant.
