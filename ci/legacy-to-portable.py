#!/usr/bin/env python3
"""Convert a VERIFIED run-as legacy rescue TAR to the application's complete ZIP.

This does not touch a phone, install/uninstall APKs, modify the original TAR,
or overwrite any prior ZIP. Run the independent AI-CI archive oracle afterward.
Older SQLite v1/v2 columns are filled only according to BookStore.onUpgrade.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import sqlite3
import tarfile
import tempfile
import zipfile

DB_COLUMNS = {
    "books": ["id", "title", "author", "kind", "epub_path", "source_uri",
              "source_sha1", "chapter", "scroll_y", "char_offset", "content_length",
              "current_page", "total_pages", "added_ms", "last_read_ms",
              "total_seconds", "reading_since_ms"],
    "bookmarks": ["id", "book_id", "chapter", "char_offset", "page",
                  "label", "saved_ms"],
    "sessions": ["id", "book_id", "started_ms", "ended_ms", "seconds"],
    "readera_positions": ["sha1", "title", "filename", "ratio", "last_read_ms",
                          "raw_position", "imported_ms"],
}
MIGRATIONS = {
    "books": {
        "char_offset": (1, 0),
        "content_length": (1, 0),
        "source_uri": (2, None),
        "source_sha1": (2, None),
    }
}
MAX_ARCHIVE = 1024 ** 3
MAX_BOOK = 100 * 1024 ** 2
MAX_DB = 16 * 1024 ** 2
MAX_ROWS = 100000
MAX_BOOKS = 10000


def fail(message):
    raise ValueError(message)


def tar_members(archive):
    indexed = {}
    for member in archive.getmembers():
        name = member.name.removeprefix("./")
        parts = PurePosixPath(name).parts
        if name.startswith("/") or ".." in parts:
            fail("unsafe legacy TAR path")
        if not member.isfile():
            continue
        if name in indexed:
            fail("duplicate legacy TAR entry: " + name)
        indexed[name] = member
    for required in ("databases/reading.sqlite",):
        if required not in indexed:
            fail("legacy TAR is missing private reading.sqlite")
    return indexed


def legacy_database(connection):
    version = connection.execute("PRAGMA user_version").fetchone()[0]
    if type(version) is not int or not 1 <= version <= 3:
        fail("unsupported legacy database version")
    database = {}
    for table, expected_columns in DB_COLUMNS.items():
        cur = connection.execute(f"PRAGMA table_info({table})")
        actual = [item["name"] for item in cur]
        if not actual:
            if table == "readera_positions" and version < 3:
                database[table] = []
                continue
            if table == "bookmarks" and version == 1:
                database[table] = []
                continue
            fail("missing reading table: " + table)
        unexpected = set(actual) - set(expected_columns)
        if unexpected:
            fail("unrecognized legacy columns in " + table)
        missing = set(expected_columns) - set(actual)
        for name in missing:
            rule = MIGRATIONS.get(table, {}).get(name)
            if rule is None or version > rule[0]:
                fail("unrecognized legacy schema gap: " + table + "." + name)
        records = []
        for entry in connection.execute(f"SELECT * FROM {table}"):
            if len(records) >= (MAX_BOOKS if table == "books" else MAX_ROWS):
                fail("too many legacy database records")
            row = dict(entry)
            for name in missing:
                row[name] = MIGRATIONS[table][name][1]
            records.append({key: row[key] for key in expected_columns})
        database[table] = records
    return database


def append_bytes(archive, target, name, length_limit, source):
    digest = hashlib.sha256()
    first_sha1 = hashlib.sha1()
    count = 0
    with archive.open(name, mode="w") as output:
        while True:
            piece = source.read(16384)
            if not piece:
                break
            count += len(piece)
            if count > length_limit:
                fail("legacy book exceeds portable backup size limit: " + name)
            output.write(piece)
            digest.update(piece)
            first_sha1.update(piece)
    return {
        "name": name, "bytes": count, "sha256": digest.hexdigest()
    }, first_sha1.hexdigest()


def convert(source_path, output_path):
    output = Path(output_path)
    if output.exists() or Path(str(output) + ".partial").exists():
        fail("refusing to overwrite any existing rescue conversion")
    temp_output = Path(str(output) + ".partial")
    try:
        with tarfile.open(source_path, "r:*") as source:
            members = tar_members(source)
            with tempfile.TemporaryDirectory(prefix="reading-sqlite-rescue-") as directory:
                root = Path(directory)
                for basename in ("reading.sqlite", "reading.sqlite-wal", "reading.sqlite-shm"):
                    path = "databases/" + basename
                    if path not in members:
                        continue
                    entry = members[path]
                    if entry.size > MAX_DB:
                        fail("legacy SQLite component exceeds rescue profile")
                    destination = root / basename
                    with source.extractfile(entry) as inp, destination.open("wb") as out:
                        while data := inp.read(16384):
                            out.write(data)
                connection = sqlite3.connect(f"file:{root / 'reading.sqlite'}?mode=ro", uri=True)
                connection.row_factory = sqlite3.Row
                try:
                    database = legacy_database(connection)
                finally:
                    connection.close()
            used = set()
            total = 0
            file_list = []
            with zipfile.ZipFile(temp_output, "w", compression=zipfile.ZIP_DEFLATED,
                                 allowZip64=True) as target:
                for book in database["books"]:
                    kind, ident = book["kind"], book["id"]
                    if type(ident) is not int or ident <= 0 or ident > 999999999999999999:
                        fail("invalid legacy book id")
                    if kind == 1:
                        if book["epub_path"] is not None:
                            fail("paper book unexpectedly references a file")
                        continue
                    if kind not in (0, 2):
                        fail("unsupported legacy book kind")
                    original = book["epub_path"]
                    if not isinstance(original, str):
                        fail("digital book missing original private path")
                    # All imported books in this reader live in files/epubs; refuse
                    # to follow an unexpected path or silently drop an orphan.
                    parts = PurePosixPath(original).parts
                    if len(parts) < 4 or parts[-2] != "epubs" or parts[-3] != "files":
                        fail("digital book not inside expected private files/epubs directory")
                    basename = parts[-1]
                    suffix = ".txt" if kind == 2 else ".epub"
                    if not basename.endswith(suffix):
                        fail("private document extension does not match book kind")
                    member = "files/epubs/" + basename
                    if member not in members:
                        fail("missing referenced EPUB/TXT bytes from legacy rescue: " + member)
                    used.add(member)
                    target_name = "books/" + str(ident) + suffix
                    with source.extractfile(members[member]) as oldfile:
                        recorded, sha1 = append_bytes(
                            target, source, target_name, MAX_BOOK, oldfile)
                    if book["source_sha1"] not in (None, "") and (
                        book["source_sha1"].lower() != sha1
                    ):
                        fail("private book bytes differ from stored SHA-1")
                    file_list.append(recorded)
                    book["epub_path"] = target_name
                    # Original SAF grants do not carry across installations.
                    book["source_uri"] = None
                    total += recorded["bytes"]
                    if total > MAX_ARCHIVE:
                        fail("portable backup would exceed 1 GiB")
                # Refuse to declare a backup complete if orphan imported EPUB/TXT
                # files remain outside database references.
                orphans = {
                    path for path in members if path.startswith("files/epubs/")
                    and path.endswith((".epub", ".txt"))
                } - used
                if orphans:
                    fail("unreferenced private book files; preserve TAR and inspect: " +
                         ", ".join(sorted(orphans)[:3]))
                database_bytes = json.dumps(database, separators=(",", ":"),
                                           ensure_ascii=False).encode("utf-8")
                if len(database_bytes) > MAX_DB:
                    fail("legacy database exceeds portable manifest profile")
                with target.open("database.json", "w") as db_out:
                    db_out.write(database_bytes)
                file_list.append({
                    "name": "database.json", "bytes": len(database_bytes),
                    "sha256": hashlib.sha256(database_bytes).hexdigest()
                })
                manifest = {"format": "org.isomorphisms.remindmetoread.backup",
                            "version": 1, "files": file_list}
                target.writestr("manifest.json", json.dumps(
                    manifest, separators=(",", ":")).encode("utf-8"))
        if temp_output.stat().st_size > MAX_ARCHIVE:
            fail("compressed conversion exceeds size limit")
        # Portable verifier is separately owned by AI-CI. Do not claim that
        # a self-produced manifest authenticates the original device.
        os.replace(temp_output, output)
        return {
            "books": len(database["books"]),
            "private_file_count": len(used),
            "bookmarks": len(database["bookmarks"]),
            "sessions": len(database["sessions"]),
            "readera_positions": len(database["readera_positions"]),
            "output_sha256": hashlib.file_digest(
                output.open("rb"), "sha256").hexdigest()
        }
    finally:
        if temp_output.exists():
            temp_output.unlink()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("verified_legacy_tar")
    ap.add_argument("new_portable_zip")
    args = ap.parse_args()
    try:
        print(json.dumps(convert(args.verified_legacy_tar, args.new_portable_zip),
                         sort_keys=True))
    except (OSError, ValueError, sqlite3.DatabaseError, tarfile.TarError,
            zipfile.BadZipFile) as error:
        ap.exit(1, f"FAIL: {error}\n")
