# 3d. Anime (Kitsu IDs)

Read [README.md](README.md) first. Depends on: 2e, 2f. Optional.

## Problem

For anime, Stremio's catalogs usually hand out Kitsu IDs instead of IMDb
IDs, and count episodes from 1 within one Kitsu entry, without a season:

```
kitsu:1          a film, or a whole entry
kitsu:1:5        episode 5 of that entry
```

One Kitsu entry is one season or one film. A second season is a different
Kitsu entry with its own ID and its own title ("… Season 2", "… 2nd
Season").

## Goal

Answer stream requests for `kitsu:` IDs from providers that carry anime.

## Manifest changes (2c)

- `idPrefixes`: add `"kitsu:"`
- `types`: add `"anime"`

Stream requests then arrive with type `movie`, `series` or `anime`. Treat
`anime` as `series` when the ID has an episode and as `movie` when it has
none.

## Files

Under `engine/src/main/kotlin/com/lagradost/hless/`:

| File | Contents |
| :--- | :--- |
| `Kitsu.kt` | lookup of one entry |
| changes in `StremioId.kt`, `TitleMatcher.kt`, `Resolver.kt`, `StreamServiceImpl.kt` | below |

## StremioId

Add a field and a case:

```kotlin
data class StremioId(val imdb: String?, val kitsu: String?, val season: Int?, val episode: Int?)
```

- `kitsu:<digits>` → `kitsu` set, `season` and `episode` null.
- `kitsu:<digits>:<digits>` → `kitsu` set, `season` null, `episode` set.

## Kitsu lookup

`GET https://kitsu.io/api/edge/anime/<id>`, timeout 5 s, header
`Accept: application/vnd.api+json`. Checked against the live API on
2026-11-15. Read from `data.attributes`:

| Field | Example | Use |
| :--- | :--- | :--- |
| `titles.en` | `"Cowboy Bebop"` | name |
| `titles.en_jp` | `"Cowboy Bebop"` | name (romanised Japanese) |
| `canonicalTitle` | `"Cowboy Bebop"` | name |
| `subtype` | `TV`, `movie`, `OVA`, `ONA`, `special` | movie or series |
| `startDate` | `"1998-04-03"` | year |
| `episodeCount` | `26` | sanity check |

`TitleInfo.names` = `titles.en`, `titles.en_jp`, `canonicalTitle`, without
nulls and duplicates (by `normalize`). Cache on disk like 2f, key
`"kitsu|<id>"`.

Translated titles for other languages are not available here. Providers in
other languages usually list anime under the romanised or English name, so
this is acceptable.

## Which providers

Only providers whose `tvTypes` contain `Anime`, `AnimeMovie` or `OVA`.

`wantedTypes` for a Kitsu ID: with an episode → `Anime`, `OVA`, `TvSeries`,
`Cartoon`; without → `AnimeMovie`, `Movie`, `Anime`, `OVA`.

## Picking the episode

New pure function in `TitleMatcher.kt`:

`fun pickAnimeEpisode(details: DetailsDto, entryName: String, episode: Int): EpisodeDto?`

Episodes with `season == 0` are specials: ignore them throughout. Let
`seasons` be the distinct non-null seasons of the remaining episodes.

1. `seasons` has at most one value: the episode with `episode == episode`.
2. Several seasons, and `entryName` ends in a season marker: use that season
   and the episode with `episode == episode` in it. Season markers,
   case-insensitive, at the end of the name:
   `Season <n>`, `<n>nd Season` / `<n>rd Season` / `<n>th Season`,
   `Part <n>`, a trailing ` <n>` with 2 ≤ n ≤ 9, and the roman numerals
   ` II`, ` III`, ` IV`.
3. Several seasons and no marker: assume the entry is the first season; the
   episode with `season == min(seasons)` and `episode == episode`.
4. Nothing found by 1–3: absolute numbering. Sort all episodes by
   `(season, episode)` and take the one at position `episode` (1-based).
5. Sub and dub variants of the same episode: prefer `audio` equal to
   `HLESS_ANIME_AUDIO` (`sub` or `dub`, default `sub`), then null, then the
   first.

Step 2 uses the name of the provider's matched result as well: if the
matched result's own name carries a season marker (the provider lists each
season as its own title), skip steps 2–4 and use rule 1.

Known to be wrong sometimes, accept it: shows that Kitsu splits into parts
but the provider counts through, and the reverse. Do not add special cases.

## Resolver and service

- `StreamServiceImpl.streams`: for a Kitsu ID use `Kitsu.lookup` instead of
  `TitleSource.lookup`; everything after is the same.
- `MatchCache` key: `"kitsu:<id>|<providerId>"`.
- `Resolver`: movie → `details.movieData`, or the only episode if the
  provider models films as one episode; series → `pickAnimeEpisode`.

## Tests

`StremioIdTest.kt`: both Kitsu forms. `KitsuTest.kt`: parsing from a fixture
string. `TitleMatcherTest.kt`: `pickAnimeEpisode` rules 1–5, one test each,
with hand-built `DetailsDto` values.

## Done when

Tests pass, and with an anime provider installed a `kitsu:` episode plays
from Stremio's anime catalog. This has not been tried against a real anime
provider; write what you observe under "Findings" at the bottom of this
file.
