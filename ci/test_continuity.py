#!/usr/bin/env python3
"""Synthetic receipts: a valid two-run pair and deliberately broken controls."""
import importlib.util
import pathlib
import tempfile
import unittest

script = pathlib.Path(__file__).with_name("check-continuity.py")
spec = importlib.util.spec_from_file_location("continuity", script)
cont = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cont)

CERT = "a" * 64


def receipt(run, cert=CERT, abis=("arm64-v8a", "armeabi-v7a"), sha="b"*64):
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

    def test_fake_local_receipt(self):
        old = self.save("a.tsv", receipt(123).replace("run_id=123", "run_id=local"))
        new = self.save("b.tsv", receipt(124))
        with self.assertRaises(ValueError):
            cont.compare(old, new, CERT)


if __name__ == "__main__":
    unittest.main()
