# 6a. Configuration page (later)

Read [README.md](README.md) first. Depends on: 2c, 2g, 5a, 5b, 5c. **Do not
start before those are done**: this plan moves their settings from process
start to every request, so it should change finished code once, not code that
is still moving.

**State: done 2026-11-18** (`Prefs.kt`, `Settings.kt`, `AddonServer.kt`,
`engine/src/main/resources/hless/configure.html`; tests `PrefsTest`,
`AddonServerTest`, `StreamServiceImplTest`). Checked in a browser against the
test container: the page loads, the generated link decodes to the chosen
preferences, the server accepts it, and a provider switched off disappears
from that link's catalogs. Differences from the text below: `Settings` wraps
`RankSettings`, `DisplaySettings`, `Variants` and `proxy`; `/api/server` also
returns `denied` (providers the server forbids; they are not offered on the
page); the proxy switch only shows when the server has MediaFlow.

## What the maintainer asked for

A small page, like most Stremio addons have: big server-wide things (the
repository list, keys, MediaFlow) stay in environment variables with defaults;
in the page a person filters resolution, switches providers on and off, and
changes the sorting. Each person's choices travel inside the addon URL they
install.

## How Stremio does this

A manifest with `behaviorHints.configurable = true` makes Stremio show a
"Configure" button that opens `<addon base>/configure` in a browser. The
page has no storage of its own: it builds a new addon URL that carries the
chosen settings, and the person installs that URL. The server reads the
settings from the URL on every request.

## Two kinds of settings

| Kind | Where | Examples |
| :--- | :--- | :--- |
| Server | environment variables only, never in the page or in a URL | repositories, plugin list, TMDB key, MediaFlow URL and password, solver URL, secret, ports, deadlines, TTLs |
| Preferences | the page, carried in the URL | below |

