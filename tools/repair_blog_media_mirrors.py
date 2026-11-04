#!/usr/bin/env python3
"""Second-pass repair for media still listed in data/skipped.jsonl.

repair_blog_media.py only asked the Wayback Machine about the URLs as they
appear in the export (www.nogizaka46.com/files/46/_pre/blog/...), which were
never crawled.  The same files were originally served from the retired hosts
img.nogizaka46.com/blog/... and blog.nogizaka46.com/..., and third-party
mirrors (janelin612.github.io, donadona.jp) embed those original paths.  This
pass maps every missing URL back to those original locations and searches:

1. Wayback CDX by directory prefix on each legacy host,
2. Wayback replay redirects for exact legacy URLs CDX did not list,
3. Arquivo.pt CDX/replay,
4. the janelin612/n46-crawler GitHub Pages mirror, whose files are committed
   with percent-encoded names (so the github.io URLs in the export 404 unless
   the '%' is escaped once more).
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import posixpath
import os
import re
import subprocess
import sys
import threading
import time
from collections import defaultdict
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from repair_blog_media import (  # noqa: E402
    USER_AGENT,
    MissingItem,
    build_index,
    detect_extension,
    load_checkpoint,
    rewrite_export,
    save_content,
    write_checkpoint,
)

LEGACY_IMG = "img.nogizaka46.com/blog/"
LEGACY_BLOG = "blog.nogizaka46.com/"
SESSION_LOCAL = threading.local()


def session() -> requests.Session:
    if not hasattr(SESSION_LOCAL, "value"):
        value = requests.Session()
        value.headers["User-Agent"] = USER_AGENT
        SESSION_LOCAL.value = value
    return SESSION_LOCAL.value


class Throttle:
    def __init__(self, interval: float) -> None:
        self.interval = interval
        self.lock = threading.Lock()
        self.next_time = 0.0
        self.penalty_until = 0.0

    def wait(self) -> None:
        with self.lock:
            now = time.monotonic()
            delay = max(self.next_time, self.penalty_until) - now
            if delay > 0:
                time.sleep(delay)
            self.next_time = time.monotonic() + self.interval

    def penalize(self, seconds: float) -> None:
        with self.lock:
            self.penalty_until = max(self.penalty_until, time.monotonic() + seconds)


WAYBACK = Throttle(1.0)
ARQUIVO = Throttle(0.5)
GITHUB = Throttle(0.1)
MIRROR_REPO = "janelin612/n46-crawler"
MIRROR_BRANCH = "gh-pages"


def get(url: str, throttle: Throttle, *, params: dict | None = None, timeout=(10, 60), tries: int = 4):
    for attempt in range(tries):
        throttle.wait()
        try:
            response = session().get(url, params=params, timeout=timeout, allow_redirects=True)
        except requests.RequestException:
            time.sleep(3 * (attempt + 1))
            continue
        if response.status_code in (429, 503, 504, 520, 522):
            throttle.penalize(30 * (attempt + 1))
            continue
        return response
    return None


def legacy_rest(url: str) -> str | None:
    """Return the path below the legacy blog root (e.g. 'photos/entry/...')."""
    parts = urlsplit(url)
    host, path = parts.netloc.lower(), unquote(parts.path)
    if host == "www.nogizaka46.com":
        marker = "/files/46/_pre/blog/"
        if path.startswith(marker):
            return path[len(marker):]
        if path.startswith("/img.nogizaka46.com/blog/"):
            return path[len("/img.nogizaka46.com/blog/"):]
        return None
    if host == "img.nogizaka46.com" and path.startswith("/blog/"):
        return path[len("/blog/"):]
    if host == "blog.nogizaka46.com":
        return path.lstrip("/")
    match = None
    if host == "janelin612.github.io":
        match = re.match(r"^/n46-crawler/mb/[^/]+/img/(.*)$", path)
    elif host == "donadona.jp":
        match = re.match(r"^/storage/nogizaka/[^/]+/[^/]+/img/(.*)$", path)
    if not match:
        return None
    rest = match.group(1)
    if rest.startswith("cid:"):
        return None
    for prefix in ("files/46/_pre/blog/", "blog/"):
        if rest.startswith(prefix):
            return rest[len(prefix):]
    return rest


def legacy_candidates(item: MissingItem) -> list[str]:
    """Legacy host URLs (scheme-less, decoded path) for every candidate of an item."""
    result: list[str] = []
    for candidate in item.candidates:
        rest = legacy_rest(candidate)
        if not rest:
            continue
        for host in (LEGACY_IMG, LEGACY_BLOG):
            value = host + rest
            if value not in result:
                result.append(value)
    return result


def match_key(url: str) -> str:
    if "://" in url:
        url = url.split("://", 1)[1]
    host, _, path = url.partition("/")
    host = host.lower().split(":")[0]
    if host.startswith("www."):
        host = host[4:]
    return host + "/" + unquote(path.split("?", 1)[0]).lower()


def encoded(value: str) -> str:
    host, _, path = value.partition("/")
    return host + "/" + quote(path, safe="/%:@!$&'()*+,;=~-._")


# ---------------------------------------------------------------- Wayback

def cdx_prefix(prefix: str) -> list[tuple[str, str]] | None:
    """Wayback captures below a directory; None when the listing itself failed."""
    params = {
        "url": encoded(prefix),
        "matchType": "prefix",
        "output": "json",
        "fl": "timestamp,original,statuscode",
        "filter": ["statuscode:200", "mimetype:image/.*"],
        "limit": "5000",
    }
    response = get("https://web.archive.org/cdx/search/cdx", WAYBACK, params=params, timeout=(10, 120))
    if response is None or response.status_code != 200:
        return None
    try:
        rows = response.json() if response.text.strip() else []
    except ValueError:
        return None
    return [(row[0], row[1]) for row in rows[1:]]


def arquivo_prefix(prefix: str) -> list[tuple[str, str]] | None:
    # Arquivo's pywb only honours prefix queries written as a scheme-less "host/path*".
    params = {"url": encoded(prefix) + "*", "output": "json", "limit": "5000"}
    response = get("https://arquivo.pt/wayback/cdx", ARQUIVO, params=params, timeout=(10, 120), tries=3)
    if response is None or response.status_code != 200:
        return None
    rows = []
    for line in response.text.splitlines():
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if str(row.get("status")) == "200" and str(row.get("mime", "")).startswith("image/"):
            rows.append((row["timestamp"], row["url"]))
    return rows


def fetch_image(url: str, throttle: Throttle) -> tuple[bytes, str] | None:
    response = get(url, throttle, timeout=(10, 60), tries=3)
    if response is None or response.status_code != 200 or not response.content:
        return None
    extension = detect_extension(response.content, response.headers.get("Content-Type", ""), url)
    return (response.content, extension) if extension else None


def replay_snapshots(snapshots: list[tuple[str, str]], base: str, throttle: Throttle):
    # Early captures are often truncated or time out; walk every capture, raw (id_) then image-mode (im_).
    for timestamp, original in snapshots:
        for mode in ("id_", "im_"):
            replay = f"{base}/{timestamp}{mode}/{original}"
            result = fetch_image(replay, throttle)
            if result:
                return result, replay
    return None


def wayback_probe(legacy: str, year: str):
    """Replay without CDX: Wayback redirects to the nearest capture if one exists."""
    replay = f"https://web.archive.org/web/{year or '2013'}id_/http://{encoded(legacy)}"
    result = fetch_image(replay, WAYBACK)
    return (result, replay) if result else None


# ---------------------------------------------------------------- GitHub mirror

def github_mirror_index() -> dict[str, str]:
    """Map decoded legacy paths to raw URLs of files in the n46-crawler mirror."""
    def api(path: str):
        response = get(f"https://api.github.com/repos/{MIRROR_REPO}/{path}", GITHUB, tries=2)
        if response is None or response.status_code != 200:
            raise RuntimeError(f"GitHub API {path}: {response.status_code if response is not None else 'no response'}")
        return response.json()

    token = os.environ.get("GITHUB_TOKEN") or ""
    if not token:
        try:
            token = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True, timeout=20).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            token = ""
    if token:
        session().headers["Authorization"] = f"Bearer {token}"
    index: dict[str, str] = {}
    try:
        for member in api(f"contents/mb?ref={MIRROR_BRANCH}"):
            if member["type"] != "dir":
                continue
            tree = api(f"git/trees/{member['sha']}?recursive=1")
            for entry in tree.get("tree", []):
                path = entry["path"]
                if entry["type"] != "blob" or not path.startswith("img/"):
                    continue
                rest = path[len("img/"):]
                for prefix in ("files/46/_pre/blog/", "blog/"):
                    if rest.startswith(prefix):
                        rest = rest[len(prefix):]
                        break
                raw = (
                    f"https://raw.githubusercontent.com/{MIRROR_REPO}/{MIRROR_BRANCH}/mb/"
                    f"{quote(member['name'])}/{quote(path, safe='/')}"
                )
                index.setdefault(unquote(rest).lower(), raw)
    finally:
        session().headers.pop("Authorization", None)
    return index


# ---------------------------------------------------------------- driver

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--skip-probe", action="store_true", help="skip per-URL Wayback replay probing")
    parser.add_argument("--skip-arquivo", action="store_true")
    parser.add_argument("--only-arquivo", action="store_true")
    parser.add_argument("--only-github", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()

    print("Indexing export...", flush=True)
    missing, record_targets, _ = build_index(root)
    recovered = {k: v for k, v in load_checkpoint(root).items() if k in missing}
    print(f"Missing URLs: {len(missing)}; checkpoint {len(recovered)}", flush=True)

    legacy_by_item = {target: legacy_candidates(item) for target, item in missing.items()}
    years = {}
    for target, item in missing.items():
        match = re.match(r"(20\d{2})", item.published_at or "")
        years[target] = match.group(1) if match else ""

    lock = threading.Lock()

    def record(target: str, outcome, source: str) -> None:
        (data, extension), replay = outcome
        path = save_content(root, data, extension)
        with lock:
            recovered[target] = (path, source, replay)

    def run_phase(name: str, func, source: str) -> None:
        pending = [t for t in missing if t not in recovered and legacy_by_item[t]]
        print(f"{name}: {len(pending)} pending", flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = {pool.submit(func, target): target for target in pending}
            for done, future in enumerate(concurrent.futures.as_completed(futures), 1):
                target = futures[future]
                try:
                    outcome = future.result()
                except Exception as error:  # keep going; report at the end
                    print(f"  error {target}: {error}", flush=True)
                    outcome = None
                if outcome:
                    record(target, outcome, source)
                if done % 50 == 0 or done == len(futures):
                    print(f"  {done}/{len(futures)}; total recovered {len(recovered)}", flush=True)
                    with lock:
                        write_checkpoint(root, recovered)

    def list_directories(name: str, lister) -> tuple[dict[str, list[tuple[str, str]]], set[str]]:
        directories: set[str] = set()
        for target, values in legacy_by_item.items():
            if target in recovered:
                continue
            for value in values:
                directory = posixpath.dirname(value) + "/"
                # Host-root or member-root listings are huge; leave those to exact lookups.
                if directory.count("/") >= 3:
                    directories.add(directory)
        print(f"{name}: {len(directories)} directory listings", flush=True)
        index: dict[str, list[tuple[str, str]]] = defaultdict(list)
        listed: set[str] = set()
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = {pool.submit(lister, directory): directory for directory in directories}
            for done, future in enumerate(concurrent.futures.as_completed(futures), 1):
                rows = future.result()
                if rows is not None:
                    if len(rows) < 5000:  # truncated listings must not suppress exact lookups
                        listed.add(futures[future])
                    for timestamp, original in rows:
                        index[match_key(original)].append((timestamp, original))
                if done % 25 == 0 or done == len(futures):
                    print(f"  listed {done}/{len(futures)} (ok {len(listed)}); archived files {len(index)}", flush=True)
        return index, listed

    def lookup(index, base: str, throttle: Throttle):
        def run(target: str):
            for value in legacy_by_item[target]:
                found = index.get(match_key(value))
                if found:
                    outcome = replay_snapshots(sorted(found), base, throttle)
                    if outcome:
                        return outcome
            return None
        return run

    wb_listed: set[str] = set()
    only = args.only_arquivo or args.only_github
    if not only:
        # Phase 1: Wayback CDX listings of the legacy directories.
        wb_index, wb_listed = list_directories("Phase 1 Wayback CDX", cdx_prefix)
        run_phase("Phase 1 download", lookup(wb_index, "https://web.archive.org/web", WAYBACK), "wayback-legacy")

    # Phase 2: exact Wayback replay for URLs whose directory could not be listed.
    if not args.skip_probe and not only:
        def probe(target: str):
            for value in legacy_by_item[target]:
                if posixpath.dirname(value) + "/" in wb_listed:
                    continue
                outcome = wayback_probe(value, years[target])
                if outcome:
                    return outcome
            return None
        run_phase("Phase 2 Wayback replay probe", probe, "wayback-legacy")

    # Phase 3: Arquivo.pt listings of the same directories.
    if not args.skip_arquivo and not args.only_github:
        aq_index, _ = list_directories("Phase 3 Arquivo.pt CDX", arquivo_prefix)
        run_phase("Phase 3 download", lookup(aq_index, "https://arquivo.pt/wayback", ARQUIVO), "arquivo")

    # Phase 4: janelin612/n46-crawler GitHub mirror.
    if not args.only_arquivo:
        print("Phase 4 GitHub mirror: indexing...", flush=True)
        mirror = github_mirror_index()
        print(f"  mirror files {len(mirror)}", flush=True)

        def from_mirror(target: str):
            for value in legacy_by_item[target]:
                raw = mirror.get(match_key(value).split("/", 1)[1].removeprefix("blog/"))
                if raw:
                    result = fetch_image(raw, GITHUB)
                    if result:
                        return result, raw
            return None
        run_phase("Phase 4 download", from_mirror, "github-mirror")

    write_checkpoint(root, recovered)
    changed_records, remaining_count = rewrite_export(root, record_targets, missing, recovered)

    report_path = root / "wayback-repair-report.json"
    report = json.loads(report_path.read_text(encoding="utf-8")) if report_path.is_file() else {}
    sources = defaultdict(int, report.get("sources", {}))
    for _, source, _ in recovered.values():
        sources[source] += 1
    report.update(
        {
            "missingBefore": int(report.get("missingBefore", len(missing))),
            "recovered": int(report.get("recovered", 0)) + len(recovered),
            "remaining": remaining_count,
            "changedBlogRecords": int(report.get("changedBlogRecords", 0)) + changed_records,
            "sources": dict(sorted(sources.items())),
            "unmappedMissing": sorted(t for t, item in missing.items() if not item.records),
            "unrecovered": sorted(t for t in missing if t not in recovered),
        }
    )
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    summary = {k: v for k, v in report.items() if k not in ("unmappedMissing", "unrecovered")}
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
