#!/usr/bin/env python3
"""Repair missing media in an unpacked NogiRelay blog export.

The exporter records uncached URLs in data/skipped.jsonl.  This tool maps those
URLs back to blog records, reuses already-exported files where possible, tries
the live original image (including an enclosing anchor's full-size image), and
then falls back to the Internet Archive CDX/Wayback APIs.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import mimetypes
import os
import re
import shutil
import sys
import tempfile
import threading
import time
from collections import defaultdict
from dataclasses import dataclass, field
from html.parser import HTMLParser
from pathlib import Path
from typing import Iterable
from urllib.parse import urljoin, urlsplit, urlunsplit

import requests


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".avif"}
USER_AGENT = "NogiRelay-media-repair/1.0 (+personal archival repair)"
TIMEOUT = (10, 45)


def canonical_url(value: str, base: str = "https://www.nogizaka46.com/") -> str:
    value = (value or "").strip().replace("&amp;", "&")
    if not value:
        return ""
    if value.startswith("//"):
        value = "https:" + value
    absolute = urljoin(base, value)
    parts = urlsplit(absolute)
    scheme = "https" if parts.scheme in {"http", "https"} else parts.scheme
    host = parts.netloc.lower()
    return urlunsplit((scheme, host, parts.path, parts.query, ""))


def url_path_key(value: str) -> str:
    return urlsplit(canonical_url(value)).path


def corrected_url(value: str) -> str:
    """Repair a legacy protocol-relative URL incorrectly joined to the main host."""
    fixed = canonical_url(value)
    marker = "/img.nogizaka46.com/"
    parts = urlsplit(fixed)
    normalized_path = "/" + parts.path.lstrip("/")
    if parts.netloc == "www.nogizaka46.com" and normalized_path.startswith(marker):
        return "https://img.nogizaka46.com/" + normalized_path[len(marker) :] + (
            ("?" + parts.query) if parts.query else ""
        )
    return fixed


def looks_like_image_url(value: str) -> bool:
    path = urlsplit(canonical_url(value)).path
    return Path(path).suffix.lower() in IMAGE_EXTENSIONS or path.lower().endswith(".cgi")


class ImageLinkParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.anchors: list[str] = []
        self.images: list[tuple[str, str]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = {key.lower(): value or "" for key, value in attrs}
        tag = tag.lower()
        if tag == "a":
            self.anchors.append(values.get("href", ""))
        elif tag == "img":
            self.images.append((values.get("src", ""), self.anchors[-1] if self.anchors else ""))

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "a" and self.anchors:
            self.anchors.pop()


TAG_RE = re.compile(r"<a\b[^>]*>|</a\s*>|<img\b[^>]*>", re.IGNORECASE)
ATTR_RE = re.compile(r"\b(href|src)\s*=\s*([\"'])(.*?)\2", re.IGNORECASE | re.DOTALL)


def image_links(html: str) -> Iterable[tuple[str, str]]:
    """Fast, forgiving extraction of image sources and their enclosing links."""
    anchors: list[str] = []
    for match in TAG_RE.finditer(html or ""):
        tag = match.group(0)
        lower = tag.lower()
        if lower.startswith("</a"):
            if anchors:
                anchors.pop()
            continue
        attrs = {name.lower(): value for name, _, value in ATTR_RE.findall(tag)}
        if lower.startswith("<a"):
            anchors.append(attrs.get("href", ""))
        else:
            yield attrs.get("src", ""), anchors[-1] if anchors else ""


@dataclass
class MissingItem:
    url: str
    role: str
    candidates: list[str] = field(default_factory=list)
    records: set[str] = field(default_factory=set)
    published_at: str = ""

    def add_candidate(self, value: str, *, first: bool = False) -> None:
        value = corrected_url(value)
        if not value or value in self.candidates:
            return
        if first:
            self.candidates.insert(0, value)
        else:
            self.candidates.append(value)


def read_jsonl(path: Path) -> Iterable[dict]:
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                yield json.loads(line)


def build_index(root: Path) -> tuple[dict[str, MissingItem], dict[str, list[str]], dict[str, str]]:
    skipped_path = root / "data" / "skipped.jsonl"
    blogs_path = root / "data" / "blogs.jsonl"
    missing: dict[str, MissingItem] = {}
    by_path: dict[str, list[str]] = defaultdict(list)
    for row in read_jsonl(skipped_path):
        if row.get("kind") != "media" or not row.get("url"):
            continue
        url = canonical_url(row["url"])
        item = missing.setdefault(url, MissingItem(url=url, role=row.get("role") or "media"))
        item.add_candidate(url)
        by_path[url_path_key(url)].append(url)

    record_targets: dict[str, list[str]] = defaultdict(list)
    existing: dict[str, str] = {}
    member_slugs: dict[str, str] = {}
    for blog in read_jsonl(blogs_path):
        record_id = str(blog.get("id", ""))
        member_id = str(blog.get("member_id", ""))
        base = blog.get("post_url") or "https://www.nogizaka46.com/"
        searchable = [blog.get("body_html") or ""]
        searchable.extend(media.get("url", "") for media in (blog.get("media") or []))
        for value in searchable:
            match = re.search(r"(?:/mb/|/_pre/blog/|\.com/blog/)([a-z0-9]+(?:\.[a-z0-9]+)+)/", value, re.I)
            if match:
                member_slugs[member_id] = match.group(1).lower()
                break
        def attach(raw_url: str, anchor: str = "") -> None:
            resolved = canonical_url(raw_url, base)
            matches = []
            if resolved in missing:
                matches.append(resolved)
            for candidate in by_path.get(url_path_key(resolved), []):
                if candidate not in matches:
                    matches.append(candidate)
            for target in matches:
                item = missing[target]
                item.records.add(record_id)
                if not item.published_at:
                    item.published_at = blog.get("published_at") or ""
                # assets_c entries are small retired thumbnails; prefer the linked original.
                if anchor and looks_like_image_url(anchor):
                    item.add_candidate(canonical_url(anchor, base), first="/assets_c/" in target)
                item.add_candidate(resolved)
                if target not in record_targets[record_id]:
                    record_targets[record_id].append(target)

        for source, anchor in image_links(blog.get("body_html") or ""):
            attach(source, anchor)
        for field_name in ("image_url", "member_avatar_url"):
            if blog.get(field_name):
                attach(blog[field_name])
        for media in blog.get("media") or []:
            url = canonical_url(media.get("url", ""))
            path = media.get("path", "")
            if url and path:
                existing[url] = path.replace("\\", "/")

    return missing, record_targets, existing


def detect_extension(data: bytes, content_type: str, source_url: str) -> str | None:
    head = data[:32]
    if head.startswith(b"\xff\xd8\xff"):
        return "jpg"
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if head.startswith((b"GIF87a", b"GIF89a")):
        return "gif"
    if head.startswith(b"RIFF") and head[8:12] == b"WEBP":
        return "webp"
    if len(head) >= 12 and head[4:12] in {b"ftypheic", b"ftypheix", b"ftyphevc", b"ftypmif1"}:
        return "heic"
    if len(head) >= 12 and head[4:12] in {b"ftypavif", b"ftypavis"}:
        return "avif"
    if data[:256].lstrip().lower().startswith((b"<!doctype html", b"<html")):
        return None
    mime = (content_type or "").split(";", 1)[0].strip().lower()
    known = {
        "image/jpeg": "jpg",
        "image/png": "png",
        "image/gif": "gif",
        "image/webp": "webp",
        "image/heic": "heic",
        "image/avif": "avif",
    }
    if mime in known:
        return known[mime]
    suffix = Path(urlsplit(source_url).path).suffix.lower().lstrip(".")
    return suffix if suffix in {x.lstrip(".") for x in IMAGE_EXTENSIONS} else None


def fetch_image(url: str) -> tuple[bytes, str] | None:
    variants = [url]
    if url.startswith("https://img.nogizaka46.com/"):
        variants.append("http://" + url[len("https://") :])
    for variant in variants:
        try:
            response = requests.get(
                variant,
                headers={"User-Agent": USER_AGENT},
                timeout=TIMEOUT,
                allow_redirects=True,
            )
            if response.status_code != 200 or not response.content:
                continue
            extension = detect_extension(response.content, response.headers.get("Content-Type", ""), variant)
            if extension:
                return response.content, extension
        except requests.RequestException:
            continue
    return None


def save_content(root: Path, data: bytes, extension: str) -> str:
    digest = hashlib.sha256(data).hexdigest()
    relative = f"media/{digest}.{extension.lower()}"
    destination = root / relative
    if not destination.exists():
        destination.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(prefix=".repair-", dir=destination.parent)
        try:
            with os.fdopen(fd, "wb") as handle:
                handle.write(data)
            os.replace(temporary, destination)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    return relative


def try_live(root: Path, item: MissingItem, existing: dict[str, str]) -> tuple[str, str, str] | None:
    for candidate in item.candidates:
        if candidate in existing and (root / existing[candidate]).is_file():
            return existing[candidate], "reused", candidate
    for candidate in item.candidates:
        result = fetch_image(candidate)
        if result:
            data, extension = result
            return save_content(root, data, extension), "live", candidate
    return None


class ArchiveThrottle:
    def __init__(self, interval: float = 0.8) -> None:
        self.interval = interval
        self.lock = threading.Lock()
        self.next_time = 0.0

    def wait(self) -> None:
        with self.lock:
            now = time.monotonic()
            delay = self.next_time - now
            if delay > 0:
                time.sleep(delay)
            self.next_time = time.monotonic() + self.interval


ARCHIVE_THROTTLE = ArchiveThrottle()


def archived_snapshot(candidate: str, year: str) -> tuple[str, str] | None:
    params = {
        "url": candidate,
        "output": "json",
        "filter": "statuscode:200",
        "fl": "timestamp,original,mimetype,statuscode",
        "collapse": "digest",
        "limit": "1",
    }
    if year:
        params["from"] = str(max(2011, int(year) - 1))
        params["to"] = str(min(2026, int(year) + 2))
    for attempt in range(1):
        ARCHIVE_THROTTLE.wait()
        try:
            response = requests.get(
                "https://web.archive.org/cdx/search/cdx",
                params=params,
                headers={"User-Agent": USER_AGENT},
                timeout=(8, 20),
            )
            if response.status_code == 429:
                return None
            response.raise_for_status()
            rows = response.json()
            if len(rows) < 2:
                return None
            columns = rows[0]
            row = dict(zip(columns, rows[1]))
            return row["timestamp"], row["original"]
        except (requests.RequestException, ValueError, KeyError):
            return None
    return None


def try_wayback(root: Path, item: MissingItem) -> tuple[str, str, str] | None:
    item_path = urlsplit(item.url).path
    if "/assets_c/" in item_path or "/files/46/_pre/blog/photos/entry/" in item_path:
        return None
    year_match = re.match(r"(20\d{2})", item.published_at or "")
    year = year_match.group(1) if year_match else ""
    for candidate in item.candidates:
        known_prefix = "https://img.nogizaka46.com/blog/staff/img/2020/12/05-3/"
        if candidate.startswith(known_prefix):
            replay = f"https://web.archive.org/web/20201205163702id_/{candidate}"
            result = fetch_image(replay)
            if result:
                data, extension = result
                return save_content(root, data, extension), "wayback", replay
        # CDX returns no captures for these retired generated paths as a class;
        # querying them individually mostly yields slow 503 responses.
        path = urlsplit(candidate).path
        if "/assets_c/" in path or "/files/46/_pre/blog/photos/entry/" in path:
            continue
        snapshot = archived_snapshot(candidate, year)
        if not snapshot:
            continue
        timestamp, original = snapshot
        replay = f"https://web.archive.org/web/{timestamp}id_/{original}"
        result = fetch_image(replay)
        if result:
            data, extension = result
            return save_content(root, data, extension), "wayback", replay
    return None


def write_checkpoint(root: Path, recovered: dict[str, tuple[str, str, str]]) -> None:
    destination = root / "wayback-repair-state.json"
    temporary = destination.with_suffix(".json.tmp")
    payload = {key: list(value) for key, value in recovered.items()}
    temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    os.replace(temporary, destination)


def load_checkpoint(root: Path) -> dict[str, tuple[str, str, str]]:
    source = root / "wayback-repair-state.json"
    if not source.is_file():
        return {}
    try:
        payload = json.loads(source.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    recovered: dict[str, tuple[str, str, str]] = {}
    for key, value in payload.items():
        if isinstance(value, list) and len(value) == 3 and (root / value[0]).is_file():
            recovered[key] = (value[0], value[1], value[2])
    return recovered


def backup_once(path: Path) -> Path:
    backup = path.with_name(path.stem + ".before-wayback-repair" + path.suffix)
    if not backup.exists():
        shutil.copy2(path, backup)
    return backup


def rewrite_export(
    root: Path,
    record_targets: dict[str, list[str]],
    missing: dict[str, MissingItem],
    recovered: dict[str, tuple[str, str, str]],
) -> tuple[int, int]:
    blogs_path = root / "data" / "blogs.jsonl"
    skipped_path = root / "data" / "skipped.jsonl"
    backup_once(blogs_path)
    backup_once(skipped_path)

    changed_records = 0
    temporary = blogs_path.with_suffix(".jsonl.repairing")
    with blogs_path.open("r", encoding="utf-8") as source, temporary.open("w", encoding="utf-8", newline="\n") as output:
        for line in source:
            if not line.strip():
                continue
            blog = json.loads(line)
            record_id = str(blog.get("id", ""))
            refs = blog.get("media") or []
            seen = {(canonical_url(ref.get("url", "")), ref.get("path", "")) for ref in refs}
            added = False
            for target in record_targets.get(record_id, []):
                result = recovered.get(target)
                if not result:
                    continue
                path, _, _ = result
                marker = (target, path)
                if marker in seen:
                    continue
                refs.append({"role": missing[target].role, "url": target, "path": path})
                seen.add(marker)
                added = True
            if added:
                blog["media"] = refs
                changed_records += 1
            output.write(json.dumps(blog, ensure_ascii=False, separators=(",", ":")) + "\n")
    os.replace(temporary, blogs_path)

    remaining = 0
    temporary = skipped_path.with_suffix(".jsonl.repairing")
    with skipped_path.open("r", encoding="utf-8") as source, temporary.open("w", encoding="utf-8", newline="\n") as output:
        for line in source:
            if not line.strip():
                continue
            row = json.loads(line)
            target = canonical_url(row.get("url", ""))
            if target in recovered:
                continue
            output.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
            remaining += 1
    os.replace(temporary, skipped_path)
    return changed_records, remaining


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("--live-workers", type=int, default=12)
    parser.add_argument("--wayback-workers", type=int, default=2)
    parser.add_argument("--skip-live", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    if not (root / "data" / "blogs.jsonl").is_file() or not (root / "data" / "skipped.jsonl").is_file():
        parser.error("root must be an unpacked NogiRelay blog export with data/blogs.jsonl and data/skipped.jsonl")

    print("Indexing export...", flush=True)
    missing, record_targets, existing = build_index(root)
    print(f"Missing URLs: {len(missing)}; mapped to records: {sum(bool(item.records) for item in missing.values())}", flush=True)

    recovered = {key: value for key, value in load_checkpoint(root).items() if key in missing}
    items = list(missing.items())
    print(f"Trying local reuse and live full-size images (checkpoint has {len(recovered)})...", flush=True)
    pending_live = [] if args.skip_live else [(target, item) for target, item in items if target not in recovered]
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.live_workers)) as pool:
        futures = {pool.submit(try_live, root, item, existing): target for target, item in pending_live}
        for done, future in enumerate(concurrent.futures.as_completed(futures), 1):
            target = futures[future]
            try:
                result = future.result()
            except Exception:
                result = None
            if result:
                recovered[target] = result
            if done % 100 == 0 or done == len(futures):
                print(f"Live phase {done}/{len(futures)}; recovered {len(recovered)}", flush=True)
                write_checkpoint(root, recovered)

    remaining = [(target, item) for target, item in items if target not in recovered]
    print(f"Trying Wayback for {len(remaining)} remaining URLs...", flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.wayback_workers)) as pool:
        futures = {pool.submit(try_wayback, root, item): target for target, item in remaining}
        for done, future in enumerate(concurrent.futures.as_completed(futures), 1):
            target = futures[future]
            try:
                result = future.result()
            except Exception:
                result = None
            if result:
                recovered[target] = result
            if done % 25 == 0 or done == len(futures):
                print(f"Wayback phase {done}/{len(futures)}; total recovered {len(recovered)}", flush=True)
                write_checkpoint(root, recovered)

    changed_records, remaining_count = rewrite_export(root, record_targets, missing, recovered)
    counts: dict[str, int] = defaultdict(int)
    for _, source, _ in recovered.values():
        counts[source] += 1
    prior_report = {}
    report_path = root / "wayback-repair-report.json"
    if report_path.is_file():
        try:
            prior_report = json.loads(report_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            prior_report = {}
    prior_sources = prior_report.get("sources", {}) if prior_report.get("remaining") == len(missing) else {}
    for source, count in prior_sources.items():
        counts[source] += int(count)
    prior_recovered = int(prior_report.get("recovered", 0)) if prior_sources else 0
    prior_changed = int(prior_report.get("changedBlogRecords", 0)) if prior_sources else 0
    report = {
        "missingBefore": int(prior_report.get("missingBefore", len(missing))) if prior_sources else len(missing),
        "recovered": prior_recovered + len(recovered),
        "remaining": remaining_count,
        "changedBlogRecords": prior_changed + changed_records,
        "sources": dict(sorted(counts.items())),
        "unmappedMissing": sorted(target for target, item in missing.items() if not item.records),
        "unrecovered": sorted(target for target in missing if target not in recovered),
    }
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False), flush=True)
    print(f"Report: {report_path}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
