from types import SimpleNamespace
import unittest
from unittest.mock import patch
import reserve_observer as observer
from install_node import reserved_blocks


class ReserveBoundaryTest(unittest.TestCase):
    def config(self):
        return {"boundaryPolicy": observer.BOUNDARY_POLICY,"reservedBytes": 4_000_000_000, "reservedUid": 999, "reservedGid": 998,
                "volumes": [{"id": "root", "path": "/test-volume", "device": "/dev/test","kernelReservedClusters":4096}],
                "applicationUnits": ["mysql.service", "tomcat.service"], "managedRootWriterUnits": []}

    def observe(self, config=None, blocks=None, block_size=4096,kernel_clusters=4096,delta_blocks=0):
        count = reserved_blocks(block_size,4096) if blocks is None else blocks
        layout={"filesystem":"ext4","deviceMatchesMount":True,"blockSize":block_size,"clusterSize":block_size,
                "rootReservedBlocks":count,"rootReservedBytes":count*block_size,"kernelReservedClusters":kernel_clusters,
                "kernelReservedBytes":kernel_clusters*block_size,"reservedUid":999,"reservedGid":998,
                "mountedReservedUid":999,"mountedReservedGid":998}
        total_blocks=(4_000_000_000+block_size-1)//block_size
        status = SimpleNamespace(f_bfree=1_000_000+total_blocks+delta_blocks, f_bavail=1_000_000, f_frsize=block_size)
        with patch.object(observer.os, "statvfs", return_value=status), \
             patch.object(observer, "volume_layout",return_value=layout), \
             patch.object(observer, "unit_enforced", return_value=True):
            return observer.observe(self.config() if config is None else config)

    def test_only_exact_floor_with_minimum_block_rounding_is_accepted(self):
        self.assertEqual(972467, reserved_blocks(4096,4096))
        self.assertTrue(self.observe()["guaranteed"])
        self.assertEqual(4_000_000_000 + 4096 * 1_000_000, self.observe()["volumes"][0]["availableBytes"])
        for difference in (-1, 1):
            self.assertFalse(self.observe(blocks=reserved_blocks(4096,4096)+difference)["guaranteed"])
        self.assertFalse(self.observe(blocks=976563)["guaranteed"])  # Old root-only floor adds a hidden 16 MiB.
        self.assertFalse(self.observe({**self.config(), "reservedBytes": 5_000_000_000 + 512 * 1024**2})["guaranteed"])
        for value in (0, -1, True, 4096.0):
            with self.assertRaises(ValueError): reserved_blocks(value,4096)
        with self.assertRaises(ValueError):reserved_blocks(4096)
        for value in (0,-1,True,4096.0):
            with self.assertRaises(ValueError):reserved_blocks(4096,value)

    def test_kernel_runtime_drift_missing_aggregate_policy_or_wrong_available_delta_close(self):
        for clusters in (2048,8192):
            self.assertFalse(self.observe(kernel_clusters=clusters)["guaranteed"])
        for policy in (None,"root-only","ext4-aggregate-unverified"):
            self.assertFalse(self.observe({**self.config(),"boundaryPolicy":policy})["guaranteed"])
        self.assertFalse(self.observe(delta_blocks=4096)["guaranteed"])
        self.assertFalse(self.observe(delta_blocks=-1)["guaranteed"])
        value=self.observe()["volumes"][0]
        self.assertEqual(16_777_216,value["kernelReservedBytes"])
        self.assertEqual(3_983_224_832,value["rootReservedBytes"])
        self.assertEqual(4_000_002_048,value["aggregateReservedBytes"])

    def test_bigalloc_counts_cluster_bytes_without_hiding_extra_margin(self):
        root=reserved_blocks(4096,4096,16384)
        self.assertEqual(4_000_002_048,root*4096+4096*16384)
        with self.assertRaises(ValueError):reserved_blocks(4096,4096,6000)

    def test_layout_binds_tune_metadata_to_the_actual_mounted_block_device(self):
        import stat,os
        metadata="Block size: 4096\nReserved block count: 972467\nReserved blocks uid: 999\nReserved blocks gid: 998\n"
        volume=self.config()["volumes"][0]
        def answer(*args):
            if "tune2fs" in args[0]:return metadata
            return "ext4" if "FSTYPE" in args else "8:1"
        with patch.object(observer.Path,"stat",return_value=SimpleNamespace(st_mode=stat.S_IFBLK|0o660,st_rdev=os.makedev(8,1))),patch.object(observer.Path,"read_text",side_effect=lambda *a,**k: "rw\nresuid=999\nresgid=998\n" if str(a[0]).endswith("/options") else "4096\n",autospec=True),patch.object(observer,"output",side_effect=answer):
            layout=observer.volume_layout(volume)
            self.assertTrue(observer.valid_layout(self.config(),volume,layout))
            self.assertFalse(observer.valid_layout(self.config(),volume,{**layout,"deviceMatchesMount":False}))

    def test_missing_volumes_and_root_reserve_identity_are_unprotected(self):
        for change in ({"volumes": []}, {"reservedUid": 0}, {"reservedGid": 0}):
            self.assertFalse(self.observe({**self.config(), **change})["guaranteed"])

    def test_reserved_uid_or_group_cannot_be_a_managed_writer(self):
        systemd = "LoadState=loaded\nMainPID=42\nUser=tomcat\nCapabilityBoundingSet=cap_net_bind_service\n"
        def accepted(uid, gid, groups="", effective="0"):
            status = f"Uid: {uid} {uid} {uid} {uid}\nGid: {gid} {gid} {gid} {gid}\nGroups: {groups}\nCapEff: {effective}\nCapPrm: 0\nCapInh: 0\nCapAmb: 0\nCapBnd: 0\n"
            with patch.object(observer, "output", return_value=systemd), patch.object(observer.Path, "read_text", return_value=status):
                return observer.unit_enforced("tomcat.service", True, 999, 998)
        self.assertTrue(accepted(1001,1001))
        self.assertFalse(accepted(999,1001))
        self.assertFalse(accepted(1001,998))
        self.assertFalse(accepted(1001,1001,"1001 998"))
        self.assertFalse(accepted(0,0))
        self.assertFalse(accepted(1001,1001,effective=f"{observer.CAP_SYS_RESOURCE:x}"))


