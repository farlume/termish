import contextlib
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from publish_cos import publish, required_config, validate_release


class FakeCos:
    def __init__(self, fail_key=None):
        self.objects = {}
        self.fail_key = fail_key

    def put_object(self, **kwargs):
        if kwargs["Key"] == self.fail_key:
            raise OSError("upload failed")
        self.objects[kwargs["Key"]] = kwargs


class PublishCosTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.client = FakeCos()
        self.config = {
            "bucket": "termish-123456", "region": "ap-guangzhou",
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
        self.assertEqual(result["download_url"], self.release["assets"][0]["browser_download_url"])
        self.assertEqual(result["cos_download_url"], "https://downloads.example.com/downloads/releases/v1.7.1/Termish-1.7.1-release.apk")
        self.assertTrue(result["github_download_url"].endswith("/Termish-1.7.1-release.apk"))
        self.assertEqual(self.client.objects["downloads/termish.apk"]["Body"], (self.directory / "Termish-1.7.1-release.apk").read_bytes())
        self.assertEqual(self.client.objects["downloads/version.json"]["CacheControl"], "no-cache")

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
            required_config({"COS_SECRET_ID": "do-not-log-this"})
        self.assertIn("COS_SECRET_KEY", str(error.exception))
        self.assertNotIn("do-not-log-this", str(error.exception))

    def test_configuration_defaults_and_unsafe_urls(self):
        env = {"COS_SECRET_ID": "id", "COS_SECRET_KEY": "key", "COS_BUCKET": "termish-123456", "COS_REGION": "ap-guangzhou"}
        self.assertEqual(required_config(env)["base"], "https://termish-123456.cos.ap-guangzhou.myqcloud.com")
        for changes in ({"COS_PREFIX": "../root"}, {"COS_PUBLIC_BASE_URL": "https://user:password@example.com"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                required_config({**env, **changes})


if __name__ == "__main__":
    unittest.main()
