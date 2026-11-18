# 5a. Stream display

Read [README.md](README.md) first. Depends on: 2e, 2g. Best together with
[5b](5b-link-probing.md) (real resolutions, sizes), but every rule here works
without it: pass `facts = null` until 5b exists.

## Why this plan exists

Checked on 2026-11-18 against the running addon (film with five German
providers) and screenshots next to another bridge's list:

1. **`400p` is shown for 9 of 15 links.** 400 is not a resolution. It is the
   CloudStream library's `Qualities.Unknown`
   (`library/.../utils/ExtractorApi.kt`, `Unknown(400, …)`). Probing those same
   links showed they really are 720p, 720p, 800p, and so on.
2. **The name is one wrapping line** (`CS 🇩🇪 1080p`). Stremio's name column is
   narrow and breaks it badly. The provider, the most useful part, is not in
   the name at all.
3. **The description repeats itself** (`Kellerkino · DoodStream` then
   `DoodStream`) and says nothing about the title, the stream type, the size
   or the bitrate.
4. **The flag is wrong for many languages.** `flagOf` turns a language code
   into a country flag by treating it as a country code: `ja` gives 🇯🇦 (no such
   flag; Japan is `JP`), and `ko`, `zh`, `cs`, `da`, `el`, `sv`, `uk`, `he`
   are wrong the same way. Also, it is the *provider's* language, not proof of
   the audio language.

## Layout facts

From the maintainer's screenshots (devices differ, so keep this
conservative): the name column is about 13 characters wide and breaks at line
breaks; the description is about 28–30 characters wide per line, longer lines
are **cut off, not wrapped**, and about four lines are visible. So: three
short lines in the name, and the most important words at the start of each
description line.

## Constants and helpers

In `engine/src/main/kotlin/com/lagradost/hless/StreamDisplay.kt`:

```kotlin
const val UNKNOWN_QUALITY = 400   // Qualities.Unknown in the library

/** Height in pixels, or null when the link does not say. 0, negatives and 400 mean unknown. */
fun knownHeight(quality: Int): Int? = quality.takeIf { it > 0 && it != UNKNOWN_QUALITY }
```

Replace every direct use of `link.quality` / `Candidate.quality` for display,
sorting or filtering by `knownHeight(...)`:

- `StreamMapper`: no more `"${link.quality}p"`.
- `StreamRanker.rank`: sort by `knownHeight(it.quality) ?: 0` descending
  (unknown last); `minQuality` drops a stream only when the height is known
  and below the minimum.
- `bingeGroup` in `StreamMapper`: use `knownHeight(...) ?: 0`, not the raw
  number.

## Config (add to `Config`, plan 2a)

| Variable | Default | Meaning |
| :--- | :--- | :--- |
| `HLESS_LABEL` | `hless` | First line of every stream name |
| `HLESS_FLAGS` | `emoji` | `emoji`, `code` (upper-case language code, e.g. `DE`) or `none`. Some systems (Windows desktop) draw flag emoji as two letters; then use `code` |
| `HLESS_ICONS` | `true` | Emoji icons in front of lines. `false` for plain text |

## The format

### Name: three lines, separated by `\n`

```
<label>
<icon><quality><flag>
<provider>
```

- `icon` is `🎬 ` when icons are on, else empty.
- `quality`: the highest known height as `1080p`; `Auto` when none is known.
- `flag` is a space plus the provider language rendered per `HLESS_FLAGS`;
  nothing when the language is unknown or the mode is `none`.
- `provider` is the provider's `name`, cut to 14 characters.

### Description: up to five lines

| # | Line | Shown when |
| :--- | :--- | :--- |
| 1 | `🎬 <title>` | always |
| 2 | `📺 <qualities> • <type>` | always |
| 3 | `🔗 <provider> • <source>` | always |
| 4 | `💾 <size> · ~<mbps> Mbps` | size or bitrate known |
| 5 | `🔤 <subtitle languages>` | the response has subtitles |

Rules per line (icons dropped when `HLESS_ICONS=false`):

