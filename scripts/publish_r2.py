#!/usr/bin/env python3
"""Mirror a published GitHub release to R2; verify downloads before announcing it."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen


TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+")
MIME = {
    "apk": "application/vnd.android.package-archive",
    "aab": "application/octet-stream",
}


def validate_release(release: dict) -> str:
    tag = release.get("tag_name", "")
    if not TAG.fullmatch(tag) or release.get("draft") or release.get("prerelease"):
        raise ValueError("Only published stable releases with a vX.Y.Z tag can be uploaded")
    return tag


def required_config(env: dict) -> dict:
    names = ("R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_ACCOUNT_ID", "R2_BUCKET", "R2_PUBLIC_BASE_URL")
    missing = [name for name in names if not env.get(name, "").strip()]
    if missing:
        raise ValueError("Configure GitHub Environment r2-release: " + ", ".join(missing))
    prefix = env.get("R2_PREFIX", "downloads").strip().strip("/")
    if any(part in (".", "..", "") for part in prefix.split("/")):
        raise ValueError("R2_PREFIX must be a non-empty relative object prefix")
    bucket, account = env["R2_BUCKET"].strip(), env["R2_ACCOUNT_ID"].strip()
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,61}[a-z0-9]", bucket):
        raise ValueError("R2_BUCKET must be a 3-63 character bucket name")
    if not re.fullmatch(r"[a-fA-F0-9]{32}", account):
        raise ValueError("R2_ACCOUNT_ID must be the 32-character Cloudflare account ID")
    base = env["R2_PUBLIC_BASE_URL"].strip().rstrip("/")
    url = urlsplit(base)
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment or url.path:
        raise ValueError("R2_PUBLIC_BASE_URL must be an HTTPS bucket domain without a path, credentials or query")
    if url.hostname == "r2.cloudflarestorage.com" or url.hostname.endswith(".r2.cloudflarestorage.com"):
        raise ValueError("R2_PUBLIC_BASE_URL must be a public custom domain or r2.dev URL, not the private S3 API endpoint")
    return {"bucket": bucket, "endpoint": f"https://{account}.r2.cloudflarestorage.com", "prefix": prefix, "base": base}


def public_url(base: str, key: str) -> str:
    return base + "/" + quote(key, safe="/")


def verify_public_object(url: str, expected: bytes):
    """Read the public URL without credentials and verify its entire contents."""
    for attempt in range(3):
        try:
            request = Request(url, headers={
                "Cache-Control": "no-cache",
                "User-Agent": "Termish-Release-Check/1.0 (+https://termish.dev)",
            })
            with urlopen(request, timeout=30) as response:
                digest, size = hashlib.sha256(), 0
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > len(expected):
                        raise ValueError("Public download size exceeds the release asset")
                    digest.update(chunk)
                if size != len(expected) or digest.digest() != hashlib.sha256(expected).digest():
                    raise ValueError("Public download checksum does not match the release asset")
            print(f"Verified public download {url}")
            return
        except (URLError, OSError, ValueError) as error:
            if isinstance(error, HTTPError):
                error.close()
            if attempt == 2:
                raise RuntimeError(f"Public download verification failed: {url}. Check R2 public access, domain and cache settings.") from error
            time.sleep(attempt + 1)


def verify_assets(release: dict, directory: Path) -> dict:
    tag = validate_release(release)
    checksums = {}
    for line in (directory / "SHA256SUMS").read_text().splitlines():
        digest, name = line.split(maxsplit=1)
        name = name.lstrip("*")
        if not re.fullmatch(r"[a-fA-F0-9]{64}", digest) or name in checksums:
            raise ValueError("Invalid SHA256SUMS")
        checksums[name] = digest.lower()
    remote_assets = {asset["name"]: asset for asset in release["assets"]}
    verified = {}
    for extension in MIME:
        name = f"Termish-{tag[1:]}-release.{extension}"
        body = (directory / name).read_bytes()
        digest = hashlib.sha256(body).hexdigest()
        asset = remote_assets.get(name)
        if not body or digest != checksums.get(name):
            raise ValueError(f"Checksum mismatch: {name}")
        if not asset or asset["size"] != len(body):
            raise ValueError(f"Release asset size mismatch: {name}")
        if asset.get("digest") and asset["digest"] != f"sha256:{digest}":
            raise ValueError(f"GitHub asset digest mismatch: {name}")
        verified[extension] = {
            "name": name, "sha256": digest, "size": len(body),
            "github_url": asset["browser_download_url"], "body": body,
        }
    return verified


def publish(client, config: dict, release: dict, directory: Path, latest_tag) -> dict:
    # Validate every file before the first write, including the checksums published by CI.
    tag = validate_release(release)
    files = verify_assets(release, directory)
    prefix, base = config["prefix"], config["base"]
    archive = f"{prefix}/releases/{tag}"

    def put(key: str, body: bytes, content_type: str, *, immutable=False, filename=None):
        options = {"ContentDisposition": f'attachment; filename="{filename}"'} if filename else {}
        client.put_object(
            Bucket=config["bucket"], Key=key, Body=body,
            ContentMD5=base64.b64encode(hashlib.md5(body).digest()).decode(),
            ContentType=content_type,
            CacheControl="public, max-age=31536000, immutable" if immutable else "no-store, max-age=0",
            **options,
        )
        print(f"Uploaded {key}")
        verify_public_object(public_url(base, key), body)

    assets = {}
    for extension, asset in files.items():
        key = f"{archive}/{asset['name']}"
        put(key, asset["body"], MIME[extension], immutable=True, filename=asset["name"])
        assets[extension] = {field: value for field, value in asset.items() if field != "body"}
        assets[extension]["url"] = public_url(base, key)
    checksum_key = f"{archive}/SHA256SUMS"
    put(checksum_key, (directory / "SHA256SUMS").read_bytes(), "text/plain; charset=utf-8", immutable=True)
    metadata = {
        "version": tag,
        "version_name": tag[1:],
        "published_at": release["published_at"],
        "release_url": release["html_url"],
        "download_url": assets["apk"]["url"],
        "github_download_url": assets["apk"]["github_url"],
        "sha256": assets["apk"]["sha256"],
        "sha256sums_url": public_url(base, checksum_key),
        "assets": assets,
    }
    encoded = (json.dumps(metadata, ensure_ascii=False, indent=2) + "\n").encode()
    put(f"{archive}/release.json", encoded, "application/json; charset=utf-8")

    # Re-query GitHub after uploading the archive: manually replaying an older tag
    # must not roll the website back. Workflows serialize all R2 uploads.
    if latest_tag() != tag:
        print(f"Archived {tag}; latest download pointers were not changed")
        return metadata
    for extension, asset in files.items():
        put(f"{prefix}/termish.{extension}", asset["body"], MIME[extension], filename=asset["name"])
    stable_checksums = "".join(f"{asset['sha256']}  termish.{ext}\n" for ext, asset in files.items())
    put(f"{prefix}/SHA256SUMS", stable_checksums.encode(), "text/plain; charset=utf-8")
    put(f"{prefix}/release.json", encoded, "application/json; charset=utf-8")
    # Readers see the new version only after every referenced release file exists.
    put(f"{prefix}/version.json", encoded, "application/json; charset=utf-8")
    return metadata


def github_release(repo: str, tag: str = "") -> dict:
    if tag and not TAG.fullmatch(tag):
        raise ValueError("Tag must have the form vX.Y.Z")
    endpoint = f"repos/{repo}/releases/" + (f"tags/{tag}" if tag else "latest")
    return json.loads(subprocess.check_output(["gh", "api", endpoint], text=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", default="", help="Published vX.Y.Z tag; default is the latest stable release")
    args = parser.parse_args()
    config = required_config(os.environ)
    repo = os.environ["GITHUB_REPOSITORY"]
    release = github_release(repo, args.tag)
    tag = validate_release(release)

    import boto3
    from botocore.config import Config

    client = boto3.client(
        "s3", endpoint_url=config["endpoint"], region_name="auto",
        aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
        config=Config(
            signature_version="s3v4", request_checksum_calculation="when_required",
            response_checksum_validation="when_required",
            retries={"mode": "standard", "max_attempts": 3},
        ),
    )
    with tempfile.TemporaryDirectory(prefix="termish-r2-") as temp:
        subprocess.run([
            "gh", "release", "download", tag, "--repo", repo, "--dir", temp,
            "--pattern", f"Termish-{tag[1:]}-release.apk",
            "--pattern", f"Termish-{tag[1:]}-release.aab", "--pattern", "SHA256SUMS",
        ], check=True)
        metadata = publish(client, config, release, Path(temp), lambda: github_release(repo)["tag_name"])
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as report:
            report.write(f"R2 release uploaded and public downloads verified: {tag}\n\n[Release APK]({metadata['download_url']})\n")


if __name__ == "__main__":
    main()