Preferences (each optional; missing means "use the server default", which is
today's `HLESS_*` value):

| Key | Type | Meaning |
| :--- | :--- | :--- |
| `langs` | list of language codes | Preferred order (`de,en`) |
| `minH` | Int | Hide streams below this height (unknown height is kept) |
| `maxH` | Int | Hide streams above this height (bandwidth saving); unknown kept |
| `off` | list of provider names | Providers never asked |
| `first` | list of provider names | Providers listed first, in this order |
| `sort` | `quality` \| `lang` \| `provider` \| `size` | Primary sort order |
| `perProv` | Int 1–20 | Cap per provider |
| `total` | Int 1–100 | Cap in total |
| `variants` | `fold` \| `all` | 5b folding |
| `flags` | `emoji` \| `code` \| `none` | 5a |
| `icons` | Boolean | 5a |
| `proxy` | Boolean | Wrap in MediaFlow. Ignored when the server has none |

Never put a secret in preferences. The MediaFlow password and the TMDB key
are not preferences and must not appear in the page, in any API answer, or in
a generated URL.

## URL layout

```
<base>/<secret?>/c~<base64url(json)>/manifest.json
<base>/<secret?>/c~<base64url(json)>/stream/<type>/<id>.json
<base>/<secret?>/configure
<base>/<secret?>/c~<base64url(json)>/configure      (opens with those values filled in)
<base>/<secret?>/api/providers
```

- `c~` is a fixed marker, `json` is UTF-8, base64url without padding.
  Missing segment = all defaults, so existing installed URLs keep working.
- The decoded JSON is at most 2 KiB; larger or invalid input is ignored (the
  defaults apply), never an error page. Unknown keys are ignored. Numbers are
  clamped to their range. Strings in lists are cut to 40 characters, lists to
  60 entries.

## Files

Under `engine/src/main/kotlin/com/lagradost/hless/`, and one resource file:

| File | Contents |
| :--- | :--- |
| `Prefs.kt` | `@Serializable data class Prefs`, `decodePrefs(segment)`, `encodePrefs`, `Prefs.resolve(defaults): Settings` |
| `AddonServer.kt` | route changes below |
| `engine/src/main/resources/configure.html` | the page: one file, vanilla JavaScript, no external requests, no CDN, no build step |

`Settings` is the resolved, validated value used by ranking and by the
service; it replaces reading `Config.*` for the settings above.

## From global to per request

Today `StreamServiceImpl` takes one `RankSettings` at construction and keeps
one cache entry per title containing the results of the providers it chose at
that moment. With per-request preferences two people can ask for the same
title with different provider sets, so:

1. **Cache per provider, not per title.** Key `type/id|providerId`, value
   `ProviderResult(candidates, state)` with its own TTL (5 minutes with
   streams, 30 seconds without; 5c). A request asks the cache for each
   provider it wants; a missing or expired one starts the work, and two
   requests wanting the same one share the same in-flight work (store a
   `Deferred`).
2. **Rank per request.** `rank(candidates, settings)` is already a pure
   function: call it with the request's `Settings`.
3. **Answer early per request** (5c): the "quiet" rule watches only the
   providers this request wants.
4. `askProvider(name, settings)` uses the request's `off` list.

`StreamService.streams(type, id, settings: Settings)`.

## Routes

- Wrap the existing routes in a helper that reads the optional `c~…` segment
  and passes `Settings` down.
- `GET …/manifest.json`: add `behaviorHints.configurable = true` and
  `configurationRequired = false`.
- `GET …/configure`: the HTML page (`text/html`, `Cache-Control: no-store`).
  Add the header
  `Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:`.
  The script lives inline in the one file, so scripts need `'unsafe-inline'`;
  no other origin is allowed.
- `GET …/api/providers`: `[{"name","language","types":[…]}]` from
  `client.providers()` (names only, no provider IDs, no URLs, no keys),
  `Cache-Control: max-age=60`.
- `GET …/api/server`: `{"mediaflow": true|false, "defaults": {…preference defaults…}}`
  so the page knows whether to offer the `proxy` switch and which values to
  show initially. Nothing else.

## The page

One screen, plain HTML and CSS that follows the system light or dark theme
(`prefers-color-scheme`), phone width friendly (16 px side margins, no
horizontal scroll).

Sections, top to bottom:

1. **Languages**: an ordered list with up/down buttons and a field to add a
   code. Initial order from the defaults.
2. **Resolution**: two selects, `at least` and `at most`, with
   `any, 360, 480, 720, 1080, 2160`.
3. **Providers**: one row per provider from `/api/providers` with a switch
   (on = asked) and up/down buttons for the "first" order. Group by language
   when there are more than ten.
4. **Sorting and limits**: `sort` select, `perProv` and `total` number fields.
5. **Appearance**: `variants`, `flags`, `icons`.
6. **Proxy**: the `proxy` switch, only when `/api/server` says MediaFlow is
   configured.
7. **Install**: a read-only box with the resulting addon URL
   (`window.location.origin` plus the path, or `HLESS_PUBLIC_URL` when the
   server sends it in `/api/server`), a "Copy" button, and an "Open in
   Stremio" link that replaces `https://` by `stremio://`.

The URL box updates on every change. Opening `…/c~<cfg>/configure` fills the
controls from that config. Only differences from the defaults are written
into the JSON, so URLs stay short.

Use `textContent`, never `innerHTML`, for anything that comes from the
server (provider names).

## Tests

- `PrefsTest.kt`: round trip; oversize, invalid base64, invalid JSON, wrong
  types → defaults; clamping; unknown keys ignored; a secret-looking key is
  just ignored.
- `AddonServerTest.kt`: `c~…` segment reaches the service as `Settings`;
  manifest says `configurable`; `/api/providers` contains names only;
  `/api/server` never contains the password or the TMDB key (assert on the
  full response text with the fake values set); with a secret configured, all
  new routes are 404 without it.
- `StreamServiceImplTest.kt`: two requests for one title with different
  `off` lists make one `links` call per provider in total; the request with
  more providers waits for the extra one.
- A script-free check of `configure.html`: it is served, contains no
  `http://` or `https://` references (grep in the test).

## Done when

Tests pass. On the test container: open `/configure`, switch one provider
off, set "at least 720", copy the URL, install it in Stremio, and see the
change in the stream list; the server log shows one fan-out for the title,
not one per setting.

## Not in this plan

Managing repositories or extensions from the page, per-title overrides,
accounts, and any setting that needs a secret. Those need a real
authentication story first.

## Review fixes (2026-11-18)

A review of the whole addon found, and these commits fixed:

- **Ids are signed.** An `hless:` id carries a provider and a page URL that goes
  to the extension. It now ends in an HMAC (key in `<data>/hless/id.key`,
  created once, mode 0600), and an id without a valid signature is refused. Ids
  from before this change no longer work.
- **Startup.** The addon server starts as soon as the engine answers;
  bootstrap and the solver loop run in their own scope (a coroutine started in
  `runBlocking` could never run again once the server blocked that thread).
  `/healthz` answers 503 while the engine is not ready.
- **Extension updates are turned on** at bootstrap (`/extensions/settings`),
  because the engine ships with them off.
- **A title found by TMDB is cached; one that fell back to Cinemeta because TMDB
  failed is not.**
- **A slow empty search is not remembered as "not there"** (the engine answers
  `[]` for a failed search too); real misses are kept 10 minutes, not an hour.
- **Fuzzy title matches need the same numbers** ("Spider-Man" no longer matches
  "Spider-Man 2" when the provider gives no year).
- **Finding the title takes no semaphore place**, only link extraction does;
  at most 150 provider tasks run and 1000 finished ones are kept.
- **Folding keeps a link whose quality the master does not have.**
- **The cache files drop entries older than 31 days and are written on a
  background thread.**
