#!/usr/bin/env python3
"""Query dmem trace rows in an address range and optionally dump payload bytes."""

import argparse
import sqlite3
import sys
from pathlib import Path


def parse_int(text: str) -> int:
    try:
        return int(text, 0)
    except ValueError as e:
        raise argparse.ArgumentTypeError(f"invalid integer value: {text}") from e


def parse_address_range(text: str) -> tuple[int, int]:
    parts = text.split(",", maxsplit=1)
    if len(parts) != 2:
        raise argparse.ArgumentTypeError(
            "address must be in 'start,end' format (e.g. 0x1000,0x2000)"
        )
    start = parse_int(parts[0].strip())
    end = parse_int(parts[1].strip())
    if end <= start:
        raise argparse.ArgumentTypeError("address end must be greater than start")
    return (start, end)


def parse_args():
    p = argparse.ArgumentParser(
        description=(
            "Pretty-print dmem rows, optionally filtered by address range, and "
            "optionally dump payload bytes to a .bin file."
        )
    )
    p.add_argument("db", type=Path, help="path to sqlite database")
    p.add_argument(
        "--address",
        type=parse_address_range,
        help="optional address filter in 'start,end' format (inclusive,exclusive)",
    )
    p.add_argument(
        "--table",
        default="dmem",
        choices=["dmem", "smem"],
        help="memory table to query (default: dmem)",
    )
    p.add_argument(
        "--dump-bin",
        type=Path,
        help="optional output .bin path for payload bytes from rows in range",
    )
    p.add_argument(
        "--kind",
        default="all",
        choices=["all", "read", "write"],
        help="which rows to include for stdout and --dump-bin (default: all)",
    )
    return p.parse_args()


def query_rows(
    conn: sqlite3.Connection, table: str, address_range: tuple[int, int] | None, kind: str
):
    if kind == "read":
        kind_clause = "store = 0"
    elif kind == "write":
        kind_clause = "store = 1"
    else:
        kind_clause = "1 = 1"

    where_clauses = [kind_clause]
    params: list[int] = []
    if address_range is not None:
        start, end = address_range
        where_clauses.append("address >= ? AND address < ?")
        params.extend([start, end])

    sql = f"""
        SELECT id, cluster_id, core_id, lane_id, store, address, size, data
        FROM {table}
        WHERE {" AND ".join(where_clauses)}
        ORDER BY id
    """
    return conn.execute(sql, params).fetchall()


def fmt_hex(value: int, nbytes: int) -> str:
    return f"0x{value:0{nbytes * 2}x}"


def print_table(rows):
    headers = ["id", "cluster", "core", "lane", "op", "address", "size", "data"]
    str_rows = []
    for row in rows:
        op = "W" if row[4] else "R"
        str_rows.append(
            [
                str(row[0]),
                str(row[1]),
                str(row[2]),
                str(row[3]),
                op,
                fmt_hex(row[5], 4),
                str(row[6]),
                fmt_hex(row[7], max(1, row[6])),
            ]
        )

    widths = [len(h) for h in headers]
    for r in str_rows:
        for i, cell in enumerate(r):
            widths[i] = max(widths[i], len(cell))

    def line(cells):
        return " | ".join(c.ljust(widths[i]) for i, c in enumerate(cells))

    print(line(headers))
    print("-+-".join("-" * w for w in widths))
    for r in str_rows:
        print(line(r))


def clipped_payload(row, start: int, end: int) -> bytes:
    address = row[5]
    size = int(row[6])
    data = int(row[7])
    # Trace DB stores memory data as a 32-bit beat. Reconstruct request-sized
    # payload bytes from that beat using the byte offset in the address.
    beat_nbytes = 4
    beat = (data & 0xFFFF_FFFF).to_bytes(beat_nbytes, byteorder="little", signed=False)
    offset = int(address) % beat_nbytes
    size = max(1, size)
    avail = beat_nbytes - offset
    if size <= avail:
        payload = beat[offset:offset + size]
    else:
        # Crossing beat boundary is rare for this trace format; preserve known
        # bytes and pad tail with zeros rather than failing.
        payload = beat[offset:] + (b"\x00" * (size - avail))

    payload_end = address + len(payload)
    clip_lo = max(start, address)
    clip_hi = min(end, payload_end)
    if clip_lo >= clip_hi:
        return b""

    begin = clip_lo - address
    stop = clip_hi - address
    return payload[begin:stop]


def dump_image(rows, start: int, end: int) -> bytes:
    size = max(0, end - start)
    image = bytearray(size)
    for row in rows:
        address = row[5]
        payload = clipped_payload(row, start, end)
        if not payload:
            continue
        image_off = max(start, address) - start
        image[image_off:image_off + len(payload)] = payload
    return bytes(image)


def infer_range_from_rows(rows) -> tuple[int, int]:
    if not rows:
        return (0, 0)
    start = min(int(row[5]) for row in rows)
    end = max(int(row[5]) + max(1, int(row[6])) for row in rows)
    return (start, end)


def main():
    args = parse_args()
    if not args.db.exists():
        print(f"error: sqlite file not found: {args.db}", file=sys.stderr)
        return 2

    conn = sqlite3.connect(str(args.db))
    try:
        rows = query_rows(conn, args.table, args.address, args.kind)
    finally:
        conn.close()

    if args.address is None:
        range_str = "all"
    else:
        range_str = f"[{fmt_hex(args.address[0], 4)}, {fmt_hex(args.address[1], 4)})"
    print(f"# table={args.table} range={range_str} rows={len(rows)} kind={args.kind}")
    print_table(rows)

    if args.dump_bin:
        dump_start, dump_end = args.address if args.address is not None else infer_range_from_rows(rows)
        blob = dump_image(rows, dump_start, dump_end)
        args.dump_bin.parent.mkdir(parents=True, exist_ok=True)
        args.dump_bin.write_bytes(blob)
        print(
            f"# wrote {len(blob)} bytes to {args.dump_bin} "
            f"(kind={args.kind}, layout=image, range=[{fmt_hex(dump_start, 4)}, {fmt_hex(dump_end, 4)}))"
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
