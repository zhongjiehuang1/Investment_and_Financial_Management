"""Optional local account information; credentials are never stored in source.

Set IAFM_PRIVATE_ACCOUNTS_FILE to an owner-managed JSON file outside the project.
The file contains parallel string arrays named Software, name, email, password,
card, address and phoneNumber. Missing configuration leaves the legacy arrays
empty; calling load_accounts explicitly requires an existing, valid file.
"""

import json
import os
from pathlib import Path


FIELDS = ("Software", "name", "email", "password", "card", "address", "phoneNumber")


def load_accounts(path=None):
    """Read private account information without logging its contents."""
    configured = path or os.environ.get("IAFM_PRIVATE_ACCOUNTS_FILE")
    if not configured:
        raise FileNotFoundError("Set IAFM_PRIVATE_ACCOUNTS_FILE to a local private JSON file.")
    with Path(configured).expanduser().open(encoding="utf-8") as source:
        data = json.load(source)
    if not isinstance(data, dict) or any(
        not isinstance(data.get(field), list)
        or any(not isinstance(value, str) for value in data[field])
        for field in FIELDS
    ):
        raise ValueError("Private account input must contain the documented string arrays.")
    if len({len(data[field]) for field in FIELDS}) != 1:
        raise ValueError("Private account input arrays must have equal lengths.")
    return {field: data[field] for field in FIELDS}


_accounts = load_accounts() if os.environ.get("IAFM_PRIVATE_ACCOUNTS_FILE") else {field: [] for field in FIELDS}
Software = _accounts["Software"]
name = _accounts["name"]
email = _accounts["email"]
password = _accounts["password"]
card = _accounts["card"]
address = _accounts["address"]
phoneNumber = _accounts["phoneNumber"]
