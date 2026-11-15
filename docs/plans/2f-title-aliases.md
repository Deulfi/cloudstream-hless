# 2f. Title aliases (TMDB)

Read [README.md](README.md) first. Depends on: 2d. Changes 2d's `TitleInfo`,
`Cinemeta` use, `pickMatch` and `Resolver`; build it together with 2d or
right after.

## Problem

Cinemeta returns English titles. Providers in other languages list the same
film under a translated title ("The Shawshank Redemption" is "Die
Verurteilten" on German sites), so an English-only search finds nothing.

## Goal

Look up every title in the user's languages and search and match with all of
them.

## Config (add to `Config`, plan 2a)

| Variable | Type | Default | Meaning |
| :--- | :--- | :--- | :--- |
| `HLESS_TMDB_KEY` | String? | none | TMDB API key (v3, 32 hex characters) or read access token (v4, starts with `eyJ`). Unset: Cinemeta only |
| `HLESS_LANGS` | list, comma-separated | `en` | Preferred languages, most wanted first, as two-letter codes, e.g. `de,en` |

## Files

Under `engine/src/main/kotlin/com/lagradost/hless/`:

| File | Contents |
| :--- | :--- |
| `Tmdb.kt` | the two TMDB calls and parsing |
| `Titles.kt` | `TitleInfo`, `TitleSource` (TMDB, then Cinemeta), disk cache |

## TitleInfo (replaces the one in 2d)

```kotlin
data class TitleInfo(val names: List<String>, val year: Int?)
```

`names` is ordered, most useful first, never empty, at most 4 entries, with
no two entries that are equal after `normalize` (2d).

## TMDB calls

Base `https://api.themoviedb.org/3`. Checked against the live API on
2026-11-15.

Authentication: a v3 key goes in the query as `api_key=<key>`; a v4 token
goes in the header `Authorization: Bearer <token>`. Decide by
`value.startsWith("eyJ")`. Never log the request URL.

1. `GET /find/<imdb>?external_source=imdb_id`
   - movie: `movie_results[0]` with `id`, `title`, `original_title`,
     `release_date` (`"1994-09-23"`)
   - series: `tv_results[0]` with `id`, `name`, `original_name`,
     `first_air_date`
   - Use `movie_results` for Stremio type `movie`, `tv_results` for `series`.
     If that list is empty, TMDB does not know the ID: fall back to Cinemeta.
2. `GET /movie/<id>?append_to_response=translations` or
   `GET /tv/<id>?append_to_response=translations`
   - `translations.translations[]`: each has `iso_639_1` (language),
     `iso_3166_1` (country) and `data.title` (movies) or `data.name`
     (series).
   - **The translated title is an empty string or null when it equals the
     original.** Treat empty and null as "no entry".

Do not use `alternative_titles`: it contains abbreviations and working
titles ("GoT", "BB") that cause false matches.

Timeout 5 s per call. HTTP 401: log once "TMDB key rejected" and behave as if
no key were set until restart. Any other failure: fall back to Cinemeta for
this request.

## Building `names`

1. For each language in `HLESS_LANGS`, in order: the first non-empty
   translated title with that `iso_639_1`.
2. Then the original title (`original_title` / `original_name`).
3. Then the English title (`title` / `name` from step 1 of the calls; the
   find call answers in English by default).
4. Drop duplicates by `normalize`, keep the first occurrence, cut to 4.

Year: the first four characters of `release_date` / `first_air_date`, or
null.

Example with `HLESS_LANGS=de,en` and `tt0111161`:
`names = ["Die Verurteilten", "The Shawshank Redemption"]`, `year = 1994`.

## TitleSource

`suspend fun lookup(type: String, imdb: String): TitleInfo?`

1. Disk cache hit younger than 30 days: return it.
2. If a TMDB key is set and not rejected: TMDB. On success cache and return.
3. Cinemeta (2d): `TitleInfo(listOf(name), year)`. Cache and return.
4. Null if both fail. Never cache a failure.

Disk cache: `<data dir>/hless/titles.json`, key `"<type>|<imdb>|<langs>"`
where `<langs>` is `HLESS_LANGS` joined by `,` (so changing languages does
not serve stale names). Same load, save and atomic-move rules as
`MatchCache` in 2d; share the code.

## Changes to 2d

`pickMatch(info, type, results)`: compute the name score for every entry of
`info.names` and use the best one. Everything else is unchanged.

`Resolver.resolveData`, steps 2–3 become:

1. For `name` in `info.names.take(2)`:
   1. `results = client.search(providerId, name, timeoutMs)`
   2. `match = pickMatch(info, type, results)`; if not null, stop the loop.
2. Store the match, or the miss if no name matched.

Two searches per provider at most. The engine allows 40 searches in a burst
and refills 20 per second, which covers about 30 providers.

Optimisation, do it only if it is trivial: skip the second search when the
provider's `language` equals the first entry of `HLESS_LANGS` and the first
search returned results but none matched.

## Tests

`TmdbTest.kt` with JSON fixtures stored as strings in the test (no network,
no real key):

- a movie with a German translation → German name first, English second;
- a series whose German translation is `""` → only the original name;
- original title differs from the English one (a foreign film) → three names;
- `find` with empty `movie_results` → null.

`TitlesTest.kt`: TMDB failure falls back to the Cinemeta fake; cache key
changes with the language list.

`TitleMatcherTest.kt`: add a case where only the second name matches.

## Done when

- Tests pass.
- On the test container with `HLESS_LANGS=de,en`, a real key and a
  German-language provider installed,
  `curl -s http://127.0.0.1:7000/stream/movie/tt0111161.json` returns streams
  from that provider, and returns none from it with `HLESS_TMDB_KEY` unset.
