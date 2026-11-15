# 3b. Provider catalogs in Stremio

Read [README.md](README.md) first. Depends on: phase 2 working. Optional.

## Goal

Show each provider's home page rows as Stremio catalogs, and let titles that
have no IMDb ID (many anime and regional titles) be opened and played.

## IDs

Our own ID prefix, so Stremio routes these to us:

```
hless:<base64url(providerId)>:<base64url(url)>                 a title
hless:<base64url(providerId)>:<base64url(url)>:<season>:<episode>   an episode
```

Base64url without padding. Put encoding and decoding in `HlessId.kt` with
tests for round trips, including URLs containing `:` and `/`.

## Engine calls to add to EngineClient

| Method | Request | Returns |
| :--- | :--- | :--- |
| `home(providerId, page)` | `GET /home?plugin=<providerId>&page=<n>` | home page DTO: named rows of `SearchResultDto` |

Read the exact DTO in
`engine/src/main/kotlin/com/lagradost/macos/models/MediaDtos.kt` before
coding.

## Manifest changes (2c)

- `resources`: `["stream", "catalog", "meta"]`
- `idPrefixes`: `["tt", "hless:"]`
- `catalogs`: one per enabled provider with `hasMainPage == true`, for each of
  `movie` and `series` that the provider's `tvTypes` cover:
  `{"type": "movie", "id": "hless-<base64url(providerId)>", "name": "<provider name>", "extra": [{"name": "search"}, {"name": "skip"}]}`

Build the manifest at request time from `client.providers()`; cache it for 60
seconds.

## Routes to add

| Route | Behaviour |
| :--- | :--- |
| `GET P/catalog/{type}/{id}.json` | First page of `home`: all rows flattened, filtered by type |
| `GET P/catalog/{type}/{id}/search={q}.json` | `client.search(providerId, q)`, filtered by type |
| `GET P/catalog/{type}/{id}/skip={n}.json` | `home` page `n / 50 + 1`; empty list when the engine returns nothing |
| `GET P/meta/{type}/{id}.json` | `client.details`, mapped as below |

Catalog item: `{"id": <hless id>, "type": <type>, "name": name, "poster": posterUrl}`.

Meta: `{"id", "type", "name", "poster", "background": backgroundPosterUrl, "description": plot, "releaseInfo": year, "genres": tags, "videos": [...]}`.
For a series each episode becomes
`{"id": "<title id>:<season ?: 1>:<episode>", "title": name, "season": season ?: 1, "episode": episode, "overview": description, "thumbnail": posterUrl}`.
Skip episodes without an episode number.

## Stream route

In `StreamServiceImpl.streams`, an ID starting with `hless:` skips Cinemeta,
search and matching: decode it, call `details` on that one provider, pick
`movieData` or the episode, then `links` as usual.

## Done when

A provider's catalog appears on Stremio's Discover page, a title opens with
its episode list, and an episode plays.
