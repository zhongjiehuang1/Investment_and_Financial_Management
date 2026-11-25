#!/usr/bin/env python3
"""Import verified bank transactions through the authenticated income/expense API.

Amounts use exact minor units; dates use YYYY-MM-DD. The selected owner and account
are pinned to immutable generations for the lifetime of the import. Private source
files and checkpoints must remain outside published packages. Session ownership is
verified before the first write; no caller-supplied user ID is sent to the API.
"""

from __future__ import annotations

import argparse
import getpass
import hashlib
import http.cookiejar
import json
import os
import re
import struct
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import (
    HTTPCookieProcessor,
    HTTPRedirectHandler,
    Request,
    build_opener,
)

# ----------------------------- Default configuration -----------------------------
DEFAULT_XLS = "2024-09-18---2026-07-31.xls"
DEFAULT_BASE_URL = "http://localhost:8080/Investment_and_Financial_Management"
# Servlet endpoint names are case-sensitive.
DEFAULT_ENDPOINT = "/addIncomeOrExpense"
DEFAULT_LOGIN_ENDPOINT = "/login"
DEFAULT_PLATFORM = "邮政储蓄银行活期账户"
EXPECTED_USER_ID = 2
DEFAULT_CHECKPOINT = ".income_expense_import_checkpoint.json"

# Compound File Binary / BIFF constants
FREESECT = 0xFFFFFFFF
ENDOFCHAIN = 0xFFFFFFFE


class ImportErrorWithMessage(RuntimeError):
    """An import error safe to show to the operator."""


def money_minor(value: Any) -> int:
    """Parse an amount String as integer minor units, without floating-point arithmetic."""
    text = str(value).strip()
    if len(text) > 64 or re.fullmatch(r"-?[0-9]+(?:\.[0-9]{1,2})?", text) is None:
        raise ImportErrorWithMessage("金额必须是最多两位小数的十进制文本")
    negative = text.startswith("-")
    unsigned = text[1:] if negative else text
    whole, _, fraction = unsigned.partition(".")
    minor = int(whole + fraction.ljust(2, "0"))
    return -minor if negative else minor


def money_text(minor: int) -> str:
    """Insert the decimal point into integer minor units."""
    digits = str(abs(minor)).rjust(3, "0")
    return ("-" if minor < 0 else "") + digits[:-2] + "." + digits[-2:]



