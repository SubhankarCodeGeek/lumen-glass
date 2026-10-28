#!/usr/bin/env python3
"""Validate the built Lumen Glass website.

Runs against the Jekyll output (``_site/``) so it checks what GitHub Pages will
actually serve, not the sources. Standard library only, so it works on a stock
GitHub Actions runner with no install step.

Checks:
  * required files survived the build;
  * every local ``src``/``href`` resolves to a real file in the output;
  * no localhost / preview URLs leaked in;
  * canonical, Open Graph and Twitter metadata are present and absolute;
  * JSON-LD blocks parse as JSON;
  * ``<img>`` elements carry an ``alt`` attribute;
  * ``sitemap.xml`` is well-formed and lists only published pages.

Usage:
    python3 scripts/validate_site.py --site _site --baseurl /lumen-glass \
        --origin https://subhankarcodegeek.github.io
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit

REQUIRED = [
    "index.html",
    "robots.txt",
    "sitemap.xml",
    "google09ae51d459da8d85.html",
    "assets/og-image.png",
    "assets/site/styles.css",
    "assets/site/docs.css",
    "assets/site/script.js",
    "assets/site/lumen-demo.js",
    "assets/site/favicon.svg",
    "assets/site/hero-poster.jpg",
    "assets/site/hero-demo.mp4",
    "assets/site/toric.webp",
    "assets/site/liquid-dock.webp",
    "assets/site/droplet.webp",
    "assets/site/split-comparison.webp",
    "assets/site/legacy.webp",
    "assets/site/live-capture.webp",
    "docs/ARCHITECTURE.html",
    "docs/PERF.html",
]

BANNED = ("localhost", "127.0.0.1", "0.0.0.0", "file://", "/Users/", "example.com")

# Ownership-proof files are plain text served with an .html name: they have no
# <head>, no lang and no title, and must not be "fixed".
SKIP_PAGES = {"google09ae51d459da8d85.html"}


class PageParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.refs: list[tuple[str, str]] = []
        self.images: list[dict[str, str]] = []
        self.metas: list[dict[str, str]] = []
        self.links: list[dict[str, str]] = []
        self.html_lang: str | None = None
        self.title: str | None = None
        self.h1_count = 0
        self._in_title = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        data = {k: (v or "") for k, v in attrs}
        if tag == "html":
            self.html_lang = data.get("lang")
        elif tag == "title":
            self._in_title = True
        elif tag == "h1":
            self.h1_count += 1
        elif tag == "img":
            self.images.append(data)
            if data.get("src"):
                self.refs.append(("src", data["src"]))
        elif tag == "meta":
            self.metas.append(data)
        elif tag == "link":
            self.links.append(data)
            if data.get("href"):
                self.refs.append(("href", data["href"]))
        elif tag in {"script", "source", "video"}:
            if data.get("src"):
                self.refs.append(("src", data["src"]))

    def handle_endtag(self, tag: str) -> None:
        if tag == "title":
            self._in_title = False

    def handle_data(self, data: str) -> None:
        if self._in_title and self.title is None:
            self.title = data.strip()


def looks_external(ref: str) -> bool:
    if ref.startswith("//"):
        return True
    return bool(re.match(r"^[a-zA-Z][a-zA-Z0-9+.\-]*:", ref))


def resolve(site: Path, baseurl: str, page: Path, ref: str) -> Path | None:
    """Map a reference to a file in the built site, or None if it is external."""
    ref = ref.strip()
    if not ref or ref.startswith("#") or looks_external(ref):
        return None

    path = urlsplit(ref).path
    if not path:
        return None

    if path.startswith("/"):
        if baseurl and path.startswith(baseurl + "/"):
            path = path[len(baseurl):]
        elif baseurl and path == baseurl:
            path = "/"
        elif baseurl:
            # Absolute from the domain root: wrong for a project site.
            return site / "__outside_baseurl__" / path.lstrip("/")
        target = site / path.lstrip("/")
    else:
        target = page.parent / path

    if target.is_dir() or ref.endswith("/"):
        target = target / "index.html"
    return target


def check_page(
    site: Path,
    baseurl: str,
    origin: str,
    page: Path,
    errors: list[str],
    warnings: list[str],
) -> None:
    rel = page.relative_to(site)
    raw = page.read_text(encoding="utf-8", errors="replace")

    parser = PageParser()
    parser.feed(raw)

    for token in BANNED:
        if token in raw:
            errors.append(f"{rel}: contains forbidden reference {token!r}")

    # A page that still contains template delimiters means Liquid did not run
    # (or a variable name is wrong) and was published verbatim.
    if "{{" in raw or "{%" in raw:
        first = raw.index("{{") if "{{" in raw else raw.index("{%")
        errors.append(f"{rel}: unrendered Liquid near {raw[max(0, first - 40):first + 40]!r}")

    if not parser.html_lang:
        errors.append(f"{rel}: <html> is missing a lang attribute")

    if not parser.title:
        errors.append(f"{rel}: missing <title>")
    elif len(parser.title) > 70:
        warnings.append(f"{rel}: <title> is {len(parser.title)} characters (> 70)")

    if parser.h1_count != 1:
        warnings.append(f"{rel}: expected exactly one <h1>, found {parser.h1_count}")

    metas = {m.get("name") or m.get("property"): m.get("content", "") for m in parser.metas}
    if not metas.get("description"):
        warnings.append(f"{rel}: missing meta description")

    # Links, styles, scripts and media must exist in the build output.
    for attr, ref in parser.refs:
        target = resolve(site, baseurl, page, ref)
        if target is None:
            continue
        if "__outside_baseurl__" in str(target):
            errors.append(f"{rel}: {attr} {ref!r} points outside the project-site base path")
        elif not target.exists():
            errors.append(f"{rel}: {attr} {ref!r} does not resolve to a published file")

    for image in parser.images:
        if "alt" not in image:
            errors.append(f"{rel}: <img src={image.get('src', '?')!r}> has no alt attribute")

    # JSON-LD must be parseable JSON.
    for block in re.findall(
        r'<script[^>]+type="application/ld\+json"[^>]*>(.*?)</script>', raw, re.S
    ):
        try:
            parsed = json.loads(block)
        except json.JSONDecodeError as exc:
            errors.append(f"{rel}: JSON-LD does not parse ({exc})")
            continue
        if "@context" not in parsed or "@type" not in parsed:
            errors.append(f"{rel}: JSON-LD is missing @context/@type")

    # Landing page: SEO essentials must be present, absolute and correct.
    if rel.as_posix() == "index.html":
        canonical = next(
            (link.get("href", "") for link in parser.links if link.get("rel") == "canonical"),
            "",
        )
        expected = f"{origin}{baseurl}/"
        if not canonical:
            errors.append("index.html: missing canonical URL")
        elif canonical != expected:
            errors.append(f"index.html: canonical is {canonical!r}, expected {expected!r}")

        for prop in ("og:title", "og:description", "og:url", "og:image", "og:type"):
            if not metas.get(prop):
                errors.append(f"index.html: missing {prop}")
        for name in ("twitter:card", "twitter:image", "twitter:title"):
            if not metas.get(name):
                errors.append(f"index.html: missing {name}")

        og_image = metas.get("og:image", "")
        if og_image and not og_image.startswith("http"):
            errors.append("index.html: og:image must be an absolute URL")

        if 'name="theme-color"' not in raw:
            warnings.append("index.html: missing theme-color")
        if not any(link.get("rel") == "icon" for link in parser.links):
            errors.append("index.html: missing favicon link")


def check_sitemap(site: Path, baseurl: str, origin: str, errors: list[str]) -> None:
    path = site / "sitemap.xml"
    if not path.exists():
        errors.append("sitemap.xml is missing from the build output")
        return

    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:
        errors.append(f"sitemap.xml is not well-formed XML ({exc})")
        return

    ns = {"sm": "http://www.sitemaps.org/schemas/sitemap/0.9"}
    locs = [el.text or "" for el in tree.getroot().findall(".//sm:loc", ns)]
    if not locs:
        errors.append("sitemap.xml lists no URLs")
        return

    prefix = f"{origin}{baseurl}"
    for loc in locs:
        if not loc.startswith(prefix):
            errors.append(f"sitemap.xml: {loc!r} is not under {prefix!r}")
            continue
        rel = loc[len(prefix):].lstrip("/")
        target = site / rel
        if rel == "" or rel.endswith("/"):
            target = site / rel / "index.html"
        if not target.exists():
            errors.append(f"sitemap.xml: {loc!r} does not resolve to a published page")


def main() -> int:
    argp = argparse.ArgumentParser(description="Validate the built Lumen Glass website.")
    argp.add_argument("--site", default="_site", help="build output directory")
    argp.add_argument("--baseurl", default="/lumen-glass", help="Jekyll baseurl")
    argp.add_argument("--origin", default="https://subhankarcodegeek.github.io", help="site origin")
    args = argp.parse_args()

    site = Path(args.site).resolve()
    baseurl = args.baseurl.rstrip("/")
    origin = args.origin.rstrip("/")

    errors: list[str] = []
    warnings: list[str] = []

    if not site.is_dir():
        print(f"::error::build output {site} does not exist", file=sys.stderr)
        return 1

    for name in REQUIRED:
        if not (site / name).exists():
            errors.append(f"required file missing from the build: {name}")

    pages = [
        page
        for page in sorted(site.glob("*.html")) + sorted((site / "docs").glob("*.html"))
        if page.name not in SKIP_PAGES
    ]
    if not pages:
        errors.append("no HTML pages were produced")

    for page in pages:
        check_page(site, baseurl, origin, page, errors, warnings)

    check_sitemap(site, baseurl, origin, errors)

    for warning in warnings:
        print(f"::warning::{warning}")
    for error in errors:
        print(f"::error::{error}")

    print(
        f"Checked {len(pages)} page(s): "
        f"{len(errors)} error(s), {len(warnings)} warning(s)."
    )
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
