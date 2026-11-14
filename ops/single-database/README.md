# Single database backup and isolated recovery

## Scope

The current design uses server 2 as the only production MySQL server. Server 1's MySQL service must remain masked. Both application hosts connect to server 2; they are not database replicas. This procedure does not use the former peer controller, its failover state, or server 1's old data directory.

The backup script is a candidate. Have the operator review the source, SSH settings and current topology before use. It only makes a backup when explicitly invoked with `--execute`; running it without that flag is a local plan and makes no network calls or file changes. The tool currently requires all current non-system schemas, the six required core schemas, InnoDB for every base table, server 2 UUID `5b042440-ddee-11f1-8a71-06ffe4b75559`, port 332, durable redo/binlog settings, both application stacks stopped and server 1 MySQL masked. It discovers every non-system schema and refuses missing core schemas, unsupported schema names or non-InnoDB base tables. It records table and view counts and requires the inventory to remain identical throughout the backup. It checks the same GTID and table inventory before and after the dump. Do not allow other writers, DDL, account changes or maintenance during this window.

The compressed SQL contains all current non-system schemas and their table data, including Star Hub database BLOB content, plus triggers, events and routines. All current MySQL accounts and grants are captured in an encrypted reference component. Two separately verified encrypted configuration archives capture the current Server 1 and Server 2 application units and effective drop-ins, reserve policy, entry and monitor runtime configuration, Tomcat configuration, selected current application configuration, relevant TLS material, restricted peer-SSH identity/known-host files and SSH server/authorised-key settings. The captures intentionally exclude MySQL data directories, WARs, project source trees, logs, synthetic test-account credentials, temporary stages, old controller state and old deployment preimages. The files can contain passwords, key material and other private data; treat them as sensitive. Each configuration archive is an installation reference, not a bootable server image, and may include symlinks; captures are individually checked but are not a single atomic snapshot across the two hosts. Recover Star Hub file contents that are stored outside MySQL from the separately verified file backup, if any; this SQL export cannot establish that no external files exist. Browser-local data is outside its scope.

Each component is independently sealed with the existing AES-GCM envelope and verified with each of the two protected recovery key copies. Verification proves authentication and a plaintext hash; it is not an SQL restore test. Earlier restoration of two prior snapshots does not prove restoration of a newly created backup. Keep both recovery keys in their existing protected locations outside OneDrive. Do not upload, print or copy them into the backup folder.

## Recovery-key location on this Mac

The two existing recovery-key copies have moved from the old `~/Documents/Server_Recovery_Keys/` directories. Do not copy them back or create substitute keys. Their current protected locations are recorded in the owner-only custody JSON below; the file contains paths, not the key bytes. Both copies remain on the same Mac and are not an independent-device or off-site backup. Preserve the custody file and its two key files during cleanup.

```sh
CUSTODY_FILE="$HOME/Documents/Codex/2026-11-11/iafm-star-hub-ucost-zhongjie-portfolio/work/private-recovery/recovery-key-custody-946de4f5-2777-404d-acee-6a5460e3a896.json"
test -f "$CUSTODY_FILE"
stat -f '%Sp %Su %N' "$CUSTODY_FILE"
```

The custody file must be an owner-owned regular file with mode 0600 and one link; its `primaryPath` and `secondaryPath` must be absolute and name two distinct owner-owned mode-0600 32-byte key files outside OneDrive. Both programs check these requirements and authenticate with both key copies. Use `--key-custody-file "$CUSTODY_FILE"` for each actual invocation. Without that option they still look at the obsolete historical default locations and will fail on the current Mac. Do not print, upload, copy or place key bytes, passwords or tokens in commands, logs, OneDrive or documents. A different Mac requires a separately secured custody file pointing to the correct existing keys; use its actual location rather than assuming this Mac's paths.

## Before a backup

1. Review the current topology, service names, MySQL identity, SSH configuration, application versions and space. Make an exclusive maintenance window. Keep users informed and stop both application entry services and Tomcats using the current operations guide. Ensure no application or administrative writer remains.
2. Confirm server 1's `codex-lab-mysql.service` is masked and stopped; confirm server 2's production MySQL is running and the correct server UUID and port are in use. Keep the 4 GB server disk reserve and verify free local Mac space for both temporary plaintext and ciphertext. The script refuses many incompatible states but cannot detect every possible external writer or concurrent file change.
3. Keep `single_database_backup.py`, `remote_backup.py`, `capture_config.py`, `archive_verify.py` and `recovery_envelope.cjs` together. Review their pinned hashes, core schema names, owner-private staging directory, OneDrive location and both SSH settings. The tool does not call the retired maintenance runner or controller. Arrange existing SSH key or agent access to Server 1; when an already-authenticated SSH control socket is needed, pass `--server1-control-socket <ABSOLUTE-SOCKET-PATH>`. The default Node executable can be overridden with `--node-binary <ABSOLUTE-PATH>` when the current verified runtime is elsewhere. Never put passwords in commands or source files.
4. From this tool's directory, inspect the no-effect plan:

   ```sh
   python3 -B single_database_backup.py
   ```

