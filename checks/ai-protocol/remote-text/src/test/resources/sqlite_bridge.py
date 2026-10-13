"""Test transport only: execute bound SQL on a real owned SQLite connection.

No source, consent, candidate, job, or reference business rules live here.
SQL and values arrive from the directly compiled product implementation.
"""
import base64
import json
import os
from pathlib import Path
import sqlite3
import sys


def decode(value):
    if value == "N":
        return None
    tag, data = value.split(":", 1)
    if tag == "I":
        return int(data)
    if tag == "S":
        return base64.b64decode(data, validate=True).decode("utf-8", errors="strict")
    if tag == "B":
        return base64.b64decode(data, validate=True)
    raise ValueError("unsupported binding type")


def encode(value):
    if value is None:
        return "N"
    if isinstance(value, int):
        return "I:" + str(value)
    if isinstance(value, str):
        return "S:" + base64.b64encode(value.encode("utf-8", errors="strict")).decode("ascii")
    if isinstance(value, bytes):
        return "B:" + base64.b64encode(value).decode("ascii")
    raise ValueError("unsupported result type")


connection = sqlite3.connect(sys.argv[1], timeout=1.0, isolation_level=None)
try:
    connection.execute("PRAGMA foreign_keys=ON")
    connection.execute("PRAGMA busy_timeout=1000")
    mode = connection.execute("PRAGMA journal_mode=WAL").fetchone()[0]
    connection.execute("PRAGMA synchronous=FULL")
    if sys.argv[3] == "initialize":
        schema = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))["database"]
        connection.execute("BEGIN IMMEDIATE")
        try:
            for entity in schema["entities"]:
                connection.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
                for index in entity.get("indices", []):
                    connection.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            connection.execute("PRAGMA user_version=" + str(int(schema["version"])))
            connection.execute("COMMIT")
        except BaseException:
            connection.execute("ROLLBACK")
            raise
    print("HELLO\t" + str(os.getpid()) + "\t" + sqlite3.sqlite_version + "\t" + mode + "\t" +
          str(connection.execute("PRAGMA foreign_keys").fetchone()[0]), flush=True)
    for line in sys.stdin:
        fields = line.rstrip("\n").split("\t")
        request_id = fields[0]
        try:
            if fields[1] == "C":
                connection.close()
                print(request_id + "\tO\t0", flush=True)
                break
            operation, sql, count = fields[1], decode(fields[2]), int(fields[3])
            if count != len(fields) - 4:
                raise ValueError("binding count mismatch")
            cursor = connection.execute(sql, [decode(value) for value in fields[4:]])
            if operation == "Q":
                rows = cursor.fetchall()
                payload = [request_id, "Q", str(len(rows))]
                for row in rows:
                    payload.append(str(len(row)))
                    payload.extend(encode(value) for value in row)
                print("\t".join(payload), flush=True)
            elif operation == "X":
                print(request_id + "\tO\t" + str(max(cursor.rowcount, 0)), flush=True)
            else:
                raise ValueError("unsupported operation")
        except sqlite3.Error as failure:
            # Codes only: never expose SQL, paths, values, or a database exception message.
            print(request_id + "\tE\t" + str(getattr(failure, "sqlite_errorcode", -1)) + "\t" +
                  str(getattr(failure, "sqlite_errorname", "SQLITE_ERROR")), flush=True)
        except (ValueError, UnicodeError):
            print(request_id + "\tE\t-1\tBRIDGE_PROTOCOL", flush=True)
finally:
    connection.close()
