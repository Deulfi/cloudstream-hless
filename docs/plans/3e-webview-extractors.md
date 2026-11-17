# 3e. Web-view extractors

Read [README.md](README.md) first. Depends on: 2e. Optional, and the largest
optional item. **Do step 0 first; stop there if nothing needs it.**

## What this is about

Some extensions cannot find their stream by plain HTTP. On Android they load
a page in a hidden browser (`WebViewResolver`), let its JavaScript run, and
watch the page's network requests until one matches a pattern (usually an
`.m3u8`). Without a browser, those extensions return no links.

The engine already turns each such request into a task for someone else to
run. On macOS the app does it with WebKit. Here nothing does.

## Step 0: find out whether you need it

On 2026-11-15 the 25 extensions of the maintainer's test repository were
scanned: none uses `WebViewResolver` or an Android `WebView`. Repeat the
scan for the repositories you actually use:

```python
# run on the machine that holds the engine's data directory
import os, zipfile, collections
needles = [b"WebViewResolver", b"android/webkit/WebView"]
hits = collections.Counter()
for dirpath, _, files in os.walk("/data"):          # the engine's data directory
    for name in files:
        path = os.path.join(dirpath, name)
        if not zipfile.is_zipfile(path): continue
        with zipfile.ZipFile(path) as z:
            blob = b"".join(z.read(n) for n in z.namelist() if n.endswith((".dex", ".class")))
        for needle in needles:
            if needle in blob: hits[needle.decode()] += 1
print(dict(hits))
```

Zero hits: stop, mark this plan "not needed" in the index. Hits only for
`android/webkit/WebView` (extensions that script a web view directly): that
is the `/webview/*` bridge, which this plan does not cover; stop and record
it under Findings.

## How the engine side works (read from the source, not yet exercised)

| Call | Shape |
| :--- | :--- |
| `GET /media/pending?waitMs=25000` | `[{"taskId","url","method","headers":{…},"userAgent","script","timeoutMs","ageMs"}]` |
| `POST /media/observe` | body `{"taskId","requests":[{"url","method"}]}` → `{"known": bool, "stop": bool}` |
| `POST /media/complete` | body `{"taskId","scriptResult","cookies":{"<host>":"<cookie header>"},"userAgent","pageUrl"}` |

The worker loads `url` in a real browser with the given headers and
User-Agent, reports every request the page makes through `/media/observe`
(in batches), and finishes with `/media/complete` when the engine answers
`stop: true` or `timeoutMs` has passed. If `script` is set, it is evaluated
in the page once it has loaded and its result goes into `scriptResult`. The
engine does the pattern matching; the worker only reports.

**In-process mode needs one extra call.** The engine wires this bridge up
only when it runs extensions in worker processes. In our mode nothing hands
`WebViewResolver` requests to the bridge. `HlessMain` must call, once, after
the engine is ready:

```kotlin
com.lagradost.macos.MediaResolveBridge.install()
```

This is a second permitted exception to rule 3 in the README (calling an
engine class directly). Add it to the README's list when you do it. Verify
it first: with the call in place and an extension that uses
`WebViewResolver`, a `/links` request must make a task appear in
`/media/pending`. If none appears, stop and write down what you see.

## Choosing the browser

Try A first; it needs no new container.

### A. The existing solver

The maintainer's solver (Solverr 1.8.0) accepts a `capture` field (a list of
strings) on `/v1` and `/scrape`, and its answers contain `extracted` and
`debug` objects. What `capture` does is **not known**. Find out:

1. Read the solver's documentation for `capture`.
2. Send a request for a page you know loads an `.m3u8`, with `capture` set
   to a pattern for it, and look for the captured request URLs in the
   answer.

If the answer lists the page's network requests: implement the worker as
"one solver call per task, then one `/media/observe` with all captured
requests, then `/media/complete`". Cookies come from `solution.cookies`,
grouped by `domain`. `script` cannot be supported this way; complete such
tasks with `scriptResult = null`.

If it does not: go to B.

### B. A headless Chromium container

Run a container that exposes the Chrome DevTools Protocol, for example
`chromedp/headless-shell` on port 9222, and drive it from Kotlin over a
WebSocket (OkHttp has a WebSocket client; no new dependency).

Config: `HLESS_BROWSER_URL` (e.g. `http://10.0.0.6:9222`); unset disables
the worker.

Per task:

1. `PUT <browser>/json/new?about:blank` → JSON with `webSocketDebuggerUrl`
   and `id`.
2. Open the WebSocket. Messages are JSON
   `{"id": n, "method": "...", "params": {...}}`; answers carry the same
   `id`; events have `method` and no `id`.
3. Send `Network.enable`, `Page.enable`,
   `Network.setUserAgentOverride {"userAgent": task.userAgent}` (if set),
   `Network.setExtraHTTPHeaders {"headers": task.headers}` (if not empty),
   then `Page.navigate {"url": task.url}`.
4. Collect `Network.requestWillBeSent` events
   (`params.request.url`, `params.request.method`). Every 300 ms, if any
   were collected, `POST /media/observe`. Stop when the answer has
   `stop: true` or `known: false`, or after `task.timeoutMs`.
5. If `task.script` is set: after `Page.loadEventFired`, send
   `Runtime.evaluate {"expression": task.script, "returnByValue": true, "awaitPromise": true}`
   and keep `result.result.value` as a string.
6. `Network.getAllCookies` → group by `domain` (strip a leading `.`), join
   each group as `name=value; name=value`.
7. `POST /media/complete`.
8. Always, also on errors: `GET <browser>/json/close/<id>`.

At most 2 tasks at a time. A task that fails is completed with empty values
so the engine does not wait out its timeout.

Files: `BrowserWorker.kt` (loop, like `SolverLoop` in 3a), `Cdp.kt` (the
WebSocket client), and `pendingMedia`, `observeMedia`, `completeMedia` on
`EngineClient`.

## Network

The browser, this container and MediaFlow must leave through the same public
IP, as in 3a.

## Tests

`CdpTest.kt`: request/response correlation by `id` and event dispatch,
against OkHttp's `MockWebServer` WebSocket if it is on the test classpath;
otherwise test the JSON framing as pure functions.

Grouping cookies by host: pure function, unit test.

## Done when

An extension that returned no links before returns links, and the engine log
shows the media task completed.

## Findings

2026-11-17: step 0 scan of the installed extensions found no
`WebViewResolver` or Android `WebView` usage. Nothing needs this plan.