5. Generate a new operation ID and run once, after the maintenance preconditions are met:

   ```sh
   uuidgen
   python3 -B single_database_backup.py --execute --run <NEW-UUID> --writers-stopped --key-custody-file "$CUSTODY_FILE"
   ```

6. On `COMPLETE`, inspect the reported `Databases_Backup_YYYY-MM-DD/Current_Production_Database` folder and its `MANIFEST.json`. It should contain four `.GCM` components: SQL, all-account/grants reference, Server 1 configuration, and Server 2 configuration; each must report verification using both recovery keys. The script refuses to replace an existing current backup; never remove or overwrite an existing backup before the replacement passes the required isolated restore and has been reviewed. Verify their ciphertext hashes against the manifest. Only after reviewing the backup and the current application guide should services be restarted.
7. If execution stops or reports `INCOMPLETE_DO_NOT_REPLAY`, preserve the owner-private run directory, partial encrypted outputs and exclusive lock. They are evidence, not a completed backup. Inspect them and both hosts before making a new decision; do not repeat the same operation or remove the lock blindly. Plaintext is stored only in the owner-private Mac run directory, never in OneDrive. Owner-private temporary work is under `~/Documents/Server_Recovery_Work/Single_Database_Backup`, outside OneDrive. Successful completion removes only the exact four temporary plaintext components; encrypted files and evidence remain. Filesystem deletion is not a claim of physical erasure from SSD or system snapshots.

## Run the reviewed isolated restore check

This check uses `single_database_restore.py` and the eight exact package members identified by `restore-package.json`. It creates an isolated, non-networked MySQL instance on Server 2 with a unique transient systemd unit, private mount namespace and owned data directory. It does not import into the production data directory. It is a verification tool for the current server and topology, not a blank-server rebuild procedure or a general SQL importer. A plan or encrypted-envelope verification is not a restore pass.

1. First make the new backup with both application stacks stopped and Server 2 MySQL running, following the preceding steps. Record the absolute directory and SHA-256 of that backup's `MANIFEST.json`; preserve earlier recovery copies. The four ciphertexts must remain byte-identical. The subsequent isolated test checks SQL/schema and BLOB content fingerprints and inventory from this exact snapshot; it does not prove application login, cloud rebuild, or complete grants/routine behavior.
2. Stop the Server 2 monitor and MySQL, and keep both application entries, checkers and Tomcats stopped. Temporarily mask the Server 2 MySQL and the application units on both hosts, verify their effective mask and empty cgroups; Server 1 MySQL must remain **permanently** masked. No application writer, administrative writer or unrelated MySQL may run. The restore inventory checks both nodes and refuses stale or unmasked states. Keep SSH and reserve services available.
3. Inspect disk and memory on Server 2 and free Mac private work space. The present restore implementation explicitly requires physical free space of at least `max(8,000,000,000, 4,000,000,000 + 3 × expanded SQL bytes + compressed SQL bytes + 1 GiB)`, at least 1 GiB available RAM and 1 GiB free swap. This is the isolated test's separate conservative feasibility check, not the application's 4 GB reserve or an assertion that all 1 GiB is consumed. Do not run if it fails. Existing `/var/lib/mysql` must be an ordinary directory and the production datadir identity must match the inventory.
4. In the project backup's `Investment_and_Financial_Management/ops/single-database` directory, inspect the default zero-effect plan:

   ```sh
   python3 -B single_database_restore.py
   ```

   Verify `restore-package.json` SHA-256 against the reviewed release and inspect its eight member hashes. The reviewed package SHA-256 for this candidate is `41b87fc55ac181e4847840093f3941eaddf7153c1dbefec57cf0571ff1e484f2`; check the actual file again before use. The Node executable path is currently specific to this Mac. If it has moved, review an update and re-pin the package rather than bypassing validation. Do not expose the two recovery keys.
5. Generate one new UUID with `uuidgen`. Use the exact absolute backup directory and manifest hash; substitute the independently reviewed values and optional existing Server 1 control socket as appropriate:

   ```sh
   python3 -B single_database_restore.py --execute \
     --expected-package-sha256 <REVIEWED-PACKAGE-SHA256> \
     --backup <ABSOLUTE-BACKUP-DIRECTORY> \
     --manifest-sha256 <REVIEWED-MANIFEST-SHA256> \
     --run <NEW-UUID> --cleanup-after-pass \
     --key-custody-file "$CUSTODY_FILE"
   ```

   If an existing SSH control socket is required, add `--server1-control-socket <ABSOLUTE-SOCKET-PATH>`. The tool authenticates the SQL ciphertext with both key copies, decrypts it into a protected owner-private Mac working file, verifies its bounded import, performs the isolated test and removes only its own temporary data after a successful check. Successful filesystem deletion is not a claim of secure physical erasure.
