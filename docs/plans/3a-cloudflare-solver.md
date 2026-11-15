# 3a. Cloudflare solver bridge

Read [README.md](README.md) first. Depends on: phase 2 working. Do this only
when a provider you want fails behind a challenge.

## How the engine handles a challenge (already there)

When a provider's request gets a Cloudflare challenge (HTTP 403 or 503 with
challenge markers), the engine parks it for up to 90 seconds and publishes a
task. Whoever solves it posts cookies back; the engine retries the request
once with them and reuses them for that origin for 5 minutes.

| Call | Shape |
| :--- | :--- |
| `GET /solve/pending?waitMs=25000` | `[{"taskId","targetUrl","ageMs","userAgent"}]`; waits up to `waitMs` for a task |
| `POST /solve-callback` | body `{"taskId","targetUrl","cookies"}`; `cookies` is one `Cookie` header value, `name=value; name2=value2` |

`userAgent` is the User-Agent of the parked request. Cloudflare ties the
clearance cookie to the User-Agent and IP that earned it.

## Goal

A loop inside our process that feeds those tasks to a FlareSolverr-compatible
service (FlareSolverr or Byparr) running as another container.

## Config

| Variable | Default | Meaning |
| :--- | :--- | :--- |
| `HLESS_SOLVER_URL` | none | e.g. `http://flaresolverr:8191`. Unset disables the loop |
| `HLESS_SOLVER_TIMEOUT_MS` | `60000` | `maxTimeout` sent to the solver |

## Files

- `engine/src/main/kotlin/com/lagradost/hless/SolverLoop.kt`
- two methods on `EngineClient`: `pendingSolves(waitMs)` and
  `completeSolve(taskId, targetUrl, cookies)`

## Loop

Started from `HlessMain` after the engine is ready, only when
`HLESS_SOLVER_URL` is set. Forever:

1. `tasks = client.pendingSolves(25_000)`. On error: wait 5 s, continue.
2. For each task not already in progress (keep a set of `taskId`), launch a
   coroutine, at most 2 at a time:
   1. `POST <solver>/v1` with JSON
      `{"cmd":"request.get","url":<targetUrl>,"maxTimeout":<timeout>}`.
   2. On `status == "ok"`: join `solution.cookies` as
      `name=value; name=value` and call `completeSolve`.
   3. Otherwise log the solver's `message` and do nothing; the engine's task
      expires by itself.
   4. Remove the `taskId` from the set.

## The open problem: User-Agent

The solver answers with its own browser's User-Agent in
`solution.userAgent`. The engine retries with the **original** request's
User-Agent (`task.userAgent`). If they differ, Cloudflare rejects the cookie.
Current FlareSolverr versions ignore a requested User-Agent.

Work through these in order and stop at the first that works:

1. Try it as is against one challenged provider. Some sites do not check.
2. See whether the solver in use accepts a User-Agent per request (Byparr and
   forks differ; read its README). If so, send `task.userAgent`.
3. Make the engine use the solver's User-Agent for that origin on the retry.
   This needs a change in `engine/.../macos/SolveInterceptor.kt`, an upstream
   file. Keep it to the smallest possible diff: an optional field
   `userAgent` on `SolveCallbackRequestDto`, carried to the retry request.
   Record the change in `docs/plans/README.md` under "Local changes to
   upstream files".

## Also required

The solver, this container and MediaFlow must reach the internet through the
same public IP, or the cookie and the stream links are useless.

## Done when

A provider that failed with `remote_http_failure` (403/503) in the engine log
returns results after the solver container is added, and the log shows
`solve.completed`.
