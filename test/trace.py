#!/usr/bin/env python3
"""Query trace rows from sqlite and optionally dump memory payload bytes."""

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
            "Pretty-print trace rows (inst/dmem/smem), optionally filtered by "
            "address and IDs, and optionally dump memory payload bytes to a .bin file."
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
        choices=["inst", "dmem", "smem"],
        help="trace table to query (default: dmem)",
    )
    p.add_argument(
        "--cluster",
        type=parse_int,
        help="optional cluster_id filter",
    )
    p.add_argument(
        "--core",
        type=parse_int,
        help="optional core_id filter",
    )
    p.add_argument(
        "--warp",
        type=parse_int,
        help="optional warp filter (inst table only)",
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
    p.add_argument(
        "--regdata",
        action="store_true",
        help="print per-lane rs1/rs2 data for inst rows",
    )
    return p.parse_args()


def query_rows(
    conn: sqlite3.Connection,
    table: str,
    address_range: tuple[int, int] | None,
    kind: str,
    cluster: int | None,
    core: int | None,
    warp: int | None,
):
    where_clauses = ["1 = 1"]
    params: list[int] = []

    if cluster is not None:
        where_clauses.append("cluster_id = ?")
        params.append(cluster)
    if core is not None:
        where_clauses.append("core_id = ?")
        params.append(core)

    if table == "inst":
        if kind != "all":
            raise ValueError("--kind read/write is only supported for dmem/smem")
        if warp is not None:
            where_clauses.append("warp = ?")
            params.append(warp)
        if address_range is not None:
            start, end = address_range
            where_clauses.append("pc >= ? AND pc < ?")
            params.extend([start, end])
        sql = f"""
            SELECT
                id,
                cluster_id,
                core_id,
                warp,
                pc,
                lane_mask,
                has_rs1,
                rs1_id,
                rs1_data,
                has_rs2,
                rs2_id,
                rs2_data
            FROM {table}
            WHERE {" AND ".join(where_clauses)}
            ORDER BY id
        """
        return (sql, params, conn.execute(sql, params).fetchall())

    if warp is not None:
        raise ValueError("--warp is only supported for inst table")
    if kind == "read":
        where_clauses.append("store = 0")
    elif kind == "write":
        where_clauses.append("store = 1")
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
    return (sql, params, conn.execute(sql, params).fetchall())


def compact_sql(sql: str) -> str:
    return " ".join(sql.split())


def fmt_hex(value: int, nbytes: int) -> str:
    return f"0x{value:0{nbytes * 2}x}"


def fmt_reg_id(has_reg: int, reg_id: int) -> str:
    if not has_reg:
        return "-"
    return f"x{reg_id}"


def fmt_reg_data(has_reg: int, reg_data: str) -> str:
    if not has_reg:
        return "-"
    lanes = []
    if reg_data:
        lanes = [f"{fmt_hex(parse_int(value), 4)}" for value in reg_data.split(",")]
    return f"[{', '.join(lanes)}]"


def print_table(rows, table: str, regdata: bool):
    if table == "inst":
        headers = ["id", "cluster", "core", "warp", "pc", "mask", "rs1", "rs2"]
        if regdata:
            headers.extend(["rs1_data", "rs2_data"])
        str_rows = []
        for row in rows:
            cells = [
                str(row[0]),
                str(row[1]),
                str(row[2]),
                str(row[3]),
                fmt_hex(row[4], 4),
                fmt_hex(row[5], 2),
                fmt_reg_id(row[6], row[7]),
                fmt_reg_id(row[9], row[10]),
            ]
            if regdata:
                cells.extend(
                    [
                        fmt_reg_data(row[6], row[8]),
                        fmt_reg_data(row[9], row[11]),
                    ]
                )
            str_rows.append(cells)
    else:
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
    # trace DB stores request/load data already shifted to the LSB
    size = max(1, size)
    mask = (1 << (size * 8)) - 1
    payload = (data & mask).to_bytes(size, byteorder="little", signed=False)

    payload_end = address + len(payload)
    clip_lo = max(start, address)
    clip_hi = min(end, payload_end)
    if clip_lo >= clip_hi:
        return b""

    begin = clip_lo - address
    stop = clip_hi - address
    return payload[begin:stop]


def dump_image(rows, start: int, end: int) -> bytes:
    fragments: list[tuple[int, bytes]] = []
    for row in rows:
        address = row[5]
        payload = clipped_payload(row, start, end)
        if not payload:
            continue
        fragments.append((max(start, address), payload))

    image = bytearray()
    cursor = start
    for fragment_start, payload in sorted(fragments, key=lambda fragment: fragment[0]):
        if fragment_start > cursor:
            gap_start = cursor
            gap_end = fragment_start
            raise ValueError(
                "trace rows do not fully cover requested dump range: "
                f"gap at [{fmt_hex(gap_start, 4)}, {fmt_hex(gap_end, 4)})"
            )

        overlap = max(0, cursor - fragment_start)
        if overlap < len(payload):
            image.extend(payload[overlap:])
            cursor += len(payload) - overlap

    if cursor != end:
        raise ValueError(
            "trace rows do not fully cover requested dump range: "
            f"gap at [{fmt_hex(cursor, 4)}, {fmt_hex(end, 4)})"
        )
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
    if args.table == "inst" and args.dump_bin:
        print("error: --dump-bin is only supported for dmem/smem tables", file=sys.stderr)
        return 2

    conn = sqlite3.connect(str(args.db))
    try:
        sql, params, rows = query_rows(
            conn,
            args.table,
            args.address,
            args.kind,
            args.cluster,
            args.core,
            args.warp,
        )
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    finally:
        conn.close()

    if args.address is None:
        range_str = "all"
    else:
        range_str = f"[{fmt_hex(args.address[0], 4)}, {fmt_hex(args.address[1], 4)})"
    print(f"# sql={compact_sql(sql)}")
    print(f"# params={params}")
    print(
        f"# table={args.table} range={range_str} rows={len(rows)} "
        f"kind={args.kind} cluster={args.cluster} core={args.core} warp={args.warp}"
    )
    print_table(rows, args.table, args.regdata)

    if args.dump_bin:
        dump_start, dump_end = args.address if args.address is not None else infer_range_from_rows(rows)
        try:
            blob = dump_image(rows, dump_start, dump_end)
        except ValueError as e:
            print(f"error: {e}", file=sys.stderr)
            return 2
        args.dump_bin.parent.mkdir(parents=True, exist_ok=True)
        args.dump_bin.write_bytes(blob)
        print(
            f"# wrote {len(blob)} bytes to {args.dump_bin} "
            f"(kind={args.kind}, layout=dense, range=[{fmt_hex(dump_start, 4)}, {fmt_hex(dump_end, 4)}))"
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
