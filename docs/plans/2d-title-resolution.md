# 2d. Title resolution (IMDb ID → provider page)

Read [README.md](README.md) first. Depends on: 2a.

## Problem

Stremio asks for `tt0944947:1:2`. CloudStream providers only know "search by
text". This part turns the ID into, per provider, the `data` string that
`/links` needs.

## Files to create

Under `engine/src/main/kotlin/com/lagradost/hless/`:

| File | Contents |
| :--- | :--- |
| `StremioId.kt` | parsing the ID |
| `Cinemeta.kt` | title lookup |
| `TitleMatcher.kt` | pure matching functions |
| `MatchCache.kt` | disk cache |
| `Resolver.kt` | ties them together for one provider |

## StremioId

```kotlin
data class StremioId(val imdb: String, val season: Int?, val episode: Int?)
fun parseStremioId(type: String, id: String): StremioId?
```

- `movie`: id must match `tt\d+`. Season and episode are null.
- `series`: id must match `tt\d+:\d+:\d+`.
- Anything else returns null (the caller answers with no streams).

## Cinemeta

`suspend fun lookup(type: String, imdb: String): TitleInfo?`

- `GET https://v3-cinemeta.strem.io/meta/<type>/<imdb>.json`, timeout 5 s.
- Read `meta.name` and the year: the first four digits found in `meta.year`,
  else in `meta.releaseInfo`, else null. (`year` can look like `"2008–2013"`.)
- `data class TitleInfo(val name: String, val year: Int?)`.
- Cache results in a `ConcurrentHashMap` for the life of the process. Do not
  cache failures.
- Network error or missing `meta`: return null.

## TitleMatcher

Pure functions, no I/O.

`fun normalize(title: String): String`

1. Lowercase.
2. Remove a trailing year in brackets: `" (2019)"`, `" [2019]"`.
3. Replace `&` with `and`.
4. Remove everything that is not a letter or digit (Unicode-aware:
   `Character.isLetterOrDigit`).

`fun wantedTypes(type: String): Set<String>`: provider result types that may
match.

- `movie` → `Movie`, `AnimeMovie`, `Documentary`, `Cartoon`, `Others`
- `series` → `TvSeries`, `Anime`, `OVA`, `Cartoon`, `AsianDrama`,
  `Documentary`, `Others`

`fun pickMatch(info: TitleInfo, type: String, results: List<SearchResultDto>): SearchResultDto?`

Score each result; highest wins; below 60 means no match (return null).

| Condition | Points |
| :--- | :--- |
| `normalize(result.name) == normalize(info.name)` | 70 |
| else `FuzzySearch.ratio(...)` of the two normalized names ≥ 92 | 55 |
| else | discard the result |
| `result.type` in `wantedTypes(type)` | +15 |
| `result.type` not in `wantedTypes(type)` | discard the result |
| both years known and equal | +15 |
| both years known and differ by 1 | +5 |
| both years known and differ by more | −40 |

`FuzzySearch` is `me.xdrop.fuzzywuzzy.FuzzySearch`, already a dependency.
Ties: keep the earlier result (providers return best hits first).

`fun pickEpisode(details: DetailsDto, season: Int, episode: Int): EpisodeDto?`

1. An episode with `season == season && episode == episode`.
2. Else, if no episode in the list has a season at all and `season == 1`: the
   one with `episode == episode`.
3. Else null.
4. If several match (sub and dub variants), prefer `audio == "sub"`, then
   `audio == null`, then the first.

Out of scope, write nothing for it: absolute episode numbering for anime,
specials (season 0), titles in other languages.

## MatchCache

Remembers which provider page belongs to an IMDb ID, so later requests skip
the search.

- File: `<data dir>/hless/matches.json`, where data dir is the JVM property
  `cs.cloudstream.home`.
- Key: `"<imdb>|<providerId>"`. Value: `{"url": String?, "at": epochMillis}`.
  `url == null` records "this provider does not have it".
- A hit expires after 30 days, a miss after 24 hours.
- Load once at start (missing or corrupt file means empty). Save at most once
  every 30 seconds and on shutdown: write to `matches.json.tmp`, then move
  over the real file with `StandardCopyOption.ATOMIC_MOVE`.
- Guard the map with a `Mutex` or use `ConcurrentHashMap`.

## Resolver

```kotlin
suspend fun resolveData(
    client: EngineClient, providerId: String,
    type: String, id: StremioId, info: TitleInfo, timeoutMs: Long,
): String?   // the `data` for /links, or null
```

1. Look in `MatchCache`. A fresh miss returns null. A fresh hit gives `url`;
   go to step 4.
2. `client.search(providerId, info.name, timeoutMs)`.
3. `pickMatch(...)`. Store the result (or the miss) in `MatchCache`. Null
   returns null.
4. `client.details(providerId, url, timeoutMs)`. If this throws with code
   `provider_no_data` or HTTP 404, delete the cache entry and return null (the
   page moved).
5. `movie`: return `details.movieData`. `series`:
   `pickEpisode(details, season, episode)?.data`.

Exceptions other than the one in step 4 propagate; 2e catches them per
provider. A network failure must not be stored as a miss.

## Tests

`engine/src/test/kotlin/com/lagradost/hless/TitleMatcherTest.kt`:

- `normalize("Spider-Man: Homecoming (2017)") == "spidermanhomecoming"`;
- exact name and year beats a fuzzy one;
- a remake with a year 20 off is rejected when an exact-year result exists,
  and rejected alone as well (70 + 15 − 40 < 60);
- a `TvSeries` result is discarded for `movie`;
- `pickEpisode` cases 1, 2 and 4.

`StremioIdTest.kt`: valid and invalid IDs.

`MatchCacheTest.kt`: round trip through a temp directory, expiry of hits and
misses using an injected clock (`() -> Long`).

## Done when

Tests pass. End-to-end checking happens in 2e.
