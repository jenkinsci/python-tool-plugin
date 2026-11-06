#!/usr/bin/env python3
"""
Version Catalog Generator for the Jenkins Python Tool Plugin.

Fetches the latest official standalone Python releases from
astral-sh/python-build-standalone (multi-platform asset URLs for Windows, Linux,
macOS) and the latest Astral uv releases from astral-sh/uv, then MERGES them into
an existing versions.json so previously published versions are never dropped
just because they fell out of the "recent releases" window.

The output file is used in two places:
  * src/main/resources/io/jenkins/plugins/python/versions.json (bundled fallback)
  * the `catalog` branch of the plugin repository, refreshed on a schedule by
    .github/workflows/update-versions.yml and fetched dynamically by the plugin.
"""

import argparse
import datetime
import json
import os
import re
import sys
import urllib.request

REPO = "astral-sh/python-build-standalone"
UV_REPO = "astral-sh/uv"

TARGET_MAP = {
    "x86_64-unknown-linux-gnu": "linux-x86_64",
    "aarch64-unknown-linux-gnu": "linux-aarch64",
    "powerpc64le-unknown-linux-gnu": "linux-ppc64le",
    "ppc64le-unknown-linux-gnu": "linux-ppc64le",
    "s390x-unknown-linux-gnu": "linux-s390x",
    "armv7-unknown-linux-gnueabihf": "linux-armv7",
    "x86_64-unknown-linux-musl": "linux-x86_64-musl",
    "aarch64-unknown-linux-musl": "linux-aarch64-musl",
    "x86_64-apple-darwin": "macos-x86_64",
    "aarch64-apple-darwin": "macos-aarch64",
    "x86_64-pc-windows-msvc": "windows-x86_64",
    "aarch64-pc-windows-msvc": "windows-aarch64",
    "i686-pc-windows-msvc": "windows-x86",
}

# Regex matching: cpython-{version}+{release_tag}-{target}-install_only.tar.gz
ASSET_REGEX = re.compile(
    r"^cpython-(?P<version>\d+\.\d+\.\d+)\+(?P<tag>\d{8})-(?P<target>[\w-]+)-install_only\.tar\.gz$"
)

UV_VERSION_REGEX = re.compile(r"^\d+\.\d+\.\d+$")

DEFAULT_KEEP_PER_MINOR = 12
DEFAULT_MAX_UV_VERSIONS = 40


def github_get(url, token=None):
    headers = {
        "User-Agent": "Jenkins-Python-Plugin-Updater",
        "Accept": "application/vnd.github.v3+json",
    }
    if token:
        headers["Authorization"] = f"token {token}"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"[ERROR] Failed to fetch {url}: {e}", file=sys.stderr)
        sys.exit(1)


def fetch_releases(token=None, limit=30):
    print(f"[INFO] Querying GitHub Releases from {REPO}...")
    return github_get(f"https://api.github.com/repos/{REPO}/releases?per_page={limit}", token)


def fetch_uv_versions(token=None, limit=30):
    print(f"[INFO] Querying GitHub Releases from {UV_REPO}...")
    releases = github_get(f"https://api.github.com/repos/{UV_REPO}/releases?per_page={limit}", token)
    versions = []
    for release in releases:
        if release.get("draft") or release.get("prerelease"):
            continue
        tag = (release.get("tag_name") or "").lstrip("v")
        if UV_VERSION_REGEX.match(tag):
            versions.append(tag)
    return versions


def parse_releases(releases):
    catalog = {}

    for release in releases:
        tag = release.get("tag_name")
        for asset in release.get("assets", []):
            name = asset.get("name", "")
            if "install_only" not in name or "stripped" in name or not name.endswith(".tar.gz"):
                continue

            match = ASSET_REGEX.match(name)
            if not match:
                continue

            version = match.group("version")
            target = match.group("target")

            platform_key = TARGET_MAP.get(target)
            if not platform_key:
                continue

            if version not in catalog:
                catalog[version] = {
                    "releaseTag": tag,
                    "platforms": {},
                }

            if platform_key not in catalog[version]["platforms"]:
                catalog[version]["platforms"][platform_key] = {
                    "url": asset.get("browser_download_url"),
                    "size": asset.get("size", 0),
                    "filename": name,
                }

    full_catalog = {}
    for ver, data in catalog.items():
        platforms = data["platforms"]
        if "linux-x86_64" in platforms and "windows-x86_64" in platforms:
            full_catalog[ver] = data

    return full_catalog


def version_key(v):
    try:
        return [int(x) for x in v.split(".")]
    except ValueError:
        return [0, 0, 0]


def sort_versions(versions_dict):
    sorted_keys = sorted(versions_dict.keys(), key=version_key, reverse=True)
    return {k: versions_dict[k] for k in sorted_keys}


