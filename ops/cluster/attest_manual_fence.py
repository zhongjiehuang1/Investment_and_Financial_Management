#!/usr/bin/env python3
"""Record a short-lived, view-bound *manual* peer-fencing attestation.

This tool does not stop a cloud instance and cannot verify the operator's
claim. The operator must independently confirm the excluded instance is
stopped and prevented from writing before using it or forcing group membership.
"""
import argparse
import grp
import json
import os
from pathlib import Path
import tempfile
import time

from node_agent import Agent, replication_policy


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path)
    parser.add_argument("--peer-confirmed-stopped", required=True, action="append", metavar="MYSQL_SERVER_UUID",
                        help="Repeat for every excluded physical member; verify each cloud stop independently")
    parser.add_argument("--i-verified-cloud-stop", action="store_true")
    parser.add_argument("--valid-minutes", type=int, default=30, choices=range(1, 61))
    args = parser.parse_args()
    if os.geteuid() != 0 or not args.i_verified_cloud_stop:
        raise SystemExit("Root and explicit confirmation of the independently verified cloud stop are required")
    config = json.loads(args.config.read_text())
    if config.get("readyMode") != "replicated":
        raise SystemExit("Replicated readiness is not configured")
    target = Path(config["fenceAttestationFile"])
    parent = target.parent.resolve()
    if parent != Path("/etc/four-project-cluster") or target.name in ("", ".", ".."):
        raise SystemExit("Fence attestation must be under the private cluster configuration directory")
    parent_stat = parent.stat()
    if parent_stat.st_uid != 0 or parent_stat.st_mode & 0o022:
        raise SystemExit("Fence attestation directory must be root-owned and not writable by others")
    agent = Agent(config)
    state = agent.group_state()
    now = int(time.time() * 1000)
    proof = {"kind": "operator-confirmed-cloud-fence", "groupId": state.get("groupId"),
             "viewId": state.get("viewId"), "survivorId": state.get("localId"),
             "fencedIds": args.peer_confirmed_stopped, "issuedAtMillis": now,
             "expiresAtMillis": now + args.valid_minutes * 60_000}
    if replication_policy(state, config["replication"], proof, now) not in ("single", "fenced"):
        raise SystemExit("The observed minority view and independently stopped member identities do not match")
    fd, temporary = tempfile.mkstemp(prefix=".fence-", dir=parent)
    try:
        os.fchown(fd, 0, grp.getgrnam("cluster-agent").gr_gid)
        os.fchmod(fd, 0o640)
        with os.fdopen(fd, "w") as out:
            json.dump(proof, out, separators=(",", ":"));out.write("\n");out.flush();os.fsync(out.fileno())
        os.replace(temporary, target)
        directory = os.open(parent, os.O_RDONLY)
        try: os.fsync(directory)
        finally: os.close(directory)
    finally:
        Path(temporary).unlink(missing_ok=True)
    print("Manual fence attestation recorded for the current group view; it expires in", args.valid_minutes, "minutes.")


if __name__ == "__main__":
    main()
