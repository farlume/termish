import contextlib
import base64
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from publish_r2 import publish, required_config, validate_release, verify_public_object


class FakeS3:
    def __init__(self, fail_key=None):
        self.objects = {}
        self.fail_key = fail_key

    def put_object(self, **kwargs):
        if kwargs["Key"] == self.fail_key:
            raise OSError("upload failed")
        self.objects[kwargs["Key"]] = kwargs


class PublishR2Test(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.client = FakeS3()
        public_check = patch("publish_r2.verify_public_object")
        self.public_check = public_check.start()
        self.addCleanup(public_check.stop)
        self.config = {
            "bucket": "termish",
            "prefix": "downloads", "base": "https://downloads.example.com",
        }
        self.release = {
            "tag_name": "v1.7.1", "draft": False, "prerelease": False,
            "published_at": "2026-09-05T07:00:00Z",
            "html_url": "https://github.com/example/termish/releases/tag/v1.7.1",
            "assets": [],
        }
        sums = []
        for ext in ("apk", "aab"):
            name = f"Termish-1.7.1-release.{ext}"
            body = f"signed release fixture {ext}".encode()
            digest = hashlib.sha256(body).hexdigest()
            (self.directory / name).write_bytes(body)
            sums.append(f"{digest}  {name}\n")
            self.release["assets"].append({
                "name": name, "size": len(body), "digest": f"sha256:{digest}",
                "browser_download_url": f"https://github.com/example/termish/releases/download/v1.7.1/{name}",
            })
        (self.directory / "SHA256SUMS").write_text("".join(sums))

    def upload(self, latest="v1.7.1"):
        with contextlib.redirect_stdout(io.StringIO()):
            return publish(self.client, self.config, self.release, self.directory, lambda: latest)

    def test_latest_release_has_direct_download_and_publishes_version_last(self):
        result = self.upload()
        self.assertEqual(list(self.client.objects)[-1], "downloads/version.json")
        metadata = json.loads(self.client.objects["downloads/version.json"]["Body"])
        self.assertEqual(metadata["version"], "v1.7.1")
        self.assertEqual(result["download_url"], "https://downloads.example.com/downloads/releases/v1.7.1/Termish-1.7.1-release.apk")
        self.assertTrue(result["github_download_url"].endswith("/Termish-1.7.1-release.apk"))
        self.assertEqual(self.client.objects["downloads/termish.apk"]["Body"], (self.directory / "Termish-1.7.1-release.apk").read_bytes())
        self.assertEqual(self.client.objects["downloads/version.json"]["CacheControl"], "no-cache")
        self.assertEqual(self.public_check.call_count, 9)
        for uploaded in self.client.objects.values():
            self.assertEqual(uploaded["ContentMD5"], base64.b64encode(hashlib.md5(uploaded["Body"]).digest()).decode())
            self.assertNotIn("EnableMD5", uploaded)
            self.assertNotIn("ACL", uploaded)

    def test_private_or_broken_download_does_not_announce_new_version(self):
        self.public_check.side_effect = RuntimeError("public download failed")
        with self.assertRaisesRegex(RuntimeError, "public download failed"):
            self.upload()
        self.assertNotIn("downloads/version.json", self.client.objects)
        self.assertNotIn("downloads/termish.apk", self.client.objects)

    def test_all_release_assets_are_downloadable_before_version_is_written(self):
        checked = []
        def check(url, body):
            if url.endswith("/version.json"):
                for ext in ("apk", "aab"):
                    self.assertTrue(any(path.endswith(f"Termish-1.7.1-release.{ext}") for path in checked))
            checked.append(url)
        self.public_check.side_effect = check
        self.upload()
        self.assertTrue(checked[-1].endswith("/version.json"))

    def test_old_release_only_updates_its_archive(self):
        self.upload(latest="v1.8.0")
        self.assertTrue(self.client.objects)
        self.assertTrue(all(key.startswith("downloads/releases/v1.7.1/") for key in self.client.objects))

    def test_corrupted_asset_stops_before_any_upload(self):
        (self.directory / "Termish-1.7.1-release.aab").write_bytes(b"corrupt")
        with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
            self.upload()
        self.assertFalse(self.client.objects)

    def test_mismatching_github_digest_stops_before_any_upload(self):
        self.release["assets"][0]["digest"] = "sha256:" + "0" * 64
        with self.assertRaisesRegex(ValueError, "GitHub asset digest mismatch"):
            self.upload()
        self.assertFalse(self.client.objects)

    def test_failed_upload_does_not_announce_new_version(self):
        self.client.fail_key = "downloads/termish.aab"
        with self.assertRaises(OSError):
            self.upload()
        self.assertNotIn("downloads/version.json", self.client.objects)

    def test_latest_tag_is_checked_after_archive_upload(self):
        def latest_tag():
            self.assertIn("downloads/releases/v1.7.1/release.json", self.client.objects)
            return "v1.8.0"
        with contextlib.redirect_stdout(io.StringIO()):
            publish(self.client, self.config, self.release, self.directory, latest_tag)
        self.assertNotIn("downloads/version.json", self.client.objects)

    def test_rejects_drafts_prereleases_and_unsafe_tags(self):
        for changes in ({"draft": True}, {"prerelease": True}, {"tag_name": "../../release"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_release({**self.release, **changes})

    def test_configuration_does_not_expose_credentials(self):
        with self.assertRaises(ValueError) as error:
            required_config({"R2_ACCESS_KEY_ID": "do-not-log-this"})
        self.assertIn("R2_SECRET_ACCESS_KEY", str(error.exception))
        self.assertNotIn("do-not-log-this", str(error.exception))

    def test_configuration_defaults_and_unsafe_urls(self):
        env = {"R2_ACCESS_KEY_ID": "id", "R2_SECRET_ACCESS_KEY": "key", "R2_BUCKET": "termish", "R2_ACCOUNT_ID": "a" * 32, "R2_PUBLIC_BASE_URL": "https://downloads.example.com/"}
        config = required_config(env)
        self.assertEqual(config["base"], "https://downloads.example.com")
        self.assertEqual(config["prefix"], "downloads")
        self.assertEqual(config["endpoint"], "https://" + "a" * 32 + ".r2.cloudflarestorage.com")
        for changes in (
            {"R2_PREFIX": "../root"}, {"R2_PUBLIC_BASE_URL": "https://user:password@example.com"},
            {"R2_PUBLIC_BASE_URL": config["endpoint"]}, {"R2_PUBLIC_BASE_URL": "https://example.com/downloads"},
            {"R2_PUBLIC_BASE_URL": ""}, {"R2_ACCOUNT_ID": "not-an-account"},
        ):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                required_config({**env, **changes})


class PublicDownloadTest(unittest.TestCase):
    def test_verifies_complete_anonymous_response(self):
        body = b"release package" * 100000
        with patch("publish_r2.urlopen", return_value=io.BytesIO(body)) as open_url, contextlib.redirect_stdout(io.StringIO()):
            verify_public_object("https://downloads.example.com/app.apk", body)
        request = open_url.call_args.args[0]
        self.assertFalse(request.has_header("Authorization"))

    def test_retries_transient_public_access_failure(self):
        error = HTTPError("https://downloads.example.com/app.apk", 404, "Not Found", {}, io.BytesIO())
        with patch("publish_r2.urlopen", side_effect=[error, io.BytesIO(b"apk")]) as open_url, patch("publish_r2.time.sleep"), contextlib.redirect_stdout(io.StringIO()):
            verify_public_object("https://downloads.example.com/app.apk", b"apk")
        self.assertEqual(open_url.call_count, 2)

    def test_rejects_private_and_corrupted_downloads(self):
        for returned in (b"", b"bad", b"too long"):
            with self.subTest(returned=returned), patch("publish_r2.urlopen", side_effect=lambda *_args, **_kw: io.BytesIO(returned)), patch("publish_r2.time.sleep"), self.assertRaisesRegex(RuntimeError, "Public download verification failed"):
                verify_public_object("https://downloads.example.com/app.apk", b"apk")
        with patch("publish_r2.urlopen", side_effect=HTTPError("https://downloads.example.com/app.apk", 403, "Forbidden", {}, io.BytesIO())), patch("publish_r2.time.sleep"), self.assertRaises(RuntimeError):
            verify_public_object("https://downloads.example.com/app.apk", b"apk")


if __name__ == "__main__":
    unittest.main()
