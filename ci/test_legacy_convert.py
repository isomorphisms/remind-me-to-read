#!/usr/bin/env python3
"""Reconstruct old SQLite schemas and verify that TAR→ZIP is lossless/fail-closed."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sqlite3
import tarfile
import tempfile
import unittest
import zipfile

script = Path(__file__).with_name("legacy-to-portable.py")
spec = importlib.util.spec_from_file_location("legacy_converter", script)
convert = importlib.util.module_from_spec(spec)
spec.loader.exec_module(convert)

TITLE_FIELDS = {"title", "author", "epub_path", "source_uri", "source_sha1",
                "label", "sha1", "filename", "raw_position"}
SAMPLE_BYTES = b"Sample ASCII and UTF-8 reading content\n"


def produce(root, version=3, no_book=False, bad_sha=False, orphan=False):
    dbfile = root / "reading.sqlite"
    database = sqlite3.connect(dbfile)
    database.execute(f"PRAGMA user_version={version}")
    for table, columns in convert.DB_COLUMNS.items():
        if version < 3 and table == "readera_positions":
            continue
        if version < 2 and table == "bookmarks":
            continue
        present = [name for name in columns if not (
            table == "books" and (version < 3 and name in {"source_uri", "source_sha1"}
                                  or version < 2 and name in {"char_offset", "content_length"})
        )]
        schema = [f"{name} " + (
            "REAL" if name == "ratio" else "TEXT" if name in TITLE_FIELDS else "INTEGER"
        ) for name in present]
        database.execute(f"CREATE TABLE {table} ({', '.join(schema)})")
    row = {
        "id": 1, "title": "Sample TXT", "author": "A", "kind": 2,
        "epub_path": "/data/user/0/org.isomorphisms.remindmetoread/files/epubs/sample.txt",
        "source_uri": "content://obsolete/folder/file", "source_sha1": (
            "0" * 40 if bad_sha else hashlib.sha1(SAMPLE_BYTES).hexdigest()),
        "chapter": 0, "scroll_y": 7, "char_offset": 9,
        "content_length": 29, "current_page": 0, "total_pages": 0,
        "added_ms": 2, "last_read_ms": 22, "total_seconds": 20, "reading_since_ms": 0
    }
    present = [x[1] for x in database.execute("PRAGMA table_info(books)")]
    database.execute(f"INSERT INTO books ({', '.join(present)}) VALUES ({','.join('?' for _ in present)})",
                     [row[col] for col in present])
    if version >= 2:
        database.execute("INSERT INTO bookmarks VALUES (1,1,0,9,0,'a mark',23)")
    database.execute("INSERT INTO sessions VALUES (1,1,2,22,20)")
    if version == 3:
        database.execute(
            "INSERT INTO readera_positions VALUES (?,?,?,?,?,?,?)",
            ("f"*40, "ReadEra sample", "foreign.txt", 0.75, 11, "xpath1", 12))
    database.commit()
    database.close()
    archive = root / "old.tar"
    with tarfile.open(archive, "w") as writer:
        for name, data in [
            ("databases/reading.sqlite", dbfile.read_bytes()),
            *([] if no_book else [("files/epubs/sample.txt", SAMPLE_BYTES)]),
            *([("files/epubs/orphan.epub", b"orphan") ] if orphan else [])
        ]:
            info = tarfile.TarInfo(name)
            info.size = len(data)
            writer.addfile(info, io.BytesIO(data))
    return archive


class LegacyMigrationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.output = self.root / "converted.zip"

    def test_v3_complete_books_markers_sessions_and_readera(self):
        old = produce(self.root)
        receipt = convert.convert(old, self.output)
        self.assertEqual(receipt["books"], 1)
        self.assertEqual(receipt["bookmarks"], 1)
        self.assertEqual(receipt["sessions"], 1)
        self.assertEqual(receipt["readera_positions"], 1)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(archive.read("books/1.txt"), SAMPLE_BYTES)
            records = json.loads(archive.read("database.json"))
            self.assertEqual(records["books"][0]["char_offset"], 9)
            self.assertEqual(records["books"][0]["epub_path"], "books/1.txt")
            self.assertIsNone(records["books"][0]["source_uri"])
            self.assertEqual(records["sessions"][0]["seconds"], 20)
        self.assertTrue(old.exists(), "original rescue TAR must survive conversion")

    def test_v2_known_schema_upgrade_not_a_guess(self):
        old = produce(self.root, version=2)
        convert.convert(old, self.output)
        with zipfile.ZipFile(self.output) as archive:
            records = json.loads(archive.read("database.json"))
            self.assertIsNone(records["books"][0]["source_uri"])
            self.assertIsNone(records["books"][0]["source_sha1"])
            self.assertEqual(records["bookmarks"][0]["char_offset"], 9)
            self.assertEqual(records["readera_positions"], [])

    def test_missing_private_book_rejected(self):
        with self.assertRaises(ValueError):
            convert.convert(produce(self.root, no_book=True), self.output)
        self.assertFalse(self.output.exists())

    def test_sha_mismatch_rejected(self):
        with self.assertRaises(ValueError):
            convert.convert(produce(self.root, bad_sha=True), self.output)
        self.assertFalse(self.output.exists())

    def test_orphan_private_book_rejected(self):
        with self.assertRaises(ValueError):
            convert.convert(produce(self.root, orphan=True), self.output)
        self.assertFalse(self.output.exists())

    def test_never_overwrite_prior_export(self):
        old = produce(self.root)
        self.output.write_bytes(b"keep this backup")
        with self.assertRaises(ValueError):
            convert.convert(old, self.output)
        self.assertEqual(self.output.read_bytes(), b"keep this backup")


if __name__ == "__main__":
    unittest.main()
