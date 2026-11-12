#!/usr/bin/env python3
"""User-bound, read-only API keys and paginated financial-data export (standard library only)."""
from __future__ import annotations
import argparse
import datetime as dt
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import tempfile
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, HTTPRedirectHandler, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("The API redirected the request; check the base URL. The key was not forwarded.")


class Client:
    def __init__(self, base_url: str, token: str):
        parsed = urlsplit(base_url)
        if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"}):
            raise ValueError("Use HTTPS, except for a local development server.")
        if parsed.username or parsed.password or parsed.query or parsed.fragment or not parsed.hostname:
            raise ValueError("The base URL must not contain credentials, a query, or a fragment.")
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", token):
            raise ValueError("A valid API token is required.")
        self.base = base_url.rstrip("/")
        self.token = token
        self.opener = build_opener(NoRedirect())

    def get(self, endpoint: str, **parameters):
        suffix = "?" + urlencode(parameters) if parameters else ""
        request = Request(self.base + "/api/v1/" + endpoint + suffix,
                          headers={"Authorization": "Bearer " + self.token, "Accept": "application/json"})
        try:
            with self.opener.open(request, timeout=30) as response:
                if "application/json" not in response.headers.get("Content-Type", ""):
                    raise RuntimeError("Expected JSON from the API.")
                value = json.load(response)
        except HTTPError as error:
            raise RuntimeError(f"API request failed (HTTP {error.code}); check key, endpoint, and configuration.") from None
        except URLError:
            raise RuntimeError("The API could not be reached.") from None
        if not isinstance(value, dict) or value.get("status") != "success":
            raise RuntimeError("The API did not confirm a successful read.")
        return value

    def rows(self, endpoint: str, **parameters):
        page, expected_total, received = 1, None, 0
        seen = set()
        while True:
            result = self.get(endpoint, page=page, pageSize=100, **parameters)
            total, items, has_next = result.get("total"), result.get("items"), result.get("hasNext")
            if type(total) is not int or total < 0 or not isinstance(items, list) or type(has_next) is not bool:
                raise RuntimeError("Invalid pagination response.")
            if result.get("page") != page or result.get("pageSize") != 100 or len(items) > 100:
                raise RuntimeError("The API returned an unexpected page.")
            if expected_total is None:
                expected_total = total
            elif total != expected_total:
                raise RuntimeError("Records changed during export. Retry while writes are paused.")
            for item in items:
                if not isinstance(item, dict):
                    raise RuntimeError("Invalid record in API response.")
                identity = (item.get("kind"), item.get("id"))
                if identity[1] is not None:
                    if identity in seen:
                        raise RuntimeError("A duplicate page record was received; retry the export.")
                    seen.add(identity)
                received += 1
                yield item
            if received > total or has_next != (received < total) or (has_next and not items):
                raise RuntimeError("Inconsistent pagination response.")
            if not has_next:
                return
            page += 1


def export(client: Client, output: Path, currency: str, summaries: bool):
    output = output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=output.parent,
                                         prefix=output.name + ".", suffix=".partial", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write("{\n")
            head = {
                "exportedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                "moneyFormat": "two-decimal strings",
                "profile": client.get("profile"),
                "basicVariables": client.get("basic-variables"),
            }
            for key, value in head.items():
                stream.write(json.dumps(key) + ":" + json.dumps(value, ensure_ascii=False) + ",\n")
            resources = [
                ("nonInvestmentAssets", "assets", {"kind": "nonInvestment"}),
                ("investmentAssets", "assets", {"kind": "investment"}),
                ("transactions", "transactions", {}),
                ("platforms", "platforms", {}),
                ("inflationRates", "inflation-rates", {}),
            ]
            for index, (name, endpoint, parameters) in enumerate(resources):
                stream.write(json.dumps(name) + ":[\n")
                first = True
                for row in client.rows(endpoint, **parameters):
                    if not first:
                        stream.write(",\n")
                    json.dump(row, stream, ensure_ascii=False)
                    first = False
                stream.write("\n]")
                if index < len(resources) - 1 or summaries:
                    stream.write(",\n")
            if summaries:
                results = {}
                for name in ("assets", "cashflow", "earnings", "risk"):
                    results[name] = client.get("summaries/" + name, currency=currency)
                stream.write('"summaries":' + json.dumps(results, ensure_ascii=False))
            stream.write("\n}\n")
        os.replace(temporary, output)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    key = commands.add_parser("key", help="Generate a read-only key bound to one existing user ID")
    key.add_argument("user_id", type=int)
    key.add_argument("user_generation", help="Immutable GENERATION of the existing USERS row")
    backup = commands.add_parser("export", help="Export server data in pages of 100 without a login session")
    backup.add_argument("base_url", help="For example https://finance.example/Investment_and_Financial_Management")
    backup.add_argument("--output", type=Path, default=Path("user-data.json"))
    backup.add_argument("--currency", choices=("GBP", "CNY"), default="GBP")
    backup.add_argument("--summaries", action="store_true", help="Also read computed summaries; requires valid FX for mixed currencies")
    args = parser.parse_args()
    if args.command == "key":
        if args.user_id < 1 or args.user_id > 2147483647:
            parser.error("user_id must identify an existing user (positive int).")
        try:
            generation = str(__import__("uuid").UUID(args.user_generation))
        except (ValueError, AttributeError):
            parser.error("user_generation must be the existing user UUID generation.")
        token = secrets.token_urlsafe(32)
        print("Client API token (keep private): " + token)
        print("Server IAFM_API_KEYS entry: " + str(args.user_id) + ":" + generation + ":" + hashlib.sha256(token.encode("ascii")).hexdigest())
        return
    token = os.environ.get("IAFM_API_TOKEN") or getpass.getpass("API token: ")
    try:
        export(Client(args.base_url, token), args.output, args.currency, args.summaries)
    except (ValueError, RuntimeError, OSError) as error:
        parser.exit(1, str(error) + "\n")
    print("Saved " + str(args.output))


if __name__ == "__main__":
    main()