6. Accept a result only after inspecting the generated `completion.json`: it must say `PASS`, bind the exact backup manifest and data digest, report isolated MySQL exited, owned cleanup completed, and unchanged production datadir identity. The actual result, not this guide, establishes whether this snapshot passed. If anything times out, fails or becomes unknown, retain its private run directory and sentinel. Inspect the exact original unit, cgroup and owned files; never retry an ambiguous run blindly.
7. After the isolated test and any separately approved cleanup, remove only the temporary runtime masks, preserve Server 1's permanent MySQL mask, and restart Server 2 MySQL and monitor before both application stacks. Verify local application readiness, real authenticated operations, disk reserve and swap. Do not delete the previous recovery copy until the replacement and its actual isolated restore have passed and been reviewed.

The per-node encrypted configuration archives include the required tester-password file, ACME account state and renewal configuration, selected firewall and sysctl configuration, fstab, public user/group mappings and installed-package/version inventory. Backups made with the updated capture also include each host's current APT sources, the referenced MySQL and Ubuntu public signing keyrings, and the installed MySQL AppArmor profile and local override when present on Server 1, and the required MySQL AppArmor profile and local override on Server 2. Earlier backups must not be assumed to contain these additions; check each archive manifest. Package availability and software reinstallation on a blank host remain unproven. Only protected configuration is captured; the encrypted files are in OneDrive. Before encryption, temporary 0600 plaintext exists only in a 0700 owner-private Mac recovery directory and is removed after successful encryption and dual-key verification. The archives exclude `/etc/shadow` and are not OS images. Current Server 2 application certificates stored under `entry-tls` are not thereby proven to renew independently on that node.

The current v2 schema proof records the unmodified source DDL digest and separately compares a narrowly normalized schema digest plus an independent ordered `INFORMATION_SCHEMA` column-attribute digest. The only normalization adds the implied `CHARACTER SET utf8mb4` to a top-level character-column definition already explicitly using `COLLATE utf8mb4_unicode_ci`; all other bytes remain significant. Other schema constraints remain covered by the DDL digest. Older v1 backup manifests remain subject to the original exact raw-schema check; they are not upgraded by this procedure. A successful v2 comparison does not itself validate grants, trigger execution or a full fresh-host rebuild.


## Small maintenance procedure

`python3 maintain_system.py` shows the offline plan and makes no network calls. After reviewing both maintenance files and the current topology, execute it from the Mac with `--execute --expected-worker-sha256 <REVIEWED-SHA256>`; pass `--server1-control-socket <ABSOLUTE-SOCKET>` only when using an already authenticated SSH control socket. The worker hash binds the reviewed exact remote code. SSH authentication and recovery keys stay in their existing protected locations; no password is put on the command line.

During `start-db`, MySQL may report an active process before its socket accepts connections. The worker waits up to 90 seconds only for recognized connection failures while the service remains active; it checks the exact database UUID and port before starting the monitor. A successful query returning the wrong identity, another SQL error, a query timeout or an inactive service stops the sequence. Preflight remains a single immediate identity check. This is read-only readiness polling, not a replay of an unknown service or business operation.

The script requires both stacks healthy and Server 1 MySQL masked and stopped. It stops both entries, checkers and Tomcats, then Server 2's monitor and single MySQL. It cleans only the APT package download cache, journals older than 14 days after rotation, and eligible `catalina.YYYY-MM-DD.log` / `localhost.YYYY-MM-DD.log` files in the current project Tomcat's log directory when older than 14 days and not open. It retains current log directories. It does not delete databases, files in user libraries, keys, old datadirs, testing schemas, binlogs or transactions. It starts Server 2 MySQL and monitor and then both application stacks, checking local readiness for all four applications.

Results are written to an owner-private run directory under `~/Documents/Server_Recovery_Work/Single_Database_Maintenance/`. Any failure or unknown result leaves the exclusive lock and a receipt; inspect both hosts and the receipt before deciding the next action rather than running again blindly. After every stop and cleanup step has a definite success result, the local `finally` block starts the database and verifies its identity before starting the applications. It does not reverse any failed or unknown remote step; an original stop may still be running. It cannot execute after a Mac process is forcibly killed or during sustained network loss. In either case, first determine whether the original remote worker and any systemd job have finished, reconcile the actual unit state and then follow the normal manual start sequence. The script is not a failover mechanism, disaster recovery test, full system cleanup, or fresh-host rebuild tool.

The stopped-state checks are point-in-time samples. They do not fence a privileged administrator or external automation from restarting services during maintenance; schedule an exclusive maintenance window and ensure no other operator or automation changes the stacks. The local sentinel prevents accidental overlap only on this Mac. Local admission checks establish current readiness, not a backup restore or an end-to-end authenticated business-operation test. The script deliberately retains recent journals and logs; it does not claim to remove every system log or cache.
