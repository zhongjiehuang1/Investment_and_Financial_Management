#!/usr/bin/env python3
"""Read-only privileged observer; unix socket only, no configuration mutation API."""
import json
import os
import pwd
import grp
import re
import stat as stat_module
import socketserver
import subprocess
import sys
from pathlib import Path

CAP_SYS_RESOURCE = 1 << 24
APP_FLOOR = 4_000_000_000
BOUNDARY_POLICY = "ext4-root-plus-kernel-v1"
CGROUP_ROOT = Path("/sys/fs/cgroup")


def required_root_blocks(block_size, kernel_reserved_clusters=None, cluster_size=None):
    """Count the kernel metadata emergency pool INSIDE the one 4-GB boundary."""
    if type(block_size) is not int or block_size <= 0 or block_size & (block_size - 1):
        raise ValueError("A positive filesystem block size is required")
    if cluster_size is None:
        cluster_size = block_size
    if (type(cluster_size) is not int or cluster_size < block_size or cluster_size % block_size
            or cluster_size & (cluster_size - 1) or type(kernel_reserved_clusters) is not int
            or kernel_reserved_clusters <= 0):
        raise ValueError("An explicit positive kernel metadata reserve is required")
    total = ((APP_FLOOR + block_size - 1) // block_size) * block_size
    remainder = total - kernel_reserved_clusters * cluster_size
    if remainder < 0 or remainder % block_size:
        raise ValueError("Kernel metadata reserve cannot exceed the aggregate boundary")
    return remainder // block_size


def kernel_reserve_path(device):
    resolved = Path(device).resolve()
    if not str(resolved).startswith("/dev/") or not re.fullmatch(r"[A-Za-z0-9_.-]+", resolved.name):
        raise ValueError("An exact local block device is required")
    return Path("/sys/fs/ext4") / resolved.name / "reserved_clusters"


def mounted_reserve_identity(device):
    # tune2fs reports the on-disk defaults. A mounted superblock may still use
    # an older cached identity or a mount override; only the live kernel view
    # establishes which ordinary filesystem credentials can use the reserve.
    device_name = kernel_reserve_path(device).parent.name
    raw = (Path("/proc/fs/ext4") / device_name / "options").read_text()
    result = []
    for label in ("resuid", "resgid"):
        matches = re.findall(r"^" + label + r"=([0-9]+)$", raw, re.MULTILINE)
        if len(matches) != 1:
            raise ValueError("An explicit current ext4 reserve identity is required")
        result.append(int(matches[0]))
    return tuple(result)


def ext4_fields(metadata):
    numbers = {}
    for label in ("Block size", "Reserved block count", "Reserved blocks uid", "Reserved blocks gid"):
        match = re.search(r"^" + re.escape(label) + r":\s*(\d+)", metadata, re.MULTILINE)
        if not match:
            raise ValueError("Missing ext4 boundary field")
        numbers[label] = int(match.group(1))
    cluster = re.search(r"^Cluster size:\s*(\d+)", metadata, re.MULTILINE)
    numbers["Cluster size"] = int(cluster.group(1)) if cluster else numbers["Block size"]
    return numbers


def volume_layout(volume):
    device = Path(volume["device"])
    info = device.stat()
    if not stat_module.S_ISBLK(info.st_mode):
        raise ValueError("A real block device is required")
    identity = output("/usr/bin/findmnt", "-n", "-o", "MAJ:MIN", "--target", volume["path"]).strip()
    filesystem = output("/usr/bin/findmnt", "-n", "-o", "FSTYPE", "--target", volume["path"]).strip()
    numbers = ext4_fields(output("/usr/sbin/tune2fs", "-l", str(device)))
    kernel_clusters = int(kernel_reserve_path(device).read_text().strip())
    mounted_uid, mounted_gid = mounted_reserve_identity(device)
    return {"filesystem": filesystem, "deviceMatchesMount": identity == str(os.major(info.st_rdev)) + ":" + str(os.minor(info.st_rdev)),
            "blockSize": numbers["Block size"], "clusterSize": numbers["Cluster size"],
            "rootReservedBlocks": numbers["Reserved block count"],
            "rootReservedBytes": numbers["Reserved block count"] * numbers["Block size"],
            "kernelReservedClusters": kernel_clusters,
            "kernelReservedBytes": kernel_clusters * numbers["Cluster size"],
            "reservedUid": numbers["Reserved blocks uid"], "reservedGid": numbers["Reserved blocks gid"],
            "mountedReservedUid": mounted_uid, "mountedReservedGid": mounted_gid}


def valid_layout(config, volume, layout):
    try:
        root_blocks = required_root_blocks(layout["blockSize"], volume["kernelReservedClusters"], layout["clusterSize"])
        return (config.get("boundaryPolicy") == BOUNDARY_POLICY and config.get("reservedBytes") == APP_FLOOR
                and type(config["reservedBytes"]) is int and layout["filesystem"] == "ext4"
                and layout["deviceMatchesMount"] is True
                and type(volume["kernelReservedClusters"]) is int
                and layout["kernelReservedClusters"] == volume["kernelReservedClusters"]
                and layout["rootReservedBlocks"] == root_blocks
                and layout["reservedUid"] == config["reservedUid"] and config["reservedUid"] > 0
                and layout["reservedGid"] == config["reservedGid"] and config["reservedGid"] > 0
                and type(layout["mountedReservedUid"]) is int and type(layout["mountedReservedGid"]) is int
                and layout["mountedReservedUid"] == layout["reservedUid"]
                and layout["mountedReservedGid"] == layout["reservedGid"])
    except (KeyError, ValueError, TypeError):
        return False


def output(*arguments):
    return subprocess.check_output(arguments, text=True, timeout=3, stderr=subprocess.DEVNULL, env={"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL": "C"})


def inactive_ordinary_writer_enforced(unit, fields, reserved_uid, reserved_gid):
    """An empty stopped writer need not make its healthy peer lose capacity."""
    try:
        if (not re.fullmatch(r"[A-Za-z0-9_.@-]+\.service",unit) or fields.get("LoadState") != "loaded"
                or int(fields.get("MainPID","-1")) != 0
                or fields.get("ActiveState") not in ("inactive","failed")
                or fields.get("SubState") not in ("dead","failed") or fields.get("NoNewPrivileges") != "yes"
                or fields.get("Slice") != "system.slice"
                or not set(fields.get("CapabilityBoundingSet","").split()).issubset({"cap_net_bind_service"})):
            return False
        name=fields.get("User","")
        if not name:return False
        user=pwd.getpwuid(int(name)) if name.isdigit() else pwd.getpwnam(name)
        group=fields.get("Group","")
        gid=(grp.getgrgid(int(group)).gr_gid if group.isdigit() else grp.getgrnam(group).gr_gid) if group else user.pw_gid
        groups=set(os.getgrouplist(user.pw_name,gid)) | {gid,user.pw_gid}
        for extra in fields.get("SupplementaryGroups","").split():
            groups.add(grp.getgrgid(int(extra)).gr_gid if extra.isdigit() else grp.getgrnam(extra).gr_gid)
        if user.pw_uid in (0,reserved_uid) or reserved_gid in groups:
            return False
        if not (CGROUP_ROOT/"cgroup.controllers").is_file():
            return False  # Only the actual reviewed cgroup-v2 layout is accepted.
        expected="/system.slice/"+unit
        if fields.get("ControlGroup") not in ("",expected):
            return False
        root=CGROUP_ROOT/"system.slice"/unit
        if not root.exists():
            return fields.get("ControlGroup")==""
        if root.is_symlink() or not root.is_dir() or not (root/"cgroup.procs").is_file():
            return False
        return all(not path.read_text().strip() for path in root.rglob("cgroup.procs"))
    except (OSError,KeyError,ValueError,TypeError):
        return False


def unit_enforced(unit, must_be_nonroot=False, reserved_uid=None, reserved_gid=None):
    data = output("/usr/bin/systemctl", "show", unit, "--property=LoadState,ActiveState,SubState,MainPID,ControlGroup,Slice,User,Group,SupplementaryGroups,NoNewPrivileges,CapabilityBoundingSet")
    fields = dict(line.split("=", 1) for line in data.splitlines() if "=" in line)
    if fields.get("LoadState") == "not-found":
        return not must_be_nonroot
    pid = int(fields.get("MainPID", "0"))
    bounded = "cap_sys_resource" not in fields.get("CapabilityBoundingSet", "").split()
    if pid <= 0:
        return inactive_ordinary_writer_enforced(unit,fields,reserved_uid,reserved_gid) if must_be_nonroot else bounded
    status = Path("/proc") / str(pid) / "status"
    process = dict(line.split(":", 1) for line in status.read_text().splitlines() if ":" in line)
    uids = {int(value) for value in process["Uid"].split()}
    # Inspect the running process, not just a newly edited unit definition.
    # A permitted/inheritable/ambient privilege can be restored later, and an
    # old process can retain a wider bounding set until its next restart.
    capabilities = [int(process[name].strip(), 16) for name in ("CapEff","CapPrm","CapInh","CapAmb","CapBnd")]
    gids = {int(value) for value in (process.get("Gid", "") + " " + process.get("Groups", "")).split()}
    if reserved_uid in uids or reserved_gid in gids or (must_be_nonroot and 0 in uids):
        return False
    return bounded and not any(value & CAP_SYS_RESOURCE for value in capabilities)


def observe(config):
    volumes = []
    guaranteed = (config.get("boundaryPolicy") == BOUNDARY_POLICY and config.get("reservedBytes") == APP_FLOOR
                  and type(config.get("reservedBytes")) is int
                  and type(config.get("reservedUid")) is int and config["reservedUid"] > 0
                  and type(config.get("reservedGid")) is int and config["reservedGid"] > 0
                  and isinstance(config.get("volumes"), list) and bool(config["volumes"]))
    for volume in config["volumes"]:
        status = os.statvfs(volume["path"])
        physical = status.f_bfree * status.f_frsize
        unprivileged = status.f_bavail * status.f_frsize
        layout = volume_layout(volume)
        aggregate = layout["rootReservedBytes"] + layout["kernelReservedBytes"]
        volumes.append({"id": volume["id"], "availableBytes": min(physical, unprivileged + APP_FLOOR),
                        "physicalFreeBytes": physical, "ordinaryAvailableBytes": unprivileged,
                        "aggregateReservedBytes": aggregate, **layout})
        guaranteed &= (valid_layout(config, volume, layout) and physical >= aggregate
                       and physical - unprivileged == aggregate and status.f_frsize == layout["blockSize"])
    for unit in config["applicationUnits"]:
        guaranteed &= unit_enforced(unit, True, config["reservedUid"], config["reservedGid"])
    for unit in config["managedRootWriterUnits"]:
        guaranteed &= unit_enforced(unit, False, config["reservedUid"], config["reservedGid"])
    return {"guaranteed": bool(guaranteed), "boundaryPolicy": BOUNDARY_POLICY, "floorBytes": APP_FLOOR,
            "volumes": volumes, "scope": "ordinary data allocation; kernel emergency metadata and privileged writers can cross the boundary"}


def serve(config_path):
    config = json.loads(Path(config_path).read_text())
    socket_path = config["socket"]
    try: os.unlink(socket_path)
    except FileNotFoundError: pass

    class Handler(socketserver.StreamRequestHandler):
        def handle(self):
            if self.rfile.readline(16) != b"status\n":
                return
            try: result = observe(config)
            except Exception: result = {"guaranteed": False, "volumes": []}
            self.wfile.write(json.dumps(result, separators=(",", ":")).encode() + b"\n")

    with socketserver.ThreadingUnixStreamServer(socket_path, Handler) as server:
        os.chmod(socket_path, 0o660)
        server.daemon_threads = True
        server.serve_forever()


if __name__ == "__main__":
    if len(sys.argv) != 2: raise SystemExit("Supply root-owned reserve observer configuration path")
    serve(sys.argv[1])
