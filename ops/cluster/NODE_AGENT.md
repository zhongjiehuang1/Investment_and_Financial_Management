> Current atomic two-node backend: use `peer_controller.py` and
> `PEER_CONTROLLER.md`. It supplies stable view/epoch permits, durable peer
> fencing and post-commit two-node GTID confirmation. This older read-only
> observer does not implement `/confirm` or those permits and cannot authorize
> the new atomic writers. Its optional GR view check now requires every online
> member to be PRIMARY and uses BEFORE_AND_AFTER; old operator attestations are
> historical/manual evidence, not automatic peer-controller fence grants.

# Private node observer

The node agent binds only to `127.0.0.1:9411`. Every `/status` and `/ready` request requires the private cluster Bearer token. `/status` freshly checks MySQL durability settings, Tomcat probes and filesystem capacity; it exposes opaque node and volume identifiers, not connection credentials. `/ready` returns 204 only after both nodes and all three authority application recovery endpoints pass. It never changes database authority.

The separate reserve observer has a local group-restricted Unix socket, runs without Linux capabilities, and only reads filesystem/process status. It verifies current ext4 reserved blocks and both on-disk and mounted-kernel
reserved UID/GID, non-root application processes and managed root-writer capabilities on each request. A manifest alone never produces `reserveGuaranteed=true`. An inactive/failed
ordinary writer is accepted only when the actual cgroup-v2 service subtree is
empty, its loaded unit has a verified nonroot/nonreserved user and groups,
NoNewPrivileges=yes and no bounding capabilities beyond NET_BIND_SERVICE.
Unknown, activating, unsafe or residual-process states remain unprotected. This
keeps reserve protection distinct from service availability: stopping one
Tomcat must not remove the healthy peer's disk guarantee. mysqlReady and
tomcatReady still report the real service/authority failures independently.

The safety boundary is exactly **4,000,000,000 bytes in aggregate**. It includes both ext4 root-reserved blocks and the kernel's `reserved_clusters` metadata emergency pool. The `ext4-root-plus-kernel-v1` policy requires `root_blocks * block_size + kernel_clusters * cluster_size == ceil(4_000_000_000 / block_size) * block_size`, plus the matching `statvfs` physical-free versus ordinary-available difference. With 4-KiB blocks/clusters and 4096 kernel clusters, root reserve is 972467 blocks (3,983,224,832 bytes), kernel reserve is 16,777,216 bytes and the total is 4,000,002,048 bytes. An old root-only 976563-block setting silently adds 16 MiB and is rejected. `reserve_policy.py` persists/validates the explicit nonzero kernel-pool setting at boot; it never changes root-block counts or deletes data. Runtime drift closes observations. The live
`/proc/fs/ext4/<device>/options` must explicitly report the same positive
`resuid` and `resgid` as the raw superblock and policy. A `tune2fs -u/-g` update
alone can leave an older identity cached in a mounted filesystem; such a mismatch
is rejected, never repaired by an automatic remount. Process filesystem UID and
all UID/group credentials are checked against the reserved owner as well. This verifies a boundary for configured ordinary data writers, not protection against kernel emergency metadata allocations, unbounded privileged writers or an administrator. Journald uses `SystemKeepFree=4000000000` in bytes. Reported admission capacity remains `min(f_bfree * f_frsize, f_bavail * f_frsize + 4_000_000_000)`, so it subtracts this same floor once, without another operating margin.

Payload accounting retains the experimental `physicalBytesPerPayloadByte=4` and `fixedOverheadBytes=67_108_864`. These describe estimated per-operation storage for file staging, duplicate physical contents, database growth, transaction/replication logs and temporary files. They are **not additional reserved system space** and are not a validated physical-growth model. The installer records `capacityModelValidated=false`, so `/ready` remains closed. Enabling it requires measured near-quota two-user uploads, maximum concurrent pending operations, rollback/recovery, database/binlog/temp growth and capacity refusal on both actual filesystems. Preserve the 4 GB floor throughout; if the model is insufficient, revise the operation-cost estimate and repeat the isolated tests before setting the flag to the boolean `true`. A config flag records operator validation; it does not perform or prove those tests.

The observer checks managed main processes, their reserved UID/group exclusion and CAP_SYS_RESOURCE bounds. Ordinary processes that lack the reserved identity/group and CAP_SYS_RESOURCE cannot allocate the ext4 reserved blocks. Processes with the reserved identity/group or CAP_SYS_RESOURCE can cross the boundary; administrative root can regain those privileges or reconfigure ext4. The checks are not a kernel-wide proof for every child process or writer. Administrative root writes, newly introduced privileged services, storage hardware faults and unbounded writes outside the managed process set are excluded. Configuration changes must revalidate that boundary. Low capacity never elects a second database authority.

