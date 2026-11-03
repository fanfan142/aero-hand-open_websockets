from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]


class ReleaseChannelContractTest(unittest.TestCase):
    def test_candidate_tags_are_prereleases_in_both_publishers(self) -> None:
        for workflow in ("release.yml", "firmware_release.yml"):
            with self.subTest(workflow=workflow):
                source = (REPO_ROOT / ".github" / "workflows" / workflow).read_text(
                    encoding="utf-8"
                )
                self.assertIn(
                    "prerelease: ${{ contains(github.ref_name, '-') }}", source
                )
                self.assertIn("make_latest: false", source)

    def test_candidate_android_release_cannot_be_promoted_to_latest(self) -> None:
        source = (REPO_ROOT / ".github" / "workflows" / "release.yml").read_text(
            encoding="utf-8"
        )
        promotion = source.split(
            "- name: Mark release latest after APK and firmware assets are ready", 1
        )[1]
        self.assertIn("if: success() && !contains(github.ref_name, '-')", promotion)


if __name__ == "__main__":
    unittest.main()
