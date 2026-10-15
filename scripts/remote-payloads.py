#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Transfer locally signed service payloads to CI, without transferring keys."""
import argparse
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile

TARGETS = ("Darwin-arm64", "Darwin-x86_64", "Linux-aarch64", "Linux-x86_64")
FILES = tuple(
    f"{service}/build/binaries/{name}"
    for service, prefix in (("screenService", "termish-screen"), ("agentBridge", "termish-agent"))
    for name in ("manifest.json", *(f"{prefix}-{target}" for target in TARGETS))
)
METADATA = "remote-services.json"
LIMIT = 128 * 1024 * 1024


def commit_id(value):
    if not re.fullmatch(r"[a-f0-9]{40}", value):
        raise ValueError("Full commit SHA required")
    return value


def pack(archive, root, commit):
    root = root.resolve()
    commit_id(commit)
    with tarfile.open(archive, "w:gz") as output:
        for name in FILES:
            source = root / name
            if source.is_symlink() or not source.is_file():
                raise ValueError("Missing regular payload: " + name)
            output.add(source, arcname=name, recursive=False)
        body = json.dumps({"commit": commit}).encode()
        info = tarfile.TarInfo(METADATA)
        info.size = len(body)
        info.mode = 0o600
        output.addfile(info, io.BytesIO(body))


def unpack(archive, root, commit):
    root = root.resolve()
    commit_id(commit)
    expected = set(FILES) | {METADATA}
    bodies = {}
    total = 0
    with tarfile.open(archive, "r:gz") as source:
        for member in source:
            if member.name not in expected or member.name in bodies or not member.isfile():
                raise ValueError("Unexpected/duplicate/non-regular archive member")
            total += member.size
            if member.size < 0 or total > LIMIT:
                raise ValueError("Payload archive exceeds limit")
            with source.extractfile(member) as stream:
                bodies[member.name] = stream.read()
    if bodies.keys() != expected:
        raise ValueError("Incomplete payload archive")
    if json.loads(bodies.pop(METADATA)).get("commit") != commit:
        raise ValueError("Payload archive belongs to another commit")
    # No tar extraction: only exact, allowlisted paths are written, after the
    # complete archive and commit have been validated. Reject parent symlinks.
    for name in FILES:
        target = root / name
        if any(parent.is_symlink() for parent in (target, *target.parents)):
            raise ValueError("Symlink in payload destination")
    for name, body in bodies.items():
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + ".importing")
        if temporary.exists() or temporary.is_symlink():
            raise ValueError("Payload staging path already exists")
        with temporary.open("xb") as stream:
            stream.write(body)
        os.chmod(temporary, 0o600 if target.suffix == ".json" else 0o700)
        temporary.replace(target)


def unpack_bundle(archive, destination):
    files = {"Contents/Info.plist", "Contents/MacOS/Termish Helper",
             "Contents/Resources/AppIcon.icns", "Contents/_CodeSignature/CodeResources"}
    directories = {str(parent) for name in files for parent in Path(name).parents if str(parent) != "."}
    bodies, seen, total = {}, set(), 0
    with tarfile.open(archive, "r:gz") as source:
        for member in source:
            if member.name in seen:
                raise ValueError("Duplicate bundle member")
            seen.add(member.name)
            if member.isdir() and member.name in directories:
                continue
            if not member.isfile() or member.name not in files:
                raise ValueError("Unexpected bundle member")
            total += member.size
            if member.size < 0 or total > LIMIT:
                raise ValueError("Bundle exceeds size limit")
            with source.extractfile(member) as stream:
                bodies[member.name] = stream.read()
    if bodies.keys() != files:
        raise ValueError("Incomplete signed bundle")
    # destination is a fresh private directory owned by this verification run.
    for name, body in bodies.items():
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(body)
        target.chmod(0o700 if name.endswith("/Termish Helper") else 0o600)


def verify_macos(root):
    certificate = (root / ".github/macos-signing-certificate.sha1").read_text().strip()
    if not re.fullmatch(r"[A-Fa-f0-9]{40}", certificate):
        raise ValueError("Invalid signing certificate fingerprint")
    requirement = f'anchor apple generic and certificate leaf = H"{certificate}"'
    for target in TARGETS[:2]:
        with tempfile.TemporaryDirectory(prefix="termish-signature-") as temporary:
            app = Path(temporary) / "Termish Helper.app"
            unpack_bundle(root / f"screenService/build/binaries/termish-screen-{target}", app)
            subprocess.run(["codesign", "--verify", "--strict", "-R", "=" + requirement, str(app)], check=True)
            subprocess.run(["codesign", "-dv", "--verbose=2", str(app)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    for action in ("pack", "unpack"):
        command = commands.add_parser(action)
        command.add_argument("archive", type=Path)
        command.add_argument("commit", type=commit_id)
    commands.add_parser("verify-macos")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if args.action == "verify-macos":
        verify_macos(root)
    else:
        {"pack": pack, "unpack": unpack}[args.action](args.archive, root, args.commit)


if __name__ == "__main__":
    main()
