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
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPCookieProcessor, HTTPRedirectHandler, Request, build_opener

DEFAULT_BASE_URL = "http://localhost:8080/Investment_and_Financial_Management"
DEFAULT_ENDPOINT = "/addIncomeOrExpense"
DEFAULT_LOGIN_ENDPOINT = "/login"
DEFAULT_PLATFORM = "HSBC_UK_BANK_ACCOUNT"
EXPECTED_USER_ID = 2
DEFAULT_CHECKPOINT = ".hsbc_income_expense_import_checkpoint.json"
EXPECTED_STATEMENTS = None  # Loaded only from the owner-private source file.
EXPECTED_RECORDS = None  # Loaded only from the owner-private source file.
EXPECTED_INCOME = None  # Loaded only from the owner-private source file.
EXPECTED_EXPENSE = None  # Loaded only from the owner-private source file.
EXPECTED_INCOME_TOTAL = None  # Loaded only from the owner-private source file.
EXPECTED_EXPENSE_TOTAL = None  # Loaded only from the owner-private source file.
EXPECTED_DATA_SHA256 = "6bafd59bdbf65245d62ef282039c311ab5785130ee718012712fe64cd6978c6e"

# Statement transactions are stored in the excluded, owner-private private_data.json.


class ImportErrorWithMessage(RuntimeError):
    pass


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



class HttpRequestError(ImportErrorWithMessage):
    def __init__(self, code: int, path: str, body: str = "", location: str = "") -> None:
        self.code = code
        self.path = path
        self.body = body
        self.location = location
        if code in (301, 302, 303, 307, 308):
            message = (
                "服务器发生重定向，登录 Session 可能已经失效。"
                f" Location={location!r}"
            )
        else:
            message = f"HTTP {code}：{body[:500]}"
        super().__init__(message)


class NoRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: N802
        return None


@dataclass(frozen=True)
class Transaction:
    source_id: str
    statement: int
    statement_file: str
    date: str
    type: str
    amount: str
    name: str

    def form_data(self) -> dict[str, str]:
        return {
            "type": self.type,
            "platform": DEFAULT_PLATFORM,
            "name": self.name,
            "amount": self.amount,
            "date": self.date,
        }


def load_embedded_transactions() -> list[Transaction]:
    global EXPECTED_STATEMENTS, EXPECTED_RECORDS, EXPECTED_INCOME, EXPECTED_EXPENSE, EXPECTED_INCOME_TOTAL, EXPECTED_EXPENSE_TOTAL
    try:
        raw = Path(__file__).with_name("private_data.json").read_bytes()
        if hashlib.sha256(raw).hexdigest() != EXPECTED_DATA_SHA256:
            raise ImportErrorWithMessage("脚本私有文件交易数据校验失败，文件可能被破坏")
        items = json.loads(raw.decode("utf-8"))
        EXPECTED_RECORDS = len(items)
        EXPECTED_INCOME = sum(item["type"] == "income" for item in items)
        EXPECTED_EXPENSE = sum(item["type"] == "expense" for item in items)
        EXPECTED_INCOME_TOTAL = money_text(sum(money_minor(item["amount"]) for item in items if item["type"] == "income"))
        EXPECTED_EXPENSE_TOTAL = money_text(sum(money_minor(item["amount"]) for item in items if item["type"] == "expense"))
        EXPECTED_STATEMENTS = len({item["statement_file"] for item in items})
    except FileNotFoundError as exc:
        raise ImportErrorWithMessage("缺少本地私有数据 private_data.json；请使用自己的导出数据，公开仓库不包含银行明细。") from exc
    except ImportErrorWithMessage:
        raise
    except Exception as exc:
        raise ImportErrorWithMessage("无法读取脚本私有文件交易数据") from exc

    transactions = [
        Transaction(
            source_id=str(item["id"]),
            statement=int(item["statement"]),
            statement_file=str(item["statement_file"]),
            date=str(item["date"]),
            type=str(item["type"]),
            amount=money_text(money_minor(item["amount"])),
            name=str(item["name"]),
        )
        for item in items
    ]

    income = [tx for tx in transactions if tx.type == "income"]
    expense = [tx for tx in transactions if tx.type == "expense"]
    statement_count = len({tx.statement_file for tx in transactions})
    income_total = sum(money_minor(tx.amount) for tx in income)
    expense_total = sum(money_minor(tx.amount) for tx in expense)
    if (
        statement_count != EXPECTED_STATEMENTS
        or len(transactions) != EXPECTED_RECORDS
        or len(income) != EXPECTED_INCOME
        or len(expense) != EXPECTED_EXPENSE
        or money_text(income_total) != EXPECTED_INCOME_TOTAL
        or money_text(expense_total) != EXPECTED_EXPENSE_TOTAL
    ):
        raise ImportErrorWithMessage("私有文件交易数量或金额汇总校验失败")
    return transactions


