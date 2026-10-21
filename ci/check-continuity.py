#!/usr/bin/env python3
"""Compare TWO externally captured, verified signing receipts from different CI runs.

Certificates in this file are public SHA-256 fingerprints, not private keys.
A receipt alone does not authenticate its author: the independent AI-CI
finished-APK gate must still re-read APKs, and signed-run provenance is required.
"""
import argparse
import hashlib
import re
from pathlib import Path

HEX = re.compile(r"[0-9a-f]{64}\Z")
ABIS = {"arm64-v8a", "armeabi-v7a"}


def parse_receipt(path, directory=None, run_id=None, source_sha=None):
    rows = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        items = [part.split("=", 1) for part in line.split("\t")]
        if any(len(i) != 2 for i in items):
            raise ValueError(f"malformed receipt in {path}")
        row = dict(items)
        needed = {"source_sha", "run_id", "run_attempt", "abi", "file",
                  "apk_sha256", "cert_sha256"}
        if len(items) != len(needed) or set(row) != needed:
            raise ValueError("missing, duplicate, or unexpected receipt field")
        for name in ("source_sha", "apk_sha256", "cert_sha256"):
            if not HEX.fullmatch(row[name]):
                raise ValueError(f"invalid {name}")
        if not row["run_id"].isdigit() or not row["run_attempt"].isdigit():
            raise ValueError("not a production CI signing receipt")
        abi = row["abi"]
        if abi not in ABIS or abi in rows or abi not in row["file"]:
            raise ValueError("missing, duplicate, or mislabeled ABI")
        if Path(row["file"]).name != row["file"]:
            raise ValueError("Receipt contains a path instead of the APK basename")
        if directory is not None:
            signed_apk = Path(directory) / row["file"]
            if not signed_apk.is_file():
                raise ValueError("Received signed APK is missing")
            digest = hashlib.sha256()
            with signed_apk.open("rb") as source:
                while True:
                    block = source.read(131072)
                    if not block:
                        break
                    digest.update(block)
            if digest.hexdigest() != row["apk_sha256"]:
                raise ValueError("Receipt does not describe the actual signed APK bytes")
        rows[abi] = row
    if set(rows) != ABIS:
        raise ValueError("expected both ABI-specific APK receipts")
    if len({row["source_sha"] for row in rows.values()}) != 1:
        raise ValueError("ABI APKs came from different source revisions")
    if len({row["run_id"] for row in rows.values()}) != 1:
        raise ValueError("ABI APKs came from different CI runs")
    if len({row["run_attempt"] for row in rows.values()}) != 1:
        raise ValueError("ABI APKs came from different run attempts")
    certs = {row["cert_sha256"] for row in rows.values()}
    if len(certs) != 1:
        raise ValueError("ABI APKs do not share the same certificate")
    record = next(iter(rows.values()))
    if run_id is not None and record["run_id"] != str(run_id):
        raise ValueError("Receipt run ID differs from externally read GitHub Actions run")
    if source_sha is not None and record["source_sha"] != source_sha:
        raise ValueError("Receipt source commit differs from GitHub Actions run")
    return record["run_id"], next(iter(certs))


def compare(first, second, expected, previous_dir=None, current_dir=None,
            previous_run=None, current_run=None, previous_source=None, current_source=None):
    if not HEX.fullmatch(expected):
        raise ValueError("expected protected certificate fingerprint is invalid")
    run1, cert1 = parse_receipt(first, previous_dir, previous_run, previous_source)
    run2, cert2 = parse_receipt(second, current_dir, current_run, current_source)
    if run1 == run2:
        raise ValueError("same CI run reused; need two separate builds")
    if cert1 != expected or cert2 != expected:
        raise ValueError("development signer changed across runs")
    return {"run_1": run1, "run_2": run2, "certificate_sha256": expected}


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("earlier_receipt")
    p.add_argument("later_receipt")
    p.add_argument("expected_sha256", help="protected public certificate fingerprint")
    p.add_argument("--previous-dir", help="previous actual signed APK directory")
    p.add_argument("--current-dir", help="current actual signed APK directory")
    p.add_argument("--previous-run", help="externally authenticated previous CI run ID")
    p.add_argument("--current-run", help="externally authenticated current CI run ID")
    p.add_argument("--previous-source", help="previous GitHub Actions HEAD SHA")
    p.add_argument("--current-source", help="current GitHub Actions HEAD SHA")
    args = p.parse_args()
    try:
        print("PASS " + str(compare(
            args.earlier_receipt, args.later_receipt, args.expected_sha256,
            previous_dir=args.previous_dir, current_dir=args.current_dir,
            previous_run=args.previous_run, current_run=args.current_run,
            previous_source=args.previous_source, current_source=args.current_source)))
    except (OSError, ValueError) as exception:
        p.exit(1, "FAIL: " + str(exception) + "\n")
