# SPDX-License-Identifier: MIT
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("remote_payloads", Path(__file__).parents[1] / "remote-payloads.py")
payloads = importlib.util.module_from_spec(spec)
spec.loader.exec_module(payloads)
COMMIT = "a" * 40


class ArchiveTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.archive = self.root / "payloads.tar.gz"
        self.destination = self.root / "destination"

    def write_archive(self, extra=None, commit=COMMIT):
        with tarfile.open(self.archive, "w:gz") as output:
            entries = [(name, b"payload") for name in payloads.FILES]
            entries.append((payloads.METADATA, json.dumps({"commit": commit}).encode()))
            if extra:
                entries.append(extra)
            for name, body in entries:
                info = tarfile.TarInfo(name)
                info.size = len(body)
                output.addfile(info, io.BytesIO(body))

    def test_round_trip_preserves_signed_payload_bytes(self):
        source = self.root / "source"
        for name in payloads.FILES:
            target = source / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(name.encode())
        payloads.pack(self.archive, source, COMMIT)
        payloads.unpack(self.archive, self.destination, COMMIT)
        for name in payloads.FILES:
            self.assertEqual((source / name).read_bytes(), (self.destination / name).read_bytes())

    def test_traversal_and_duplicates_are_rejected_before_any_write(self):
        for name in ("../outside", "/tmp/outside", payloads.FILES[0]):
            self.write_archive((name, b"bad"))
            with self.assertRaises(ValueError):
                payloads.unpack(self.archive, self.destination, COMMIT)
            self.assertFalse(self.destination.exists())

    def test_wrong_commit_is_rejected_before_any_write(self):
        self.write_archive(commit="b" * 40)
        with self.assertRaisesRegex(ValueError, "another commit"):
            payloads.unpack(self.archive, self.destination, COMMIT)
        self.assertFalse(self.destination.exists())

    def test_links_are_rejected(self):
        with tarfile.open(self.archive, "w:gz") as output:
            info = tarfile.TarInfo(payloads.FILES[0])
            info.type = tarfile.SYMTYPE
            info.linkname = "/tmp/outside"
            output.addfile(info)
        with self.assertRaises(ValueError):
            payloads.unpack(self.archive, self.destination, COMMIT)

    def test_symlink_destination_is_rejected(self):
        self.write_archive()
        self.destination.mkdir()
        (self.destination / "screenService").symlink_to(self.root / "outside")
        with self.assertRaisesRegex(ValueError, "Symlink"):
            payloads.unpack(self.archive, self.destination, COMMIT)
        self.assertFalse((self.root / "outside").exists())

    def test_missing_payloads_are_rejected(self):
        with tarfile.open(self.archive, "w:gz"):
            pass
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            payloads.unpack(self.archive, self.destination, COMMIT)

    def test_bundle_rejects_links_and_traversal_before_writing(self):
        for name, kind in (("../outside", tarfile.REGTYPE), ("Contents/Info.plist", tarfile.SYMTYPE)):
            with tarfile.open(self.archive, "w:gz") as output:
                info = tarfile.TarInfo(name)
                info.type = kind
                info.linkname = "/tmp/outside" if kind == tarfile.SYMTYPE else ""
                output.addfile(info)
            with self.assertRaises(ValueError):
                payloads.unpack_bundle(self.archive, self.destination)
            self.assertFalse(self.destination.exists())

    @unittest.skipUnless(sys.platform == "darwin", "macOS signature verification")
    def test_wrong_certificate_cannot_pass_real_signature_verification(self):
        source = Path(__file__).parents[2] / "screenService/build/binaries/termish-screen-Darwin-arm64"
        if not source.is_file():
            self.skipTest("Build a signed macOS service payload first")
        certificate = self.destination / ".github/macos-signing-certificate.sha1"
        certificate.parent.mkdir(parents=True)
        certificate.write_text("0" * 40)
        binary = self.destination / "screenService/build/binaries" / source.name
        binary.parent.mkdir(parents=True)
        shutil.copyfile(source, binary)
        with self.assertRaises(subprocess.CalledProcessError):
            payloads.verify_macos(self.destination)


if __name__ == "__main__":
    unittest.main()
