"""Exercise the native builder's Git checkout sequence against CRLF worktrees."""

import pathlib
import subprocess
import tempfile
import unittest


class NativeCheckoutEolTest(unittest.TestCase):
    def test_builder_normalizes_both_disposable_source_checkouts(self):
        builder = (
            pathlib.Path(__file__).resolve().parents[1]
            / "g2ray_app/scripts/build-libxray-aar.ps1"
        ).read_text(encoding="utf-8")
        for directory, ref in (("$WorkDir", "$LibXrayRef"), ("$xrayCoreDir", "$XrayCoreRef")):
            prefix = f"git -C {directory} -c core.autocrlf=false -c core.eol=lf "
            checkout = prefix + f"checkout --force {ref}"
            refresh = prefix + "checkout-index --force --all"
            self.assertIn(checkout, builder)
            self.assertIn(refresh, builder)
            self.assertLess(builder.index(checkout), builder.index(refresh))

    def test_checkout_from_autocrlf_true(self):
        self.check_checkout("true")

    def test_checkout_from_explicit_crlf_eol(self):
        self.check_checkout("false")

    def check_checkout(self, autocrlf):
        with tempfile.TemporaryDirectory(prefix="g2ray-native-eol-") as directory:
            root = pathlib.Path(directory)

            def git(*arguments):
                return subprocess.run(
                    ["git", "-C", str(root), *arguments],
                    check=True, capture_output=True, text=True,
                ).stdout.strip()

            git("init")
            git("config", "core.autocrlf", "false")
            source = root / "sample.go"
            original = b"package sample\n\nconst Value = 1\n"
            (root / ".gitattributes").write_bytes(b"*.go text\n")
            source.write_bytes(original)
            git("add", "sample.go", ".gitattributes")
            git("-c", "user.name=G2Ray fixture", "-c", "user.email=fixture@example.invalid",
                "-c", "commit.gpgsign=false", "commit", "-m", "Fixture")
            ref = git("rev-parse", "HEAD")
            git("config", "core.autocrlf", autocrlf)
            git("config", "core.eol", "crlf")
            source.unlink()
            git("checkout-index", "--force", "--all")
            self.assertIn(b"\r\n", source.read_bytes())

            options = ("-c", "core.autocrlf=false", "-c", "core.eol=lf")
            git(*options, "checkout", "--force", ref)
            git(*options, "checkout-index", "--force", "--all")
            self.assertEqual(original, source.read_bytes())
            patch = root / "change.patch"
            patch.write_bytes(
                b"diff --git a/sample.go b/sample.go\n"
                b"--- a/sample.go\n+++ b/sample.go\n@@ -1,3 +1,3 @@\n"
                b" package sample\n \n-const Value = 1\n+const Value = 2\n"
            )
            git("apply", "--check", str(patch))
            self.assertEqual(autocrlf, git("config", "core.autocrlf"))


if __name__ == "__main__":
    unittest.main()
