# cloudstream-hless

A headless CloudStream extension host that serves as a Stremio addon. It runs
community `.cs3` extensions on a plain JVM in a container and answers Stremio's
catalog, meta and stream requests from them.

**Status: not usable yet.** The engine is here and builds upstream; the Stremio
layer and the container image are not written. See [docs/PLAN.md](docs/PLAN.md).

This is a "works for me" project with minimal maintenance. Issues may go
unanswered; pull requests and forks are welcome.

## Origin and licence

This is a fork of [CloudStream for macOS](https://github.com/krishnasinghrao/cloudstream-macos)
with the macOS client, player, downloader and packaging removed. What remains is
its Kotlin engine (`engine/`) and the CloudStream extension library (`library/`).
The full upstream history is kept in this repository.

Licensed under GPLv3, like upstream; see [LICENSE](LICENSE).

It ships no sources, streams or providers. Everything it resolves comes from
third-party extensions you install yourself; see [DISCLAIMER.md](DISCLAIMER.md).

## Layout

| Path | What it is |
| :--- | :--- |
| `library/` | CloudStream extension API the plugins link against (upstream, keep unmodified) |
| `engine/` | Headless daemon: Android stubs, `.cs3` loading, repositories, search/details/links over HTTP |
| `docs/ipc-api.md` | The engine's HTTP contract |
| `docs/PLAN.md` | What is left to build |

## Build

Needs JDK 17.

```bash
./gradlew :engine:jar
```

The result is `engine/build/libs/daemon.jar`.
