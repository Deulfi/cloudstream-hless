# 2g. Stream ordering and filtering

Read [README.md](README.md) first. Depends on: 2e. Replaces the "Sorting"
paragraph of 2e.

## Goal

Stremio shows streams in the order the addon returns them and many users
play the first one. Put the most wanted streams first and drop the rest.

## Config (add to `Config`, plan 2a)

| Variable | Type | Default | Meaning |
| :--- | :--- | :--- | :--- |
| `HLESS_LANGS` | list | `en` | Shared with 2f. Providers in an earlier language rank higher |
| `HLESS_MIN_QUALITY` | Int | `0` | Drop streams with a known height below this (e.g. `720`). Unknown quality is kept |
| `HLESS_MAX_PER_PROVIDER` | Int | `4` | Keep at most this many streams per provider |
| `HLESS_MAX_STREAMS` | Int | `30` | Keep at most this many streams in total |
| `HLESS_PROVIDERS_DENY` | list | empty | Provider names never asked (case-insensitive) |
| `HLESS_PROVIDERS_ALLOW` | list | empty | If not empty, only these providers are asked |
| `HLESS_PROVIDERS_FIRST` | list | empty | Provider names ranked before all others, in this order |

Provider names are the `name` field of `GET /providers`.

## Files

`engine/src/main/kotlin/com/lagradost/hless/StreamRanker.kt`, pure, no I/O.

```kotlin
data class Candidate(
    val stream: StremioStream,
    val providerName: String,
    val providerLanguage: String?,   // `language` of the provider, e.g. "de"
    val quality: Int,                // height in pixels; <= 0 means unknown
    val url: String,                 // the original link URL, before MediaFlow
)
fun rank(candidates: List<Candidate>, settings: RankSettings): List<StremioStream>
fun askProvider(name: String, settings: RankSettings): Boolean
```

`RankSettings` holds the values from the table so tests can construct it.

## askProvider

Used in 2e step 4, before any engine call:

- name in `deny` → false;
- `allow` not empty and name not in it → false;
- else true.

## rank

1. Drop candidates with `quality in 1 until minQuality`.
2. Drop duplicates by `url`, keeping the first.
3. Sort by, in this order:
   1. index of `providerName` in `providersFirst` (not listed: after all
      listed);
   2. index of `providerLanguage` in `langs` (not listed or null: after all
      listed);
   3. `quality` descending, unknown last;
   4. `providerName`, then original position (a stable sort does this).
4. Walk the sorted list and keep a candidate only while its provider has
   fewer than `maxPerProvider` kept so far.
5. Cut to `maxStreams`.

## Changes to 2e

- `StreamServiceImpl` keeps `Candidate` objects per provider instead of
  `StremioStream`, and calls `rank` on the flattened list in step 7.
- `StreamMapper` puts the provider's language into the name when it is
  known: `"CS DE 1080p"` instead of `"CS 1080p"` (language upper-cased).

## Tests

`StreamRankerTest.kt`:

- German provider before English with `langs = [de, en]`, even at lower
  quality;
- `providersFirst` beats language;
- unknown quality survives `minQuality` and sorts after known ones;
- per-provider cap keeps the best of each provider, not the first seen;
- duplicate URLs collapse;
- `askProvider` with deny, allow, and both.

## Done when

Tests pass, and the stream list in Stremio for a known film starts with the
preferred language at the best quality.
