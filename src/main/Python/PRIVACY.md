# Statement import data

The Python source is publishable code. Bank statements and transaction payloads are private input files and must never be committed to a public repository.

The importers in `5`, `6`, and `7` read `private_data.json` next to their source file. Each file contains the original decoded JSON transaction array; the SHA-256 integrity check remains in the importer. These private files are excluded from both the source repository and the delivery ZIP; provide them separately on the importing machine. They contain bank transaction details, so retain mode `0600` and store them securely. A public checkout deliberately omits them and displays a clear missing-data message instead of shipping example personal transactions.

PDF, XLS, CSV, checkpoints, database snapshots and reconciliation plans are also excluded. `.gitignore` does not erase earlier Git history: publication must also check all reachable commits, branches and tags for previously embedded payloads or credentials.

`InformationManage/Account.py` contains no personal account values. If that optional utility is needed, set `IAFM_PRIVATE_ACCOUNTS_FILE` to a private JSON file outside the project containing equal-length string arrays named `Software`, `name`, `email`, `password`, `card`, `address` and `phoneNumber`. With no configuration, its legacy arrays are empty. Credentials and private account-contact files are excluded from both the public repository and the delivery ZIP; they are not statement import data.

`reconcile_internal_transfers.py` is read-only. It checks the original files and optional private live JSONL export, then produces an anonymised summary and a private pairing plan. It requires `pypdf`. An equal date and amount never by themselves establish an internal transfer. The plan retains uncertain cases for review.

`scripts/migrate_ledger.py` uses an explicit owner ID and email, defaults to a dry run, and requires a new private backup path before applying. It preserves original statement rows and all account balances. Paired transfers create equal debit and credit book amounts; native amounts remain available for currency reconciliation. Fees are separate external expense records. Zero-value historical rows are retained without manufacturing monetary postings.

