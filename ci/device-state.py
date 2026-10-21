#!/usr/bin/env python3
"""Independent *host-side* logical comparison of pre/post Android update snapshots.

Snapshots are from "adb exec-out run-as PACKAGE tar -cf - databases files".
Never restore or mutate the user's phone here.
"""
import hashlib
import json
import pathlib
import sqlite3
import sys
import tarfile
import tempfile

TABLES = ("books", "bookmarks", "sessions", "readera_positions")


def examine(tar_path, legacy=False):
    with tempfile.TemporaryDirectory(prefix="rmr-logical-state-") as directory:
        root = pathlib.Path(directory)
        file_hashes = {}
        database_files = {}
        with tarfile.open(tar_path, "r:*") as archive:
            for member in archive:
                name = member.name.removeprefix("./")
                if not member.isfile():
                    continue
                if name in ("databases/reading.sqlite", "databases/reading.sqlite-wal",
                            "databases/reading.sqlite-shm"):
                    f = archive.extractfile(member)
                    target = root / name.split("/")[-1]
                    with target.open("wb") as out:
                        while block := f.read(131072):
                            out.write(block)
                    database_files[name] = str(target)
                elif name.startswith("files/epubs/"):
                    if "/" in name[len("files/epubs/"):]:
                        raise ValueError("Unexpected nested private book path")
                    source = archive.extractfile(member)
                    digest = hashlib.sha256()
                    while block := source.read(131072):
                        digest.update(block)
                    if name in file_hashes:
                        raise ValueError("Duplicate private book")
                    file_hashes[name] = digest.hexdigest()

        database = database_files.get("databases/reading.sqlite")
        if not database:
            raise ValueError("Missing private reading.sqlite; refuse update acceptance")
        connection = sqlite3.connect(f"file:{pathlib.Path(database).as_posix()}?mode=ro", uri=True)
        try:
            rows = {}
            version = connection.execute("PRAGMA user_version").fetchone()[0]
            if legacy and version not in (1, 2, 3):
                raise ValueError("Unknown legacy private SQLite schema")
            for table in TABLES:
                key = "sha1" if table == "readera_positions" else "id"
                columns = [r[1] for r in connection.execute(f"PRAGMA table_info({table})")]
                if not columns:
                    if legacy and ((table == "readera_positions" and version < 3)
                                   or (table == "bookmarks" and version == 1)):
                        rows[table] = []
                        continue
                    raise ValueError(f"Missing database table {table}")
                rows[table] = [dict(zip(columns, row)) for row in
                               connection.execute(f"SELECT * FROM {table} ORDER BY {key}")]
        finally:
            connection.close()
        # Every saved EPUB/TXT path must have actual bytes in the archive.
        for book in rows["books"]:
            if book["kind"] != 1:
                source_path = book["epub_path"]
                if not source_path:
                    raise ValueError("Digital book has no private source path")
                basename = pathlib.PurePosixPath(source_path).name
                if "files/epubs/" + basename not in file_hashes:
                    raise ValueError("Missing backed-up bytes for book id " + str(book["id"]))
        state = {"tables": rows, "book_sha256": file_hashes}
        return state


def require_seed(state):
    books = state["tables"]["books"]
    kinds = {book["kind"] for book in books}
    if not {0, 1, 2}.issubset(kinds):
        raise ValueError("Seed C67/A1 device with EPUB, TXT and paper books before testing")
    if not state["tables"]["bookmarks"] or not state["tables"]["sessions"]:
        raise ValueError("Seed real bookmarks and completed reading sessions before testing")
    if not any(b["char_offset"] > 0 for b in books if b["kind"] != 1):
        raise ValueError("Seed a nonzero EPUB/TXT reading position")
    if not any(b["current_page"] > 0 for b in books if b["kind"] == 1):
        raise ValueError("Seed a nonzero physical-book page")
    if len(state["book_sha256"]) < 2:
        raise ValueError("Private EPUB and TXT bytes missing from device snapshot")


def digest(state):
    blob = json.dumps(state, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()
    return hashlib.sha256(blob).hexdigest()


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--verify-legacy":
        state = examine(sys.argv[2], legacy=True)
        print(f"verified_legacy_archive_state_sha256={digest(state)}")
        print(f"books={len(state['tables']['books'])} private_files={len(state['book_sha256'])}")
        return
    if len(sys.argv) not in (2, 3):
        raise SystemExit("Usage: device-state.py BEFORE.tar [AFTER.tar] | --verify-legacy ARCHIVE.tar")
    before = examine(sys.argv[1])
    require_seed(before)
    print(f"before_state_sha256={digest(before)}")
    for table in TABLES:
        print(f"before_{table}_rows={len(before['tables'][table])}")
    print(f"before_private_books={len(before['book_sha256'])}")
    if len(sys.argv) == 3:
        after = examine(sys.argv[2])
        require_seed(after)
        print(f"after_state_sha256={digest(after)}")
        if before != after:
            raise SystemExit("FAIL: Logical books, files, positions, bookmarks or sessions changed")
        print("PASS: complete seeded logical library unchanged across adb install -r")


if __name__ == "__main__":
    main()
