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
| `HLESS_SOLVER_URL` | none | Base URL of the solver as reachable from this container, e.g. `http://10.0.0.5:8191`. Unset disables the loop |
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
      `{"cmd":"request.get","url":<targetUrl>,"maxTimeout":<timeout>,"userAgent":<task.userAgent>}`
      (see "User-Agent" below).
   2. On `status == "ok"`: join `solution.cookies` as
      `name=value; name=value` and call `completeSolve`.
   3. Otherwise log the solver's `message` and do nothing; the engine's task
      expires by itself.
   4. Remove the `taskId` from the set.

## User-Agent

Cloudflare rejects a clearance cookie presented with a different User-Agent
than the one that earned it. The engine retries with the **original**
request's User-Agent (`task.userAgent`), so the solver has to use that one.

The maintainer's solver is Solverr 1.8.0 (FlareSolverr-compatible `/v1`,
Camoufox Firefox). Checked on 2026-11-15: it accepts a `userAgent` field in
the request and reports it back in `solution.userAgent`. So always send it:

```json
{"cmd":"request.get","url":"<targetUrl>","maxTimeout":60000,"userAgent":"<task.userAgent>"}
```

Omit the field when `task.userAgent` is null. After each solve, compare
`solution.userAgent` with what was sent and log a warning if they differ.

Not checked: whether a real Cloudflare challenge passes when a Firefox-based
solver presents a non-Firefox User-Agent. If solves succeed but the engine's
retry is still challenged, the fallback is to make the engine use the
solver's own User-Agent for that origin on the retry. That needs a change in
`engine/.../macos/SolveInterceptor.kt`, an upstream file: keep it to the
smallest possible diff (an optional `userAgent` on
`SolveCallbackRequestDto`, carried to the retry) and record it in
`docs/plans/README.md` under "Local changes to upstream files".

Stock FlareSolverr ignores a requested User-Agent; with it, only the
fallback works.

## Also required

The solver, this container and MediaFlow must reach the internet through the
same public IP, or the cookie and the stream links are useless.

## Observed need

2026-11-15, test repository of 25 extensions: the engine parked 3 challenged
requests during one compatibility run and gave up on at least one of them
(`solve.unavailable`, HTTP 403). Two providers did not answer a search
within 30 seconds, which fits. Two of the 25 extensions reference the
Cloudflare helper class. So this plan is needed for a small part of that
repository, not for most of it.

Until the loop exists, a challenged provider costs up to 90 seconds of
waiting inside the engine. The per-provider timeout in 2e (45 s) bounds
that.

## Done when

A provider that failed with `remote_http_failure` (403/503) in the engine log
returns results after the solver container is added, and the log shows
`solve.completed`.

## Findings

2026-11-17: implemented and live-checked on the test container. With
`HLESS_SOLVER_URL` pointing at a Solverr 1.8.0 instance on the Proxmox LAN,
the loop starts after engine readiness and polls `/solve/pending` without
errors; the solver answers `/v1`. The full "Done when" has not occurred
yet: none of the installed providers hit a Cloudflare challenge on demand,
so no `solve.completed` has been logged.
