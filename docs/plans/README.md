# Work plans

Each file here is one unit of work, written so it can be done without reading
the others, except this page. [../PLAN.md](../PLAN.md) is the overview.

## Order

| # | Plan | Needs | State |
| :--- | :--- | :--- | :--- |
| 1 | [1-container.md](1-container.md) | nothing | done, except creating the LXC on Proxmox |
| 2a | [2a-launcher-and-engine-client.md](2a-launcher-and-engine-client.md) | 1 | done |
| 2b | [2b-bootstrap-repos.md](2b-bootstrap-repos.md) | 2a | done |
| 2c | [2c-addon-server.md](2c-addon-server.md) | 2a | done |
| 2d | [2d-title-resolution.md](2d-title-resolution.md) | 2a | done |
| 2e | [2e-streams.md](2e-streams.md) | 2a, 2c, 2d | done |
| 2f | [2f-title-aliases.md](2f-title-aliases.md) | 2d | done |
| 2g | [2g-stream-ordering.md](2g-stream-ordering.md) | 2e | done |
| 3a | [3a-cloudflare-solver.md](3a-cloudflare-solver.md) | 2e | optional; implemented, live check pending a challenged provider |
| 3b | [3b-catalogs.md](3b-catalogs.md) | 2e | done |
| 3c | [3c-compat-report.md](3c-compat-report.md) | 1 | done |
| 3d | [3d-anime-kitsu.md](3d-anime-kitsu.md) | 2e, 2f | done |
| 3e | [3e-webview-extractors.md](3e-webview-extractors.md) | 2e | not needed (step 0: no web-view usage in the installed extensions) |
| 4a | [4a-upstream-sync.md](4a-upstream-sync.md) | nothing | done, except the first workflow run on GitHub |

2b, 2c and 2d do not depend on each other. 2f changes parts of 2d, so build
them together or one right after the other. When you finish a plan, change
its state here to `done` in the same commit.

Suggested order for a first usable version: 2a, 2c, 2d + 2f, 2e, 2g, 2b.
Without 2f, providers that are not in English find almost nothing.

Environment variables are defined in the plan that introduces them; `Config`
(2a) holds all of them. Full list: 2a (core), 2f (`HLESS_TMDB_KEY`,
`HLESS_LANGS`), 2g (ordering and provider filters), 3a (solver), 3d
(`HLESS_ANIME_AUDIO`), 3e (`HLESS_BROWSER_URL`).

## Rules for every plan

1. **New code lives in `engine/src/main/kotlin/com/lagradost/hless/`**, tests
   in `engine/src/test/kotlin/com/lagradost/hless/`. It is the same Gradle
   module and the same jar as the upstream engine, in its own package.
2. **Do not edit upstream files**: everything under `library/`, and
   everything under `engine/` outside the two `hless` directories. Upstream
   fixes are merged regularly and edits there cause conflicts.
   Permitted exceptions, both in `engine/build.gradle.kts`: adding a
   `testImplementation` line, and nothing else without writing it down under
   "Local changes to upstream files" below.
3. **Talk to the engine over its HTTP API** on `127.0.0.1`
   ([../ipc-api.md](../ipc-api.md)), through `EngineClient`. Do not call the
   engine's Kotlin classes directly, except `com.lagradost.macos.main` to
   start it and the DTO classes in `com.lagradost.macos.models` to decode
   its answers. (Plan 3e adds one more exception; it says so itself.)
4. **No new dependencies** unless a plan names them. Already available:
   Ktor server (Netty), OkHttp, kotlinx.serialization, kotlinx.coroutines,
   fuzzywuzzy, `kotlin.test`.
5. **Never log or print** the engine token, the MediaFlow password, stream
   URLs or request headers. Log provider names, counts, codes and timings.
6. **Never commit** repository URLs, extension names or site names. Use
   placeholders in examples and tests.
7. Kotlin style follows the surrounding code: 4 spaces, no wildcard imports.
8. A plan is done only when its "Done when" section holds. If a step cannot
   be done as written, stop and write what you found at the bottom of that
   plan under a heading "Findings" instead of improvising a different design.

## Build and test

JDK 17 exactly: the engine uses `jdk.incubator.foreign`, which later JDKs do
not have.

```bash
./gradlew :engine:test --tests 'com.lagradost.hless.*' --console=plain
```

```bash
./gradlew :engine:jar --console=plain
```

The jar is `engine/build/libs/daemon.jar`. How to run it is in
[1-container.md](1-container.md).

## Local changes to upstream files

- `engine/build.gradle.kts`: test dependencies for the addon server route
  tests (2b).
