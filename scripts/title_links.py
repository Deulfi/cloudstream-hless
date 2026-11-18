#!/usr/bin/env python3
"""Raw links per provider for one title, optionally probed; optionally compared with the addon's answer.

    python3 scripts/title_links.py --engine http://127.0.0.1:17172 --token TOKEN \\
        --title "Spider-Man: Brand New Day" [--providers Megakino Filmpalast] [--probe] \\
        [--addon http://127.0.0.1:7000/stream/movie/tt22084616.json]

Use it to answer "why does the addon show fewer or worse streams than expected":
without --probe it prints every link the engine returns (type, quality, source,
header names, host); with --probe it also fetches each link the way the addon
should and prints facts; with --addon it adds
the addon's own list for comparison.

Talks to a running engine (see "Run the engine
alone"). Standard library only. Never prints full URLs, header values or the
token. The probe is a reference for the Kotlin implementation, not a tool for
untrusted networks: it does not apply the address rules of 5b.
"""
import argparse
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request

UNKNOWN_QUALITY = (0, 400)  # 400 is the CloudStream library's Qualities.Unknown


def call(args, path, params=None, body=None, timeout=60):
    url = args.engine + path + ("?" + urllib.parse.urlencode(params) if params else "")
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Authorization": "Bearer " + args.token}
    if data is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        return {"__http": error.code}
    except Exception as error:  # timeouts, resets
        return {"__error": type(error).__name__}


def norm(text):
    return re.sub(r"[^0-9a-z]", "", text.lower())


def fetch(url, headers, method="GET", byte_range=None, limit=600_000, timeout=4):
    sent = dict(headers)
    sent.setdefault("User-Agent", "Mozilla/5.0")
    if byte_range:
        sent["Range"] = byte_range
    request = urllib.request.Request(url, headers=sent, method=method)
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read(limit) if method == "GET" else b""
            return response.status, dict(response.headers), body, time.time() - started
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), b"", time.time() - started
    except Exception as error:
        return type(error).__name__, {}, b"", time.time() - started


def probe(link):
    """One line of facts about a link. Rules mirror the addon."""
    url, headers = link["url"], link.get("headers") or {}
    if link["type"] == "M3U8":
        status, _, body, took = fetch(url, headers)
        text = body.decode("utf-8", "replace")
        if status != 200 or "#EXTM3U" not in text:
            return "GET %s (%.1fs): not a playlist" % (status, took)
        if "#EXT-X-STREAM-INF" in text:
            # Variant streams only. "#EXT-X-I-FRAME-STREAM-INF" lines are trick-play
            # streams with the same resolutions and must not be counted.
            heights, bandwidths = [], []
            for line in text.splitlines():
                if line.startswith("#EXT-X-STREAM-INF"):
                    resolution = re.search(r"RESOLUTION=\d+x(\d+)", line)
                    if resolution:
                        heights.append(int(resolution.group(1)))
                    bandwidth = re.search(r"(?<!AVERAGE-)BANDWIDTH=(\d+)", line)
                    if bandwidth:
                        bandwidths.append(int(bandwidth.group(1)) // 1000)
            audio_groups = len(re.findall(r"#EXT-X-MEDIA:.*TYPE=AUDIO", text))
            return "MASTER heights=%s top_kbps=%s audio_groups=%d (%.1fs)" % (
                sorted(set(heights), reverse=True), max(bandwidths) if bandwidths else None, audio_groups, took)
        durations = [float(value) for value in re.findall(r"#EXTINF:([\d.]+)", text)]
        return "MEDIA segments=%d minutes=%.1f vod=%s (%.1fs)" % (
            len(durations), sum(durations) / 60, "#EXT-X-ENDLIST" in text, took)
    status, headers_out, _, took = fetch(url, headers, method="HEAD")
    length = headers_out.get("Content-Length")
    if status == 200 and length:
        return "FILE size=%.2fGB type=%s ranges=%s (%.1fs)" % (
            int(length) / 1e9, headers_out.get("Content-Type"), headers_out.get("Accept-Ranges"), took)
    status2, headers2, _, took2 = fetch(url, headers, byte_range="bytes=0-0")
    content_range = headers2.get("Content-Range") or ""
    total = content_range.split("/")[-1] if "/" in content_range else None
    return "FILE HEAD=%s rangeGET=%s total=%s type=%s (%.1fs)" % (
        status, status2, total, headers2.get("Content-Type"), took + took2)


def show_addon(url):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "title_links"}), timeout=60) as response:
            streams = json.loads(response.read().decode())["streams"]
    except Exception as error:
        print("addon: %s" % type(error).__name__)
        return
    print("== addon: %d streams" % len(streams))
    for stream in streams:
        print("   %-34r %s" % (stream["name"].replace("\n", " / "), stream["description"].replace("\n", " / ")[:110]))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--engine", default="http://127.0.0.1:17172")
    parser.add_argument("--token", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--providers", nargs="*", help="provider names; default: all")
    parser.add_argument("--probe", action="store_true")
    parser.add_argument("--addon")
    args = parser.parse_args()

    total = {"links": 0, "unknown_quality": 0, "split_audio": 0, "drm": 0}
    for provider in call(args, "/providers"):
        if not provider["enabled"] or (args.providers and provider["name"] not in args.providers):
            continue
        results = call(args, "/search", {"q": args.title, "plugins": provider["providerId"]}, timeout=40)
        if not isinstance(results, list):
            continue
        exact = [r for r in results if norm(r["name"]) == norm(args.title)]
        if not exact:
            continue
        details = call(args, "/details", {"plugin": provider["providerId"], "url": exact[0]["url"]})
        data = details.get("movieData") if isinstance(details, dict) else None
        if not data:
            print("== %-14s matched, but no movieData" % provider["name"])
            continue
        answer = call(args, "/links", body={"plugin": provider["providerId"], "data": data, "budgetMs": 30000}, timeout=45)
        links = answer.get("links") if isinstance(answer, dict) else None
        if links is None:
            print("== %-14s links error %s" % (provider["name"], answer))
            continue
        print("== %-14s runtime_min=%s links=%d skipped=%s" % (
            provider["name"], details.get("durationMinutes"), len(links), [s["reason"] for s in answer.get("skipped", [])]))
        for link in links:
            total["links"] += 1
            total["unknown_quality"] += link["quality"] in UNKNOWN_QUALITY
            total["split_audio"] += bool(link.get("audioTracks"))
            total["drm"] += bool(link.get("drm"))
            line = "   %-5s q=%-5s %-16s hdr=%s host=%s" % (
                link["type"], link["quality"], link["source"][:16], sorted((link.get("headers") or {}).keys()),
                urllib.parse.urlparse(link["url"]).netloc)
            print(line)
            if args.probe:
                print("         probe:", probe(link))
    print("TOTAL", total)
    if args.addon:
        show_addon(args.addon)


if __name__ == "__main__":
    main()
