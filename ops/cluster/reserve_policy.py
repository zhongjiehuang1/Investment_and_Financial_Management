#!/usr/bin/env python3
"""Restore the configured ext4 kernel metadata pool, then verify one 4-GB total.

This is a bounded boot policy, not a cleanup script. It never changes block
reservation counts, deletes data or disables the kernel metadata emergency pool.
"""
import argparse
import json
import os
from pathlib import Path
import stat

from reserve_observer import APP_FLOOR, BOUNDARY_POLICY, kernel_reserve_path, valid_layout, volume_layout


def apply_policy(config, apply=False):
    if (config.get("boundaryPolicy") != BOUNDARY_POLICY or type(config.get("reservedBytes")) is not int
            or config["reservedBytes"] != APP_FLOOR or not isinstance(config.get("volumes"), list)
            or not config["volumes"]):
        raise ValueError("Explicit aggregate ext4 boundary policy is required")
    for volume in config["volumes"]:
        expected = volume.get("kernelReservedClusters")
        if type(expected) is not int or expected <= 0:
            raise ValueError("A positive persisted kernel metadata pool is required")
        layout = volume_layout(volume)
        if layout["filesystem"] != "ext4" or not layout["deviceMatchesMount"]:
            raise ValueError("The configured ext4 device does not match the mounted data volume")
        if apply and layout["kernelReservedClusters"] != expected:
            # This exact, device-derived sysfs attribute is the only mutation.
            # No arbitrary output path and no tune2fs write are accepted here.
            path = kernel_reserve_path(volume["device"])
            with path.open("w") as stream:
                stream.write(str(expected) + "\n")
            layout = volume_layout(volume)
        if not valid_layout(config, volume, layout):
            raise ValueError("Aggregate boundary drifted; application startup remains closed")
        status = os.statvfs(volume["path"])
        physical, ordinary = status.f_bfree * status.f_frsize, status.f_bavail * status.f_frsize
        aggregate = layout["rootReservedBytes"] + layout["kernelReservedBytes"]
        if physical < aggregate or physical - ordinary != aggregate or status.f_frsize != layout["blockSize"]:
            raise ValueError("Available space does not establish the configured aggregate boundary")
    return True


def read_config(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022 or info.st_size > 65536:
            raise ValueError("Root-owned, non-writable policy configuration is required")
        return json.loads(stream.read(65537))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument("--apply", action="store_true", help="Restore only the configured nonzero kernel pool before verifying")
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SystemExit("Root is required")
    apply_policy(read_config(args.config), args.apply)
    print("Aggregate ext4 root-plus-kernel reserve verified; minimum block rounding only.")


if __name__ == "__main__":
    main()