class NoRedirectHandler(HTTPRedirectHandler):
    """Do not follow redirects that can conceal an expired session."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: N802
        return None


class CompoundFile:
    """Read the OLE Compound File container used by legacy XLS files."""

    def __init__(self, path: Path) -> None:
        self.data = path.read_bytes()
        header = self.data[:512]
        if header[:8] != bytes.fromhex("D0CF11E0A1B11AE1"):
            raise ImportErrorWithMessage(f"{path} 不是有效的旧版 Excel .xls 文件")

        self.sector_size = 1 << struct.unpack_from("<H", header, 30)[0]
        self.mini_sector_size = 1 << struct.unpack_from("<H", header, 32)[0]
        self.num_fat_sectors = struct.unpack_from("<I", header, 44)[0]
        self.directory_start = struct.unpack_from("<I", header, 48)[0]
        self.mini_stream_cutoff = struct.unpack_from("<I", header, 56)[0]
        self.mini_fat_start = struct.unpack_from("<I", header, 60)[0]
        self.num_mini_fat_sectors = struct.unpack_from("<I", header, 64)[0]
        difat_start = struct.unpack_from("<I", header, 68)[0]
        num_difat_sectors = struct.unpack_from("<I", header, 72)[0]

        difat = [
            value
            for value in struct.unpack_from("<109I", header, 76)
            if value not in (FREESECT, ENDOFCHAIN)
        ]

        sector_id = difat_start
        for _ in range(num_difat_sectors):
            sector = self._sector(sector_id)
            entry_count = self.sector_size // 4 - 1
            difat.extend(
                value
                for value in struct.unpack_from(f"<{entry_count}I", sector, 0)
                if value != FREESECT
            )
            sector_id = struct.unpack_from("<I", sector, self.sector_size - 4)[0]
            if sector_id == ENDOFCHAIN:
                break

        self.fat: list[int] = []
        for fat_sector_id in difat[: self.num_fat_sectors]:
            self.fat.extend(
                struct.unpack(
                    f"<{self.sector_size // 4}I", self._sector(fat_sector_id)
                )
            )

        directory_data = self._read_chain(self.directory_start)
        self.directory_entries: list[tuple[str, int, int, int]] = []
        for offset in range(0, len(directory_data), 128):
            entry = directory_data[offset : offset + 128]
            if len(entry) < 128:
                break
            name_length = struct.unpack_from("<H", entry, 64)[0]
            name = (
                entry[: max(0, name_length - 2)].decode("utf-16le", "replace")
                if name_length >= 2
                else ""
            )
            object_type = entry[66]
            start_sector = struct.unpack_from("<I", entry, 116)[0]
            stream_size = struct.unpack_from("<Q", entry, 120)[0]
            self.directory_entries.append(
                (name, object_type, start_sector, stream_size)
            )

        try:
            root = next(entry for entry in self.directory_entries if entry[1] == 5)
        except StopIteration as exc:
            raise ImportErrorWithMessage("无法读取 .xls 的根目录") from exc

        self.mini_fat: list[int] = []
        if (
            self.num_mini_fat_sectors
            and self.mini_fat_start not in (FREESECT, ENDOFCHAIN)
        ):
            mini_fat_data = self._read_chain(
                self.mini_fat_start, max_sectors=self.num_mini_fat_sectors
            )
            usable_length = len(mini_fat_data) // 4 * 4
            self.mini_fat = list(
                struct.unpack(
                    f"<{usable_length // 4}I", mini_fat_data[:usable_length]
                )
            )

        root_start, root_size = root[2], root[3]
        self.mini_stream = (
            self._read_chain(root_start)[:root_size] if root_size else b""
        )

    def _sector(self, sector_id: int) -> bytes:
        offset = 512 + sector_id * self.sector_size
        return self.data[offset : offset + self.sector_size]

    def _read_chain(self, start_sector: int, max_sectors: int | None = None) -> bytes:
        chunks: list[bytes] = []
        seen: set[int] = set()
        sector_id = start_sector
        while (
            sector_id not in (FREESECT, ENDOFCHAIN)
            and sector_id < len(self.fat)
            and sector_id not in seen
        ):
            seen.add(sector_id)
            chunks.append(self._sector(sector_id))
            if max_sectors is not None and len(chunks) >= max_sectors:
                break
            sector_id = self.fat[sector_id]
        return b"".join(chunks)

    def _read_mini_chain(self, start_sector: int, size: int) -> bytes:
        chunks: list[bytes] = []
        seen: set[int] = set()
        sector_id = start_sector
        while (
            sector_id not in (FREESECT, ENDOFCHAIN)
            and sector_id < len(self.mini_fat)
            and sector_id not in seen
            and len(chunks) * self.mini_sector_size < size
        ):
            seen.add(sector_id)
            offset = sector_id * self.mini_sector_size
            chunks.append(self.mini_stream[offset : offset + self.mini_sector_size])
            sector_id = self.mini_fat[sector_id]
        return b"".join(chunks)[:size]

    def read_stream(self, name: str) -> bytes:
        try:
            entry = next(item for item in self.directory_entries if item[0] == name)
        except StopIteration as exc:
            raise ImportErrorWithMessage(f".xls 中找不到 {name!r} 数据流") from exc

        _, _, start_sector, size = entry
        if size < self.mini_stream_cutoff:
            return self._read_mini_chain(start_sector, size)
        return self._read_chain(start_sector)[:size]


def iter_biff_records(
    data: bytes, start: int = 0, end: int | None = None
) -> Iterable[tuple[int, int, bytes]]:
    end = len(data) if end is None else end
    position = start
    while position + 4 <= end:
        record_id, length = struct.unpack_from("<HH", data, position)
        record_offset = position
        position += 4
        payload = data[position : position + length]
        yield record_offset, record_id, payload
        position += length


def decode_rk(value: int) -> float:
    divide_by_100 = bool(value & 1)
    is_integer = bool(value & 2)
    if is_integer:
        result = struct.unpack("<i", struct.pack("<I", value))[0] >> 2
    else:
        raw_double = (value & 0xFFFFFFFC) << 32
        result = struct.unpack("<d", struct.pack("<Q", raw_double))[0]
    return result / 100 if divide_by_100 else result


class SegmentedReader:
    """Read an SST record and its CONTINUE segments."""

    def __init__(self, segments: list[bytes]) -> None:
        self.segments = segments
        self.segment_index = 0
        self.position = 0

    def read_raw(self, length: int) -> bytes:
        output = bytearray()
        while length:
            if self.segment_index >= len(self.segments):
                raise EOFError("SST 数据提前结束")
            available = len(self.segments[self.segment_index]) - self.position
            if available == 0:
                self.segment_index += 1
                self.position = 0
                continue
            take = min(length, available)
            output.extend(
                self.segments[self.segment_index][
                    self.position : self.position + take
                ]
            )
            self.position += take
            length -= take
        return bytes(output)

    def read_characters(self, character_count: int, wide: bool) -> str:
        parts: list[str] = []
        remaining = character_count
        current_wide = wide

        while remaining:
            if self.segment_index >= len(self.segments):
                raise EOFError("SST 字符串数据提前结束")

            available = len(self.segments[self.segment_index]) - self.position
            if available == 0:
                self.segment_index += 1
                self.position = 0
                if self.segment_index >= len(self.segments):
                    raise EOFError("SST CONTINUE 数据缺失")
                # A string continued into another record restates its encoding in the first byte.
                current_wide = bool(self.segments[self.segment_index][0] & 1)
                self.position = 1
                available = len(self.segments[self.segment_index]) - self.position

            bytes_per_character = 2 if current_wide else 1
            take = min(remaining, available // bytes_per_character)
            if take == 0:
                self.segment_index += 1
                self.position = 0
                if self.segment_index >= len(self.segments):
                    raise EOFError("SST CONTINUE 数据缺失")
                current_wide = bool(self.segments[self.segment_index][0] & 1)
                self.position = 1
                continue

            byte_count = take * bytes_per_character
            raw = self.segments[self.segment_index][
                self.position : self.position + byte_count
            ]
            self.position += byte_count
            remaining -= take
            parts.append(
                raw.decode("utf-16le" if current_wide else "latin1", "replace")
            )

        return "".join(parts)


def parse_shared_strings(segments: list[bytes]) -> list[str]:
    reader = SegmentedReader(segments)
    _total_count, unique_count = struct.unpack("<II", reader.read_raw(8))
    values: list[str] = []

    for _ in range(unique_count):
        character_count = struct.unpack("<H", reader.read_raw(2))[0]
        flags = reader.read_raw(1)[0]
        wide = bool(flags & 0x01)
        has_rich_text = bool(flags & 0x08)
        has_extended_data = bool(flags & 0x04)
        rich_run_count = (
            struct.unpack("<H", reader.read_raw(2))[0] if has_rich_text else 0
        )
        extended_length = (
            struct.unpack("<I", reader.read_raw(4))[0]
            if has_extended_data
            else 0
        )

        value = reader.read_characters(character_count, wide)
        if rich_run_count:
            reader.read_raw(rich_run_count * 4)
        if extended_length:
            reader.read_raw(extended_length)
        values.append(value)

    return values


def read_xls_rows(path: Path) -> list[list[Any]]:
    workbook = CompoundFile(path).read_stream("Workbook")
    records = list(iter_biff_records(workbook))
    sheet_positions: list[tuple[int, str]] = []
    shared_strings: list[str] = []

    record_index = 0
    while record_index < len(records):
        _offset, record_id, payload = records[record_index]
        if record_id == 0x0085:  # BOUNDSHEET
            sheet_position = struct.unpack_from("<I", payload, 0)[0]
            name_length = payload[6]
            name_flags = payload[7]
            name_bytes = payload[8 : 8 + name_length * (2 if name_flags & 1 else 1)]
            sheet_name = name_bytes.decode(
                "utf-16le" if name_flags & 1 else "latin1", "replace"
            )
            sheet_positions.append((sheet_position, sheet_name))
        elif record_id == 0x00FC:  # SST
            segments = [payload]
            next_index = record_index + 1
            while next_index < len(records) and records[next_index][1] == 0x003C:
                segments.append(records[next_index][2])
                next_index += 1
            shared_strings = parse_shared_strings(segments)
            record_index = next_index - 1
        record_index += 1

    if not sheet_positions:
        raise ImportErrorWithMessage(".xls 中没有工作表")

    # Transaction data are stored in the first worksheet.
    start = sheet_positions[0][0]
    end = sheet_positions[1][0] if len(sheet_positions) > 1 else len(workbook)
    cells: dict[tuple[int, int], Any] = {}

    for _offset, record_id, payload in iter_biff_records(workbook, start, end):
        if record_id == 0x00FD and len(payload) >= 10:  # LABELSST
            row, column, _xf, string_index = struct.unpack_from("<HHHI", payload, 0)
            cells[(row, column)] = (
                shared_strings[string_index]
                if string_index < len(shared_strings)
                else ""
            )
        elif record_id == 0x0203 and len(payload) >= 14:  # NUMBER
            row, column, _xf = struct.unpack_from("<HHH", payload, 0)
            cells[(row, column)] = struct.unpack_from("<d", payload, 6)[0]
        elif record_id == 0x027E and len(payload) >= 10:  # RK
            row, column, _xf, rk = struct.unpack_from("<HHHI", payload, 0)
            cells[(row, column)] = decode_rk(rk)
        elif record_id == 0x00BD and len(payload) >= 6:  # MULRK
            row, first_column = struct.unpack_from("<HH", payload, 0)
            last_column = struct.unpack_from("<H", payload, len(payload) - 2)[0]
            position = 4
            for column in range(first_column, last_column + 1):
                _xf, rk = struct.unpack_from("<HI", payload, position)
                position += 6
                cells[(row, column)] = decode_rk(rk)
        elif record_id == 0x0204 and len(payload) >= 8:  # LABEL
            row, column, _xf, length = struct.unpack_from("<HHHH", payload, 0)
            cells[(row, column)] = payload[8 : 8 + length].decode(
                "latin1", "replace"
            )

    max_row = max((row for row, _column in cells), default=-1)
    max_column = max((column for _row, column in cells), default=-1)
    return [
        [cells.get((row, column)) for column in range(max_column + 1)]
        for row in range(max_row + 1)
    ]


@dataclass(frozen=True)
class Transaction:
    source_row: int
    type: str
    platform: str
    name: str
    amount: str
    date: str

    def form_data(self) -> dict[str, str]:
        return {
            "type": self.type,
            "platform": self.platform,
            "name": self.name,
            "amount": self.amount,
            "date": self.date,
        }


def decimal_text(value: Any) -> str:
    return money_text(abs(money_minor(value)))


def parse_trade_date(value: Any) -> str:
    text = str(value).strip()
    # The bank includes a time; the Java backend stores only the calendar date.
    candidate = text[:10]
    parts = candidate.split("-")
    if len(parts) != 3 or not all(part.isdigit() for part in parts):
        raise ImportErrorWithMessage(f"无法解析交易时间：{value!r}")
    year, month, day = map(int, parts)
    if not (1 <= month <= 12 and 1 <= day <= 31 and year >= 1900):
        raise ImportErrorWithMessage(f"无效交易时间：{value!r}")
    return f"{year:04d}-{month:02d}-{day:02d}"


def extract_transactions(rows: list[list[Any]], platform: str) -> list[Transaction]:
    required_headers = {"交易时间", "交易金额", "摘要"}
    header_row_index = -1
    header_map: dict[str, int] = {}

    for row_index, row in enumerate(rows):
        current_map = {
            str(value).strip(): column_index
            for column_index, value in enumerate(row)
            if value is not None
        }
        if required_headers.issubset(current_map):
            header_row_index = row_index
            header_map = current_map
            break

    if header_row_index < 0:
        raise ImportErrorWithMessage(
            "找不到包含“交易时间、交易金额、摘要”的表头"
        )

    transactions: list[Transaction] = []
    for row_index, row in enumerate(rows[header_row_index + 1 :], header_row_index + 1):
        def get_value(header: str) -> Any:
            column = header_map[header]
            return row[column] if column < len(row) else None

        trade_time = get_value("交易时间")
        raw_amount = get_value("交易金额")
        summary = get_value("摘要")

        # Ignore blank rows and download metadata.
        if trade_time in (None, "") or raw_amount in (None, ""):
            continue

        try:
            signed_amount = money_minor(raw_amount)
        except ImportErrorWithMessage as exc:
            raise ImportErrorWithMessage(f"第 {row_index + 1} 行金额无效，请先修正源数据") from exc

        transaction_type = "income" if signed_amount >= 0 else "expense"
        transactions.append(
            Transaction(
                source_row=row_index + 1,  # Excel row numbers are one-based.
                type=transaction_type,
                platform=platform,
                name="" if summary is None else str(summary).strip(),
                amount=money_text(abs(signed_amount)),
                date=parse_trade_date(trade_time),
            )
        )

    if not transactions:
        raise ImportErrorWithMessage("没有找到可导入的交易记录")
    return transactions


class FinanceHttpClient:
    def __init__(self, base_url: str, jsessionid: str | None, timeout: float) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.cookie_jar = http.cookiejar.CookieJar()
        self.opener = build_opener(
            HTTPCookieProcessor(self.cookie_jar), NoRedirectHandler()
        )
        self.jsessionid = jsessionid.strip() if jsessionid else None
        self.user_generation = None
        self._account_generations = {}

    def _request(
        self,
        path: str,
        *,
        form: dict[str, str] | None = None,
        json_data: dict[str, Any] | None = None,
    ) -> tuple[int, bytes, dict[str, str]]:
        url = self.base_url + (path if path.startswith("/") else "/" + path)
        origin = urlsplit(self.base_url)
        headers = {"Accept": "application/json", "Origin": origin.scheme + "://" + origin.netloc}
        if self.jsessionid:
            headers["Cookie"] = f"JSESSIONID={self.jsessionid}"
        if getattr(self, "user_generation", None):
            headers["X-IAFM-User-Generation"] = self.user_generation

        if form is not None:
            body = urlencode(form).encode("utf-8")
            headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        elif json_data is not None:
            body = json.dumps(json_data, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=UTF-8"
        else:
            body = b""

        request = Request(url, data=body, headers=headers, method="POST")
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                return response.status, response.read(), dict(response.headers.items())
        except HTTPError as exc:
            location = exc.headers.get("Location", "")
            if exc.code in (301, 302, 303, 307, 308):
                raise ImportErrorWithMessage(
                    "服务器发生重定向，登录 Session 可能已经失效。"
                    f" Location={location!r}"
                ) from exc
            body_text = exc.read().decode("utf-8", "replace")
            raise ImportErrorWithMessage(
                f"HTTP {exc.code}：{body_text[:500]}"
            ) from exc
        except URLError as exc:
            raise ImportErrorWithMessage(f"无法连接服务器：{exc.reason}") from exc

    def login(self, endpoint: str, email: str, password: str) -> None:
        status, body, _headers = self._request(
            endpoint, form={"emailAddress": email, "password": password}
        )
        if status != 200:
            raise ImportErrorWithMessage(f"登录接口返回 HTTP {status}")
        try:
            result = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ImportErrorWithMessage("登录接口没有返回有效 JSON") from exc
        if result.get("status") not in ("success", "already_logged_in"):
            raise ImportErrorWithMessage(result.get("message", "登录失败"))
        self.user_generation = None
        self._account_generations = {}

    def _identity_form(self, transaction: Transaction) -> dict[str, str]:
        """Pin the selected owner and account once; never silently follow a recycled ID."""
        if not getattr(self, "user_generation", None):
            status, body, _headers = self._request("/getUserProfile")
            profile = self._successful_json(status, body, "Identity")
            generation = profile.get("userGeneration")
            if profile.get("id") != EXPECTED_USER_ID or not isinstance(generation, str) or len(generation) != 36:
                raise ImportErrorWithMessage("The authenticated owner does not match this import.")
            self.user_generation = generation
        form = transaction.form_data()
        platform = form.get("platform")
        if not isinstance(platform, str) or not platform:
            raise ImportErrorWithMessage("The import account is missing.")
        if not hasattr(self, "_account_generations"):
            self._account_generations = {}
        if platform not in self._account_generations:
            page, matches = 1, []
            while True:
                status, body, _headers = self._request("/getPlatforms", json_data={"page": page, "pageSize": 100, "q": platform})
                result = self._successful_json(status, body, "Account identity")
                items = result.get("items")
                if not isinstance(items, list) or len(items) > 100 or type(result.get("hasNext")) is not bool:
                    raise ImportErrorWithMessage("Invalid account pagination.")
                matches.extend(item for item in items if isinstance(item, dict) and item.get("name") == platform)
                if not result["hasNext"]:
                    break
                if not items:
                    raise ImportErrorWithMessage("Incomplete account pagination.")
                page += 1
            if len(matches) != 1 or not isinstance(matches[0].get("generation"), str) or len(matches[0]["generation"]) != 36:
                raise ImportErrorWithMessage("The import account is missing, ambiguous, or has no generation.")
            self._account_generations[platform] = matches[0]["generation"]
        form["platformGeneration"] = self._account_generations[platform]
        return form

    def add_transaction(self, endpoint: str, transaction: Transaction) -> None:
        status, body, _headers = self._request(
            endpoint, form=self._identity_form(transaction)
        )
        self._successful_json(status, body, "新增")

    @staticmethod
    def _successful_json(status: int, body: bytes, operation: str) -> dict[str, Any]:
        if status != 200:
            raise ImportErrorWithMessage(f"{operation}接口返回 HTTP {status}")
        try:
            result = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ImportErrorWithMessage(f"{operation}接口没有返回有效 JSON") from exc
        if not isinstance(result, dict) or result.get("status") != "success":
            message = result.get("message") if isinstance(result, dict) else None
            raise ImportErrorWithMessage(message or f"{operation}接口没有确认成功")
        return result

    def iter_transactions(self):
        """Read every page without retaining the complete server-side transaction list."""
        page, read_count, expected_total = 1, 0, None
        while True:
            status, body, _headers = self._request(
                "/getIncomeAndExpenditureData",
                json_data={
                    "filterType": [], "filterPlatform": [], "filterMonth": [],
                    "filterDateOfMonth": [], "filterYear": [],
                    "page": page, "pageSize": 100, "section": "all",
                },
            )
            result = self._successful_json(status, body, "查询")
            items, total = result.get("items"), result.get("total")
            has_next = result.get("hasNext")
            if (not isinstance(items, list) or len(items) > 100
                    or not all(isinstance(item, dict) for item in items)
                    or type(total) is not int or total < 0
                    or type(has_next) is not bool or result.get("page", page) != page):
                raise ImportErrorWithMessage("查询接口返回的分页信息无效")
            if expected_total is None:
                expected_total = total
            elif total != expected_total:
                raise ImportErrorWithMessage("查询期间交易数量发生变化，请重新核验")
            read_count += len(items)
            if read_count > total or has_next != (read_count < total) or (has_next and not items):
                raise ImportErrorWithMessage("查询接口返回的分页数量不一致")
            yield from items
            if not has_next:
                return
            page += 1

    def count_transactions(self) -> int:
        return sum(1 for _ in self.iter_transactions())


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_checkpoint(
    checkpoint_path: Path, source_hash: str, restart: bool
) -> set[int]:
    if restart and checkpoint_path.exists():
        checkpoint_path.unlink()
    if not checkpoint_path.exists():
        return set()

    try:
        data = json.loads(checkpoint_path.read_text("utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ImportErrorWithMessage(f"检查点文件损坏：{checkpoint_path}") from exc

    if data.get("source_sha256") != source_hash:
        raise ImportErrorWithMessage(
            f"检查点属于另一份 Excel 文件。删除 {checkpoint_path} 或使用 --restart。"
        )
    return {int(row) for row in data.get("completed_source_rows", [])}


def save_checkpoint(
    checkpoint_path: Path, source_hash: str, completed_rows: set[int]
) -> None:
    payload = {
        "source_sha256": source_hash,
        "expected_user_id": EXPECTED_USER_ID,
        "completed_source_rows": sorted(completed_rows),
    }
    temporary = checkpoint_path.with_suffix(checkpoint_path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    temporary.replace(checkpoint_path)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="批量调用 /addIncomeOrExpense 导入邮储银行交易流水"
    )
    parser.add_argument("--xls", default=DEFAULT_XLS, help="银行流水 .xls 文件")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="Web 应用根地址")
    parser.add_argument(
        "--endpoint", default=DEFAULT_ENDPOINT, help="新增交易 Servlet 路径"
    )
    parser.add_argument(
        "--platform", default=DEFAULT_PLATFORM, help="固定写入的 platform"
    )
    parser.add_argument("--email", default=os.getenv("FINANCE_EMAIL"))
    parser.add_argument("--password", default=os.getenv("FINANCE_PASSWORD"))
    parser.add_argument(
        "--jsessionid",
        default=os.getenv("JSESSIONID"),
        help="ID=2 用户的有效 JSESSIONID；提供后可不输入账号密码",
    )
    parser.add_argument("--delay", type=float, default=0.05, help="每次请求后的间隔秒数")
    parser.add_argument("--timeout", type=float, default=30.0, help="HTTP 超时秒数")
    parser.add_argument(
        "--retries",
        type=int,
        choices=(1,),
        default=1,
        help="仅允许 1；金融写入不自动重试，失败后须先核对服务器记录",
    )
    parser.add_argument("--limit", type=int, help="仅处理前 N 条待导入记录")
    parser.add_argument(
        "--checkpoint", default=DEFAULT_CHECKPOINT, help="断点续传检查点文件"
    )
    parser.add_argument("--restart", action="store_true", help="清除检查点并从头导入")
    parser.add_argument("--dry-run", action="store_true", help="只解析并预览，不发请求")
    parser.add_argument("--yes", action="store_true", help="不询问确认，直接开始")
    parser.add_argument(
        "--verify-count",
        action="store_true",
        help="导入前后调用查询接口，核对总记录数变化",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    xls_path = Path(args.xls).expanduser().resolve()
    checkpoint_path = Path(args.checkpoint).expanduser().resolve()

    if not xls_path.is_file():
        raise ImportErrorWithMessage(f"找不到 Excel 文件：{xls_path}")
    if args.delay < 0:
        raise ImportErrorWithMessage("--delay 不能小于 0")
    if args.retries != 1:
        raise ImportErrorWithMessage("--retries must be 1: financial writes are never retried automatically.")

    print(f"读取文件：{xls_path}")
    rows = read_xls_rows(xls_path)
    transactions = extract_transactions(rows, args.platform)
    source_hash = sha256_file(xls_path)
    completed_rows = load_checkpoint(checkpoint_path, source_hash, args.restart)
    pending = [tx for tx in transactions if tx.source_row not in completed_rows]
    if args.limit is not None:
        pending = pending[: max(0, args.limit)]

    income_count = sum(tx.type == "income" for tx in transactions)
    expense_count = len(transactions) - income_count
    print(
        f"解析完成：共 {len(transactions)} 条，"
        f"income {income_count} 条，expense {expense_count} 条。"
    )
    print(f"检查点已完成：{len(completed_rows)} 条；本次待处理：{len(pending)} 条。")
    print(f"固定 platform：{args.platform}")
    print(
        f"目标用户：ID={EXPECTED_USER_ID}（由登录 Session 决定，userID 不会作为表单字段发送）"
    )

    preview = pending[:10]
    for tx in preview:
        print(
            f"  Excel 第 {tx.source_row} 行 | {tx.date} | {tx.type:7s} | "
            f"{tx.amount:>10s} | {tx.name}"
        )
    if len(pending) > len(preview):
        print(f"  ……另有 {len(pending) - len(preview)} 条")

    if args.dry_run:
        print("dry-run 完成：没有发送任何 HTTP 请求。")
        return 0
    if not pending:
        print("没有待导入记录。")
        return 0

    email = args.email
    password = args.password
    if not args.jsessionid:
        if not email:
            email = input("请输入 ID=2 用户的登录邮箱：").strip()
        if not password:
            password = getpass.getpass("请输入登录密码：")
        if not email or not password:
            raise ImportErrorWithMessage("必须提供 ID=2 用户的账号密码或 JSESSIONID")

    if not args.yes:
        answer = input(
            f"将向 {args.base_url}{args.endpoint} 连续提交 {len(pending)} 条记录，继续？[y/N] "
        ).strip().lower()
        if answer not in ("y", "yes"):
            print("已取消。")
            return 0

    client = FinanceHttpClient(args.base_url, args.jsessionid, args.timeout)
    if not args.jsessionid:
        client.login(DEFAULT_LOGIN_ENDPOINT, email, password)
        print("登录成功。请确认该账号在数据库中的 ID 为 2。")
    else:
        print("使用提供的 JSESSIONID。请确认该 Session 属于 ID=2 用户。")

    count_before: int | None = None
    if args.verify_count:
        count_before = client.count_transactions()
        print(f"导入前服务器交易总数：{count_before}")

    succeeded_this_run = 0
    for index, tx in enumerate(pending, start=1):
        try:
            client.add_transaction(args.endpoint, tx)
        except Exception as exc:
            raise ImportErrorWithMessage(
                f"Excel row {tx.source_row}: import stopped without retry. The server may have committed this record; "
                "reconcile it against the source and checkpoint before restarting. " + str(exc)
            ) from exc

        completed_rows.add(tx.source_row)
        save_checkpoint(checkpoint_path, source_hash, completed_rows)
        succeeded_this_run += 1
        print(
            f"[{index}/{len(pending)}] 已提交 Excel 第 {tx.source_row} 行："
            f"{tx.date} {tx.type} {tx.amount} {tx.name}"
        )
        if args.delay:
            time.sleep(args.delay)

    print(f"本次已提交 {succeeded_this_run} 条；检查点：{checkpoint_path}")

    if args.verify_count:
        count_after = client.count_transactions()
        increase = count_after - (count_before or 0)
        print(f"导入后服务器交易总数：{count_after}，增加：{increase}")
        if count_before is not None and increase != succeeded_this_run:
            print(
                "警告：服务器总记录数的增量与本次 HTTP 成功数不一致。"
                "请查看 Tomcat 日志中的 AddIncomeOrExpense execution。",
                file=sys.stderr,
            )

    print(
        "完成：每条写入均已收到明确成功响应。建议使用 --verify-count 核对服务器记录。"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("\n已中断；下次运行会从检查点继续。", file=sys.stderr)
        raise SystemExit(130)
    except ImportErrorWithMessage as exc:
        print(f"错误：{exc}", file=sys.stderr)
        raise SystemExit(1)