def load_existing(path):
    """Returns the parsed existing catalog, or an empty skeleton if unavailable."""
    if path and os.path.isfile(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, dict):
                return data
        except (OSError, ValueError) as e:
            print(f"[WARN] Could not read existing catalog {path}: {e}", file=sys.stderr)
    return {}


def merge_python_versions(existing, fresh):
    """
    Merges freshly parsed releases into the existing versions without ever
    removing a version. For versions that already exist, only missing platforms
    are added so that published download URLs stay stable.
    """
    merged = {ver: data for ver, data in existing.items()}
    for ver, data in fresh.items():
        if ver not in merged:
            merged[ver] = data
            continue
        platforms = merged[ver].setdefault("platforms", {})
        for key, download in data["platforms"].items():
            platforms.setdefault(key, download)
    return merged


def prune_per_minor(versions_dict, keep_per_minor):
    """Keeps only the newest `keep_per_minor` patch releases of each major.minor line (0 = keep all)."""
    if keep_per_minor <= 0:
        return versions_dict
    kept = {}
    counts = {}
    for ver in sorted(versions_dict.keys(), key=version_key, reverse=True):
        minor = ".".join(ver.split(".")[:2])
        counts[minor] = counts.get(minor, 0) + 1
        if counts[minor] <= keep_per_minor:
            kept[ver] = versions_dict[ver]
    return kept


def merge_uv_versions(existing, fresh, max_versions):
    merged = set(v for v in existing if UV_VERSION_REGEX.match(str(v))) | set(fresh)
    ordered = sorted(merged, key=version_key, reverse=True)
    return ordered[:max_versions] if max_versions > 0 else ordered


def default_catalog_path():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    candidate1 = os.path.join(script_dir, "..", "src", "main", "resources", "io", "jenkins", "plugins", "python", "versions.json")
    candidate2 = os.path.join(script_dir, "..", "Plugins", "python-tool-jenkins-plugin", "src", "main", "resources", "io", "jenkins", "plugins", "python", "versions.json")
    if os.path.exists(os.path.dirname(candidate1)):
        return os.path.abspath(candidate1)
    return os.path.abspath(candidate2)


def main():
    parser = argparse.ArgumentParser(description="Update the Python standalone and uv versions catalog.")
    parser.add_argument("--catalog", "-c", help="Path to versions.json (read as the merge base, then updated in place).")
    parser.add_argument("--token", "-t", help="GitHub API personal access token.")
    parser.add_argument("--limit", "-l", type=int, default=6, help="Number of python-build-standalone releases to query.")
    parser.add_argument("--keep-per-minor", type=int, default=DEFAULT_KEEP_PER_MINOR,
                        help="Newest patch releases to keep per Python minor line (0 = keep all).")
    parser.add_argument("--uv-limit", type=int, default=30, help="Number of uv releases to query.")
    parser.add_argument("--max-uv-versions", type=int, default=DEFAULT_MAX_UV_VERSIONS,
                        help="Maximum uv versions to keep in the catalog (0 = keep all).")
    args = parser.parse_args()

    target_file = os.path.abspath(args.catalog) if args.catalog else default_catalog_path()

    existing = load_existing(target_file)
    existing_versions = existing.get("versions", {}) if isinstance(existing.get("versions"), dict) else {}
    existing_uv = existing.get("uvVersions", []) if isinstance(existing.get("uvVersions"), list) else []

    releases = fetch_releases(token=args.token, limit=args.limit)
    fresh = parse_releases(releases)
    merged = merge_python_versions(existing_versions, fresh)
    merged = sort_versions(prune_per_minor(merged, args.keep_per_minor))

    uv_versions = merge_uv_versions(existing_uv, fetch_uv_versions(token=args.token, limit=args.uv_limit),
                                    args.max_uv_versions)

    unchanged = merged == existing_versions and uv_versions == existing_uv and existing.get("schemaVersion") == 1
    if unchanged:
        print(f"[INFO] Catalog already up to date ({len(merged)} Python versions, {len(uv_versions)} uv versions).")
        print(f"       {target_file}")
        return

    output_data = {
        "schemaVersion": 1,
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "totalVersions": len(merged),
        "versions": merged,
        "uvVersions": uv_versions,
    }

    os.makedirs(os.path.dirname(target_file), exist_ok=True)
    with open(target_file, "w", encoding="utf-8") as f:
        json.dump(output_data, f, indent=2)
        f.write("\n")

    added = [v for v in merged if v not in existing_versions]
    print(f"[SUCCESS] Wrote {len(merged)} Python versions and {len(uv_versions)} uv versions to:")
    print(f"          {target_file}")
    print(f"\nNew Python versions this run: {', '.join(added) if added else 'none'}")
    print("\nAvailable versions summary:")
    for ver in list(merged.keys())[:10]:
        print(f"  - Python {ver} (Platforms: {len(merged[ver]['platforms'])})")
    print(f"  - uv: {', '.join(uv_versions[:5])}{' ...' if len(uv_versions) > 5 else ''}")


if __name__ == "__main__":
    main()