if __name__ == "__main__": unittest.main()


class MountedIdentityTests(unittest.TestCase):
    def test_mount_cached_identity_or_missing_fields_close_layout(self):
        fixture=ReserveBoundaryTest()
        value=fixture.observe()["volumes"][0]
        for wrong in ({"mountedReservedUid":0},{"mountedReservedGid":0},
                      {"mountedReservedUid":True},{"mountedReservedGid":999}):
            self.assertFalse(observer.valid_layout(fixture.config(),fixture.config()["volumes"][0],{**value,**wrong}))
        value.pop("mountedReservedUid")
        self.assertFalse(observer.valid_layout(fixture.config(),fixture.config()["volumes"][0],value))

    def test_runtime_proc_options_are_required_not_raw_superblock_defaults(self):
        with patch.object(observer.Path,"read_text",return_value="rw\nresuid=999\nresgid=998\n"):
            self.assertEqual((999,998),observer.mounted_reserve_identity("/dev/test"))
        for raw in ("rw\n", "rw\nresuid=999\n", "resuid=999\nresgid=998\nresuid=0\n", "resuid=-1\nresgid=998\n"):
            with patch.object(observer.Path,"read_text",return_value=raw),self.assertRaises(ValueError):
                observer.mounted_reserve_identity("/dev/test")

    def test_filesystem_uid_cannot_be_the_reserved_owner(self):
        systemd="LoadState=loaded\nMainPID=42\nUser=writer\nCapabilityBoundingSet=cap_net_bind_service\n"
        status="Uid: 1001 1001 1001 999\nGid: 1001 1001 1001 1001\nGroups: 1001\nCapEff: 0\nCapPrm: 0\nCapInh: 0\nCapAmb: 0\nCapBnd: 0\n"
        with patch.object(observer,"output",return_value=systemd),patch.object(observer.Path,"read_text",return_value=status):
            self.assertFalse(observer.unit_enforced("writer.service",True,999,998))


