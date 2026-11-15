# 3c. Compatibility report

Read [README.md](README.md) first. Depends on: phase 1. Optional but useful
before reporting "extension X does not work".

## Goal

One command that says, for every installed provider, how far it gets:
search → details → links.

`scripts/catalog_compat_run.py` does this upstream, but it expects the
packaged Mac app in `dist/`. Do not try to run it. Write a small new script
instead and use the old one only as a reference for what to record.

## File to create

`scripts/compat_report.py`, Python 3 standard library only.

```
python3 scripts/compat_report.py --engine http://127.0.0.1:17171 --token <token> [--query "Inception"] [--out report.md]
```

Getting a token: start the engine by itself with a known one, see
[1-container.md](1-container.md) "Run the engine alone".

## Behaviour

1. `GET /providers` → list.
2. For each provider, one at a time:
   1. `GET /search?q=<query>&plugins=<providerId>` (timeout 30 s). Record
      result count.
   2. No results → record `search: empty`, next provider.
   3. `GET /details?plugin=<providerId>&url=<first result url>`.
   4. Take `movieData`, or the first episode's `data`.
   5. `POST /links` with `{"plugin","data","budgetMs":30000}`. Record link
      count and the count per `type`.
3. On a non-2xx answer record `failure.code` (or `code`) and `remoteStatus`
   if present, then go to the next provider. Never print URLs or the token.
4. Write a Markdown table: provider, search, details, links, failure code.
   Print a one-line summary: how many providers reached each stage.

Failure codes are explained in [../ipc-api.md](../ipc-api.md) under "Media
operation failures". The most telling ones:

| Code | Meaning for us |
| :--- | :--- |
| `missing_host_class`, `missing_host_member` | The extension uses an Android class the engine does not stub. Upstream may fix it; check after merging upstream |
| `remote_http_failure` with 403/503 | Probably Cloudflare; see 3a |
| `dns_failure`, `connection_reset` | The site is blocked or gone from this network |
| `provider_no_data` | The site changed; the extension's author has to fix it |

## State

Done 2026-11-15: `scripts/compat_report.py` exists and was run on the test
container. Results are in [1-container.md](1-container.md) under Findings.

## Done when

Running it on the test container produces the table for the installed
extensions.
