# 7a. A first answer that holds what matters

Read [README.md](README.md) first. Depends on: 5c, 6a. **State: done 2026-11-18.**
Built after the maintainer compared the addon with another bridge on two
films and found that reopening a title often showed more streams.

## What was wrong

Hless answered before the useful providers were done; they kept working in the
background and their streams showed up on the next open. The service log
(`journalctl -u hless`) showed three causes, one on top of the other:

1. **The engine has 8 search places** (`ProviderExecutor.searchPermits`,
   `engine/.../macos/ProviderExecutor.kt`) and queues the rest in no useful
   order. Hless started all ~23 providers at once, so the news and TV
   providers that never have a film took places from the ones that do.
2. **Providers behind a Cloudflare challenge hold a place for up to 90 s.**
   The engine parks their request and waits for the solver; each title adds
   two of them (Kinoger, Moflix). After four titles in quick succession all 8
   places were held and *nothing* answered (`0/24 providers answered`).
3. **The solver was unreachable.** The LXC's resolver had been changed to a
   public one that does not know `*.pve.lan`, so `solverr.pve.lan` failed with
   `UnknownHostException`. Use the solver's IP address in `HLESS_SOLVER_URL`.
   (The same applies to `HLESS_MEDIAFLOW_URL`, but only for the wake-up call
   from the server; players resolve it themselves.)

The resolution filter was not a cause: it never hides unknown resolutions
(`rank()` drops a stream only for a *known* height outside the range).

## What was built

- **`ProviderStats`**: per provider, hits, misses and failures of the title
  search; classes LIKELY (a hit in the last 30 days), UNKNOWN, DORMANT (ten
  tries, no hit). Also counts the hosters (`link.source`) seen. File
  `<data>/hless/stats.json`.
- **Search order.** Providers start in the order LIKELY, UNKNOWN, DORMANT (more
  hits first, then the person's order). A fair gate of 6 searches keeps the
  engine's queue empty, so that order is the order they search in; a search
  holds its place for at most 8 s. DORMANT providers start only after all the
  others are through their search.
- **What the answer waits for.** The relevant providers: all but the dormant
  ones. It answers when they are all done, or at the deadline, or (after the
  minimum wait) when nothing has happened for the quiet period *and* no LIKELY
  provider or one that already found the title is still working.
- **Providers whose site the solver cannot pass are not asked** while that
  lasts (`BlockedHosts`). A host is held back as soon as a challenge parks for
  it, for 10 minutes after a refusal, 2 minutes if the solver is unreachable.
- **The engine is told to give up.** The engine only accepts cookies. When the
  solver refused a host (or it is in cooldown), the loop completes the parked
  task with a throw-away cookie. The engine retries, still sees the
  challenge, and then refuses to park that site for ten minutes, so the
  provider fails at once instead of after 90 s.
- **Solver cookies are filtered** to the challenged site and to what the engine
  accepts. The solver's browser returns every cookie it holds; the engine
  refused the whole header (`IllegalArgumentException` in the callback) when it
  held a bad or too long one, so a solved challenge was lost.
- **A note row** at the end of a partial answer ("N sources still loading,
  reopen in a few seconds", and what the resolution or hoster filter hid),
  linking to the configuration page. Switch: `hint`, `HLESS_HINT`.
- **Prefetch.** After an episode, the providers that delivered it start on the
  next one.
- **Branding.** Addon name `Hless`, an icon (`/logo.png`, a headless stick
  figure drawn with Java2D, public even when a secret is set), the first line
  of every row is `大 Hless` (`HLESS_LABEL`), and the bare address opens the
  configuration page.
- **Configuration page.** Arrows on every provider; "Order providers by how
  often they find something" (on: the queue above, off: exactly the page
  order); per provider `hits/tries`; a collapsed "Hosters" list to switch
  hosters off (`hostOff`, `DropReason.HOSTER_OFF`).
- JVM name cache: `-Dsun.net.inetaddr.ttl=600`.

## Measured on the test container

Eight titles in a row, cold, with the solver reachable:

| | Before | After |
| :--- | :--- | :--- |
| Titles 5 to 8 | 0 providers answered, 15 s | 4.6 to 5.9 s, complete |
| Titles 1 to 4 | 6 to 10 s, partial | 4.4 to 8.4 s |

Two films that the other bridge also answered: the same four providers
(Kellerkino, Filmpalast, EinschaltenIn, Megakino) delivered; the other bridge
lists the variants of a master as separate rows (12 against our 9 for one film);
`variants = all` on the configuration page does the same.

## Left as it is

- A cold, popular title still takes up to 15 s when a provider that usually
  delivers is slow (search, link extraction and probes). The deadline is
  `HLESS_DEADLINE_MS`.
- The engine's own wait for challenged requests cannot be changed from here
  (it is an upstream file); the give-up cookie above is the only lever.