Private configuration and tokens belong under `/etc/four-project-cluster/`, never in this repository. Node 1's persistent SSH connection forwards only loopback ports: local 13306 to node 2 MySQL, local 9412 to node 2's agent, and reverse 18081 on node 2 to node 1 Tomcat. A dedicated SSH key and restricted account are used. Stateful application proxy configuration is staged separately and activated only after schema migration, recovery and WAR verification.

`stage_nginx.py` generates private staging files only. Static application files are served locally; `/api/server` always returns the local configured number. Stateful routes reach the primary Tomcat and overwrite trusted ingress headers. Public internal recovery paths are denied. Upstream failures become HTTP 503 with the exact cluster failure JSON. `activate_nginx.py --maintenance-window` is an explicit later activation step: it validates the staged files, installs them and restarts active managed root services so their capability bounds actually apply. It is not run by staging.

After all WARs are installed, replace each agent's temporary Tomcat root-page check with three direct Tomcat `/api/server` checks (financial context, `starhub`, `ucost`) and the expected local node number. Node 1 recovery checks must use `/Investment_and_Financial_Management/internal/recovery-ready`, `/starhub/api/internal/recovery-ready` and `/ucost/internal/recovery-ready`. These probes bypass their own cluster health checks to avoid recursive readiness.

Certificate renewal uses a separate SSH key, not the forwarding key. A forced command for the isolated `cluster-certsync` account accepts only a size-limited bundle for the three fixed application domains. A systemd path invokes `certificate_sync.py apply`, which validates exact hostnames, expiry, public-key matches and certificate chains before installing root-owned files. It does not have `CAP_SYS_RESOURCE`; private keys never enter this repository or logs. Nginx reload is permitted only after the explicit activation marker exists. Node 1's Certbot deploy hook sends the current bundle using pinned SSH host keys. Node 2's HTTP ACME challenge routes proxy the primary HTTP challenge origin; this keeps primary renewal usable when DNS resolves to either node. The installer account directory is mode 0750, received bundle mode 0640, and the apply unit uses the receiver group solely to read the bundle.

The primary's hourly `four-project-certsend.timer` retries synchronization after a peer outage even if Certbot has already completed renewal. Identical validated certificate files do not trigger a Nginx reload. Failed sends and validation remain visible in the corresponding systemd unit status; neither operation changes database availability or elects an authority.

## Current installation compatibility and scope

The current target layout is `tomcat.service` with `/opt/tomcat/webapps`, and MySQL's socket is `/var/run/mysqld/mysqld.sock`. `install_node.py` now requires an explicit `--mysql-defaults-file /absolute/private/path.cnf`; it must be a root-owned regular file with no group/other permissions. The previously assumed `/root/four-project-maintenance/admin.cnf` is absent from the verified AWS host. Do not run this experimental installer blindly or extract credentials into logs. Prepare the least-privilege administrative option file through an approved secret channel, verify the target and staging inputs, and use `sudo` on the Ubuntu host. No installation was performed by the local tests.

This observer still authorizes **one PRIMARY writer** and requires all other group members to be SECONDARY. Staged stateful Nginx routes still target one selected coordinator. It implements no automatic fencing or promotion, no peer durable operation acknowledgments and no restart-safe rejoin controller. A fresh health response or an operator proof file is not an implementation of those mechanisms. It does not satisfy the requested equal dual-writer design and must not be presented as production automatic failover. Its three-member examples are historical development material; this task permits only the two existing servers.

Local validation on 11 November 2026: `python3 -m unittest discover -s ops/cluster -p 'test_*.py' -v` passed 125 tests, including exact aggregate/block rounding, mounted versus raw reserve identity, filesystem UID exclusion, real localhost authority protocol tests, replay/restart/partition models, TLS policy, complete content hashing, database/view/routine/trigger/event definition auditing, live SQL-policy/guard endpoint matching and explicit old-writer drainage beyond SRO. These tests do not replace the installed-system failure matrix or capacity calibration.

Separate real tests filled an isolated ext4 loop filesystem on each host to
ordinary-data ENOSPC. Both retained exactly 4,000,002,048 physical free bytes.
Root without CAP_SYS_RESOURCE also received ENOSPC when the reserved UID/GID
had been set before mounting; privileged root then successfully crossed that
boundary. The images and mounts were removed, and the real root filesystems
were never filled. The earlier experiment that changed tune2fs UID/GID after
mounting retained a stale kernel root identity and allowed that root writer to
cross; it is retained as a failed setup and explains why the live mount identity
check is mandatory. These findings support the stated ordinary-data scope,
not global protection from metadata or privileged allocations. They do not
calibrate Star_Hub file/database/binlog/temp growth or prove full-quota capacity.

`verify_recovery_bundle.py` validates the older archive format containing `var/lib/tomcat9/webapps` paths. Those expectations intentionally identify a historical backup format; a successful archive-integrity check is not proof of a full current Tomcat 11/MySQL 26.10 recovery. A current off-server backup needs its own isolated restore and application verification.
