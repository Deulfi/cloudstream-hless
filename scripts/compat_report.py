#!/usr/bin/env python3
"""How far each installed provider gets: search -> details -> links.

    python3 scripts/compat_report.py --engine http://127.0.0.1:17171 --token TOKEN
        [--query "Inception"] [--out report.md] [--timeout 30]

Talks to a running engine (started on its own
alone). Standard library only. Never prints URLs, headers or the token.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


class EngineError(Exception):
    def __init__(self, status, code, remote):
        super().__init__(code or str(status))
        self.status, self.code, self.remote = status, code, remote


def call(base, token, path, params=None, body=None, timeout=30):
    url = base + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, headers={"Authorization": "Bearer " + token})
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        try:
            payload = json.loads(error.read().decode())
        except ValueError:
            payload = {}
        failure = payload.get("failure") or {}
        raise EngineError(error.code, failure.get("code") or payload.get("code"), failure.get("remoteStatus"))
    except (urllib.error.URLError, TimeoutError, OSError):
        raise EngineError(0, "client_timeout", None)


def describe(error):
    text = error.code or "http_%s" % error.status
    return "%s (%s)" % (text, error.remote) if error.remote else text


def check(base, token, provider, query, timeout):
    """Returns (search, details, links, failure) cells for one provider."""
    pid = provider["providerId"]
    try:
        results = call(base, token, "/search", {"q": query, "plugins": pid}, timeout=timeout)
    except EngineError as error:
        return "-", "-", "-", "search: " + describe(error)
    if not results:
        return "0", "-", "-", "search: empty"
    try:
        details = call(base, token, "/details", {"plugin": pid, "url": results[0]["url"]}, timeout=timeout)
    except EngineError as error:
        return str(len(results)), "-", "-", "details: " + describe(error)
    episodes = details.get("episodes") or []
    data = details.get("movieData") or (episodes[0]["data"] if episodes else None)
    shape = "movie" if details.get("movieData") else "%d ep" % len(episodes)
    if not data:
        return str(len(results)), shape, "-", "details: nothing playable"
    try:
        answer = call(base, token, "/links", body={"plugin": pid, "data": data, "budgetMs": int(timeout * 1000)},
                      timeout=timeout + 10)
    except EngineError as error:
        return str(len(results)), shape, "-", "links: " + describe(error)
    links = answer.get("links") or []
    kinds = {}
    for link in links:
        kinds[link.get("type", "?")] = kinds.get(link.get("type", "?"), 0) + 1
    cell = "%d (%s)" % (len(links), ", ".join("%s %d" % item for item in sorted(kinds.items()))) if links else "0"
    with_headers = sum(1 for link in links if link.get("headers"))
    note = "%d need headers" % with_headers if with_headers else ""
    skipped = answer.get("skipped") or []
    if skipped:
        note = (note + "; " if note else "") + "%d skipped" % len(skipped)
    return str(len(results)), shape, cell, note if links else "links: none" + ("; " + note if note else "")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--engine", default="http://127.0.0.1:17171")
    parser.add_argument("--token", required=True)
    parser.add_argument("--query", default="Inception")
    parser.add_argument("--out")
    parser.add_argument("--timeout", type=float, default=30)
    args = parser.parse_args()

    providers = call(args.engine, args.token, "/providers")
    rows, reached = [], {"search": 0, "details": 0, "links": 0}
    for provider in providers:
        started = time.time()
        search, details, links, note = check(args.engine, args.token, provider, args.query, args.timeout)
        reached["search"] += search not in ("-", "0")
        reached["details"] += details != "-"
        reached["links"] += links not in ("-", "0")
        rows.append((provider["name"], search, details, links, note, "%.1fs" % (time.time() - started)))
        print("%-28s search=%-4s details=%-8s links=%-22s %s" % (provider["name"], search, details, links, note),
              flush=True)

    lines = ["| Provider | Search | Details | Links | Note | Time |", "| :--- | :--- | :--- | :--- | :--- | :--- |"]
    lines += ["| %s |" % " | ".join(row) for row in rows]
    summary = "%d providers, query %r: %d found something, %d opened a title, %d returned links" % (
        len(providers), args.query, reached["search"], reached["details"], reached["links"])
    print(summary)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(summary + "\n\n" + "\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