1. `title`: first name of `TitleInfo.names` plus ` (<year>)` when the year is
   known; for a series add ` S01E02` (two digits each) instead of the year.
   Cut to 40 characters with `…`.
2. `qualities`: all known heights, highest first, joined by ` · `
   (`800p · 720p · 480p`); `Auto` when none. `type`: `M3U8` → `HLS`,
   `DASH` → `DASH`, `VIDEO` → `Direct`.
3. `source`: omit ` • <source>` when it equals the provider name (ignoring
   case) or is blank. Never print the same word twice in a row.
4. Size: below 1 GB as `NNN MB`, otherwise `N.NN GB`; bitrate as `~N.N Mbps`
   (one decimal, dot). Join the parts that are known with ` · `. Do not
   print a line with nothing in it.
5. Subtitle languages: distinct, upper-case, at most five, joined by a
   space.

Also set `behaviorHints.videoSize` (bytes) when the size is known.

### Golden examples

These are real links from the maintainer's test repository (hosts and URLs
left out). Use them as test cases; film runtime 150 minutes.

Direct file, label 1080, 2.07 GB, provider language `de`:

```
name:
hless
🎬 1080p 🇩🇪
Kellerkino

description:
🎬 Spider-Man: Brand New Day (2026)
📺 1080p • Direct
🔗 Kellerkino • DoodStream
💾 2.07 GB · ~1.8 Mbps
```

HLS master with three variants, top bandwidth 2642 kbps:

```
name:
hless
🎬 800p 🇩🇪
Megakino

description:
🎬 Spider-Man: Brand New Day (2026)
📺 800p · 720p · 480p • HLS
🔗 Megakino • Voe
💾 ~2.6 Mbps
```

Direct file with unknown quality (`400`), 7.90 GB, source equals provider:

```
name:
hless
🎬 Auto 🇩🇪
FilmFrei24

description:
🎬 Spider-Man: Brand New Day (2026)
📺 Auto • Direct
🔗 FilmFrei24
💾 7.90 GB · ~7.0 Mbps
```

## Language to flag

Replace `flagOf`. An explicit table, nothing computed:

| Language | Flag | Language | Flag | Language | Flag |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `en` | 🇬🇧 | `de` | 🇩🇪 | `fr` | 🇫🇷 |
| `es` | 🇪🇸 | `it` | 🇮🇹 | `pt` | 🇵🇹 |
| `nl` | 🇳🇱 | `pl` | 🇵🇱 | `ru` | 🇷🇺 |
| `tr` | 🇹🇷 | `ja` | 🇯🇵 | `ko` | 🇰🇷 |
| `zh` | 🇨🇳 | `ar` | 🇸🇦 | `hi` | 🇮🇳 |
| `sv` | 🇸🇪 | `da` | 🇩🇰 | `no`, `nb` | 🇳🇴 |
| `fi` | 🇫🇮 | `cs` | 🇨🇿 | `el` | 🇬🇷 |
| `he` | 🇮🇱 | `uk` | 🇺🇦 | `hu` | 🇭🇺 |
| `ro` | 🇷🇴 | | | | |

A code that is not in the table is shown as its upper-case letters (as in
`code` mode), never as a guessed flag. Match case-insensitively and ignore a
region suffix (`pt-BR` → `pt`).

The same table renders subtitle languages in line 5 as upper-case codes, not
flags, to keep the line short.

## Tests

`StreamDisplayTest.kt`:

- `knownHeight`: 0, −1, 400 → null; 360, 800, 1080 → the value.
- the three golden examples, compared as exact strings;
- `HLESS_FLAGS=code` and `none`, `HLESS_ICONS=false`;
- a title of 60 characters is cut to 40 with `…`;
- series title `Game of Thrones S01E02`;
- `ja` → 🇯🇵, `xx` → `XX`, `pt-BR` → 🇵🇹;
- ranking: a link with quality 400 sorts after one with 360.

Update the existing `StreamMapperTest` expectations; do not delete tests.

## Done when

Tests pass, and the stream list for a film on the test container shows no
`400p`, three-line names and four-line descriptions as above. Compare with
`scripts/title_links.py --addon <url>` (see [5c](5c-coverage-and-latency.md)).