class RunningCapabilityTests(unittest.TestCase):
    def test_current_effective_drop_or_new_unit_bounds_do_not_hide_regainable_capability(self):
        systemd="LoadState=loaded\nMainPID=42\nUser=writer\nCapabilityBoundingSet=cap_net_bind_service\n"
        identity="Uid: 1001 1001 1001 1001\nGid: 1001 1001 1001 1001\nGroups: 1001\n"
        names=("CapEff","CapPrm","CapInh","CapAmb","CapBnd")
        for unsafe in names:
            status=identity+"".join(name+": "+("1000000" if name==unsafe else "0")+"\n" for name in names)
            with patch.object(observer,"output",return_value=systemd),patch.object(observer.Path,"read_text",return_value=status):
                self.assertFalse(observer.unit_enforced("writer.service",True,999,998),unsafe)


class InactiveWriterPolicyTests(unittest.TestCase):
    def setUp(self):
        import tempfile
        from pathlib import Path
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);(self.root/"cgroup.controllers").write_text("memory pids\n")
        self.unit="isolated-writer.service";self.cg=self.root/"system.slice"/self.unit;self.cg.mkdir(parents=True);(self.cg/"cgroup.procs").write_text("")
        self.fields={"LoadState":"loaded","ActiveState":"inactive","SubState":"dead","MainPID":"0","ControlGroup":"/system.slice/"+self.unit,"Slice":"system.slice","User":"writer","Group":"writer","SupplementaryGroups":"","NoNewPrivileges":"yes","CapabilityBoundingSet":"cap_net_bind_service"}
        user=SimpleNamespace(pw_uid=1001,pw_gid=1001,pw_name="writer")
        for target,replacement in (("CGROUP_ROOT",self.root),("pwd.getpwnam",lambda _:user),("grp.getgrnam",lambda _:SimpleNamespace(gr_gid=1001)),("os.getgrouplist",lambda *_:[1001])):
            patched=patch.object(observer,target,replacement) if "." not in target else patch("reserve_observer."+target,replacement)
            patched.start();self.addCleanup(patched.stop)

    def accepted(self,**change):
        return observer.inactive_ordinary_writer_enforced(self.unit,{**self.fields,**change},999,998)

    def test_stopped_or_failed_safe_writer_with_empty_cgroup_preserves_capacity(self):
        self.assertTrue(self.accepted())
        self.assertTrue(self.accepted(ActiveState="failed",SubState="failed"))
        (self.cg/"cgroup.procs").unlink();self.cg.rmdir()
        self.assertTrue(self.accepted(ControlGroup=""))

    def test_unknown_transition_writable_privileged_or_reserved_identity_is_rejected(self):
        for change in ({"LoadState":"masked"},{"MainPID":"1"},{"ActiveState":"activating"},{"SubState":"start-pre"},
                       {"NoNewPrivileges":"no"},{"CapabilityBoundingSet":"cap_net_bind_service cap_sys_resource"},
                       {"CapabilityBoundingSet":"cap_setuid"},{"User":""},{"Slice":"unknown.slice"},{"ControlGroup":"/wrong"}):
            self.assertFalse(self.accepted(**change),change)
        with patch.object(observer.pwd,"getpwnam",return_value=SimpleNamespace(pw_uid=0,pw_gid=0,pw_name="root")):
            self.assertFalse(self.accepted(User="root"))
        with patch.object(observer.os,"getgrouplist",return_value=[1001,998]):self.assertFalse(self.accepted())
        with patch.object(observer.grp,"getgrnam",return_value=SimpleNamespace(gr_gid=998)):
            self.assertFalse(self.accepted(SupplementaryGroups="reserved"))

    def test_residual_process_in_parent_or_child_cgroup_closes_protection(self):
        (self.cg/"cgroup.procs").write_text("4242\n");self.assertFalse(self.accepted())
        (self.cg/"cgroup.procs").write_text("");child=self.cg/"child";child.mkdir();(child/"cgroup.procs").write_text("4243\n")
        self.assertFalse(self.accepted())

    def test_mainpid_zero_does_not_override_dangerous_or_unproven_configuration(self):
        text="\n".join(k+"="+v for k,v in self.fields.items())
        with patch.object(observer,"output",return_value=text):self.assertTrue(observer.unit_enforced(self.unit,True,999,998))
        with patch.object(observer,"output",return_value=text.replace("NoNewPrivileges=yes","NoNewPrivileges=no")):
            self.assertFalse(observer.unit_enforced(self.unit,True,999,998))
