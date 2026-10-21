#!/usr/bin/env python3
"""Synthetic receipts: a valid two-run pair and deliberately broken controls."""
import importlib.util
import hashlib
import pathlib
import tempfile
import unittest

script = pathlib.Path(__file__).with_name("check-continuity.py")
spec = importlib.util.spec_from_file_location("continuity", script)
cont = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cont)

CERT = "a" * 64


def receipt(run, cert=CERT, abis=("arm64-v8a", "armeabi-v7a"), sha="b"*40):
    return "\n".join(
        "\t".join((f"source_sha={sha}", f"run_id={run}", "run_attempt=1",
                   f"abi={abi}", f"file=app-{abi}-stableDev.apk",
                   f"apk_sha256={'c' * 64}", f"cert_sha256={cert}"))
        for abi in abis) + "\n"


class ContinuityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)

    def save(self, name, contents):
        path = pathlib.Path(self.temp.name, name)
        path.write_text(contents, encoding="utf-8")
        return path

    def test_distinct_runs_and_fixed_certificate(self):
        old = self.save("a.tsv", receipt(123))
        new = self.save("b.tsv", receipt(124))
        self.assertEqual(cont.compare(old, new, CERT)["run_2"], "124")

    def test_changed_signer_is_rejected(self):
        old = self.save("a.tsv", receipt(123))
        new = self.save("b.tsv", receipt(124, "d" * 64))
        with self.assertRaises(ValueError):
            cont.compare(old, new, CERT)

    def test_replayed_run_is_rejected(self):
        old = self.save("a.tsv", receipt(123))
        new = self.save("b.tsv", receipt(123))
        with self.assertRaises(ValueError):
            cont.compare(old, new, CERT)

    def test_one_abi_missing(self):
        old = self.save("a.tsv", receipt(123, abis=("arm64-v8a",)))
        new = self.save("b.tsv", receipt(124))
        with self.assertRaises(ValueError):
            cont.compare(old, new, CERT)

    def test_two_abis_different_certificates(self):
        old = self.save("a.tsv", receipt(123))
        changed = receipt(124).splitlines()
        changed[1] = changed[1].replace("cert_sha256=" + CERT,
                                        "cert_sha256=" + "e" * 64)
        new = self.save("b.tsv", "\n".join(changed) + "\n")
        with self.assertRaises(ValueError):
            cont.compare(old, new, CERT)

    def test_actual_apk_hashes_and_external_ci_identities(self):
        old_apks = pathlib.Path(self.temp.name, "old")
        new_apks = pathlib.Path(self.temp.name, "new")
        old_apks.mkdir()
        new_apks.mkdir()
        blob = b"cryptographically inspected sample apk bytes"
        hashed = hashlib.sha256(blob).hexdigest()
        names = ("app-arm64-v8a-stableDev.apk", "app-armeabi-v7a-stableDev.apk")
        for directory in (old_apks, new_apks):
            for name in names:
                (directory / name).write_bytes(blob)
        before = receipt(123).replace("c"*64, hashed)
        after = receipt(124).replace("c"*64, hashed)
        old = self.save("old-hashed.tsv", before)
        new = self.save("new-hashed.tsv", after)
        result = cont.compare(old, new, CERT,
                              previous_dir=old_apks, current_dir=new_apks,
                              previous_run=123, current_run=124,
                              previous_source="b"*40, current_source="b"*40)
        self.assertEqual(result["run_2"], "124")
        (old_apks / names[0]).write_bytes(blob + b"tamper")
        with self.assertRaisesRegex(ValueError, "actual signed APK bytes"):
            cont.compare(old, new, CERT, previous_dir=old_apks,
                         current_dir=new_apks)

    def test_external_run_identity_mismatch(self):
        old = self.save("old.tsv", receipt(123))
        new = self.save("new.tsv", receipt(124))
        with self.assertRaisesRegex(ValueError, "externally read GitHub"):
            cont.compare(old, new, CERT, previous_run=999)
        with self.assertRaisesRegex(ValueError, "source commit"):
            cont.compare(old, new, CERT, previous_source="c"*40)

    def test_fake_local_receipt(self):
        old = self.save("a.tsv", receipt(123).replace("run_id=123", "run_id=local"))
        new = self.save("b.tsv", receipt(124))
        with self.assertRaises(ValueError):
            cont.compare(old, new, CERT)


if __name__ == "__main__":
    unittest.main()