class FinanceHttpClient:
    def __init__(self, base_url: str, jsessionid: str | None, timeout: float) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.cookie_jar = http.cookiejar.CookieJar()
        self.opener = build_opener(HTTPCookieProcessor(self.cookie_jar), NoRedirectHandler())
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
            body_text = exc.read().decode("utf-8", "replace")
            raise HttpRequestError(exc.code, path, body_text, location) from exc
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

    def add_transaction(self, endpoint: str, transaction: Transaction) -> str:
        # Normalize the historical spelling before the single financial POST.
        selected = endpoint if endpoint.startswith("/") else "/" + endpoint
        if selected == "/AddIncomeOrExpense":
            selected = DEFAULT_ENDPOINT
        status, body, _headers = self._request(selected, form=self._identity_form(transaction))
        self._successful_json(status, body, "新增")
        return selected

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


def load_checkpoint(path: Path, restart: bool) -> set[str]:
    if restart and path.exists():
        path.unlink()
    if not path.exists():
        return set()
    try:
        data = json.loads(path.read_text("utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ImportErrorWithMessage(f"检查点文件损坏：{path}") from exc
    if data.get("data_sha256") != EXPECTED_DATA_SHA256:
        raise ImportErrorWithMessage(f"检查点不属于本脚本数据：{path}")
    return {str(item) for item in data.get("completed_source_ids", [])}


def save_checkpoint(path: Path, completed: set[str]) -> None:
    payload = {
        "data_sha256": EXPECTED_DATA_SHA256,
        "expected_user_id": EXPECTED_USER_ID,
        "completed_source_ids": sorted(completed),
    }
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def prompt_password() -> str:
    """Require a terminal that supports hidden credential entry."""
    if not sys.stdin.isatty():
        raise ImportErrorWithMessage("Run in a terminal for hidden password entry, or provide a valid session.")
    return getpass.getpass("请输入登录密码：")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="导入 55 份 HSBC UK Bank statement 中的全部 income/expense"
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="Web 应用根地址")
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT, help="新增交易 Servlet 路径")
    parser.add_argument("--email", default=os.getenv("FINANCE_EMAIL"))
    parser.add_argument("--password", default=os.getenv("FINANCE_PASSWORD"))
    parser.add_argument(
        "--jsessionid",
        default=os.getenv("JSESSIONID"),
        help="User ID=2 的有效 JSESSIONID；提供后不需要账号密码",
    )
    parser.add_argument("--delay", type=float, default=0.05, help="每条请求后的间隔秒数")
    parser.add_argument("--timeout", type=float, default=30.0, help="单次 HTTP 超时秒数")
    parser.add_argument(
        "--retries", type=int, choices=(1,), default=1,
        help="仅允许 1；金融写入不自动重试，失败后须先核对服务器记录",
    )
    parser.add_argument("--limit", type=int, help="本次最多导入 N 条待处理记录")
    parser.add_argument("--checkpoint", default=DEFAULT_CHECKPOINT, help="断点文件")
    parser.add_argument("--restart", action="store_true", help="清除断点并从第一条重新导入")
    parser.add_argument("--dry-run", action="store_true", help="只预览，不发送请求")
    parser.add_argument("--yes", action="store_true", help="跳过开始前确认")
    parser.add_argument(
        "--verify-count", action="store_true",
        help="导入前后查询服务器记录总数，检查数量增量",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if args.delay < 0:
        raise ImportErrorWithMessage("--delay 不能小于 0")
    if args.retries != 1:
        raise ImportErrorWithMessage("--retries must be 1: financial writes are never retried automatically.")

    checkpoint_path = Path(args.checkpoint).expanduser().resolve()
    transactions = load_embedded_transactions()
    completed = load_checkpoint(checkpoint_path, args.restart)
    pending = [tx for tx in transactions if tx.source_id not in completed]
    if args.limit is not None:
        pending = pending[:max(0, args.limit)]

    print(f"已读取本地私有文件中的 {EXPECTED_STATEMENTS} 份 HSBC UK Bank statement 数据。")
    print(
        f"共 {len(transactions)} 条：income {EXPECTED_INCOME} 条，"
        f"expense {EXPECTED_EXPENSE} 条。"
    )
    print(
        f"金额汇总：Paid in £{EXPECTED_INCOME_TOTAL}；"
        f"Paid out £{EXPECTED_EXPENSE_TOTAL}。"
    )
    print(f"日期范围：{transactions[0].date} 至 {transactions[-1].date}。")
    print(f"固定 platform：{DEFAULT_PLATFORM}")
    print(
        f"目标用户：User ID={EXPECTED_USER_ID}（由登录 Session 决定，"
        "不会发送 userID 表单字段）"
    )
    print(f"检查点已完成 {len(completed)} 条；本次待处理 {len(pending)} 条。")

    for tx in pending[:10]:
        print(
            f"  {tx.source_id} | {tx.date} | {tx.type:7s} | "
            f"£{tx.amount:>9s} | {tx.name}"
        )
    if len(pending) > 10:
        print(f"  ……另有 {len(pending) - 10} 条")

    if args.dry_run:
        print("dry-run 完成：没有发送 HTTP 请求。")
        return 0
    if not pending:
        print("没有待导入记录。")
        return 0

    email = args.email
    password = args.password
    if not args.jsessionid:
        if not email:
            email = input("请输入 User ID=2 的登录邮箱：").strip()
        if not password:
            password = prompt_password()
        if not email or not password:
            raise ImportErrorWithMessage("必须提供 User ID=2 的账号密码或 JSESSIONID")

    if not args.yes:
        answer = input(
            f"将向 {args.base_url}{args.endpoint} 连续提交 {len(pending)} 条，继续？[y/N] "
        ).strip().lower()
        if answer not in ("y", "yes"):
            print("已取消。")
            return 0

    client = FinanceHttpClient(args.base_url, args.jsessionid, args.timeout)
    if args.jsessionid:
        print("使用提供的 JSESSIONID；请确认它属于 User ID=2。")
    else:
        client.login(DEFAULT_LOGIN_ENDPOINT, email, password)
        print("登录成功；请确认该登录账号在数据库中的 User ID 是 2。")

    count_before: int | None = None
    if args.verify_count:
        count_before = client.count_transactions()
        print(f"导入前服务器交易总数：{count_before}")

    succeeded = 0
    reported_endpoint: str | None = None
    for index, tx in enumerate(pending, 1):
        try:
            used_endpoint = client.add_transaction(args.endpoint, tx)
        except Exception as exc:
            raise ImportErrorWithMessage(
                f"{tx.source_id}: import stopped without retry. The server may have committed this record; "
                "reconcile it against the source and checkpoint before restarting. " + str(exc)
            ) from exc

        if used_endpoint and used_endpoint != reported_endpoint:
            print(f"实际使用新增接口：{used_endpoint}")
            reported_endpoint = used_endpoint

        completed.add(tx.source_id)
        save_checkpoint(checkpoint_path, completed)
        succeeded += 1
        print(
            f"[{index}/{len(pending)}] {tx.source_id} 已提交："
            f"{tx.date} {tx.type} £{tx.amount} {tx.name}"
        )
        if args.delay:
            time.sleep(args.delay)

    print(f"本次已提交 {succeeded} 条；断点文件：{checkpoint_path}")

    if args.verify_count:
        count_after = client.count_transactions()
        increase = count_after - (count_before or 0)
        print(f"导入后服务器交易总数：{count_after}，增加：{increase}")
        if count_before is not None and increase != succeeded:
            print(
                "警告：服务器记录增量与本次 HTTP 成功数不一致。请检查 Tomcat 日志中 "
                "AddIncomeOrExpense execution 是否全部为 true。",
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
        print("\n已中断。正在提交的记录可能已写入；重新运行前，请先核对服务器记录与检查点。", file=sys.stderr)
        raise SystemExit(130)
    except ImportErrorWithMessage as exc:
        print(f"错误：{exc}", file=sys.stderr)
        raise SystemExit(1)
