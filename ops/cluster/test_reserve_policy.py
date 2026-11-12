from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import reserve_policy as policy
import test_reserve_observer as observer_tests


class BootPolicyTest(unittest.TestCase):
    def setUp(self):
        self.config=observer_tests.ReserveBoundaryTest().config()
        self.layout={"filesystem":"ext4","deviceMatchesMount":True,"blockSize":4096,"clusterSize":4096,
                     "rootReservedBlocks":972467,"rootReservedBytes":3_983_224_832,"kernelReservedClusters":4096,
                     "kernelReservedBytes":16_777_216,"reservedUid":999,"reservedGid":998,
                     "mountedReservedUid":999,"mountedReservedGid":998}
        self.space=SimpleNamespace(f_bfree=1_976_563,f_bavail=1_000_000,f_frsize=4096)

    def test_verify_only_detects_runtime_kernel_drift_without_mutation(self):
        drift={**self.layout,"kernelReservedClusters":2048,"kernelReservedBytes":8_388_608}
        with patch.object(policy,"volume_layout",return_value=drift),patch.object(policy.Path,"open") as opened,self.assertRaises(ValueError):
            policy.apply_policy(self.config)
        opened.assert_not_called()

    def test_boot_apply_only_restores_exact_nonzero_sysfs_pool_then_verifies(self):
        drift={**self.layout,"kernelReservedClusters":2048,"kernelReservedBytes":8_388_608}
        with tempfile.TemporaryDirectory() as directory:
            target=Path(directory)/"reserved_clusters";target.write_text("2048\n")
            with patch.object(policy,"volume_layout",side_effect=[drift,self.layout]),patch.object(policy,"kernel_reserve_path",return_value=target),patch.object(policy.os,"statvfs",return_value=self.space):
                self.assertTrue(policy.apply_policy(self.config,True))
            self.assertEqual("4096\n",target.read_text())
            self.assertEqual(["reserved_clusters"],[p.name for p in Path(directory).iterdir()])

    def test_old_root_only_policy_wrong_device_or_hidden_extra_root_blocks_refuse(self):
        wrong={**self.layout,"rootReservedBlocks":976563,"rootReservedBytes":4_000_002_048}
        for layout in (wrong,{**self.layout,"deviceMatchesMount":False}):
            with patch.object(policy,"volume_layout",return_value=layout),self.assertRaises(ValueError):
                policy.apply_policy(self.config,True)
        with self.assertRaises(ValueError):policy.apply_policy({**self.config,"boundaryPolicy":"root-only"},True)
        config={**self.config,"volumes":[{**self.config["volumes"][0],"kernelReservedClusters":0}]}
        with self.assertRaises(ValueError):policy.apply_policy(config,True)

    def test_enough_aggregate_free_space_and_actual_bavail_delta_are_required(self):
        for space in (SimpleNamespace(f_bfree=976562,f_bavail=0,f_frsize=4096),
                      SimpleNamespace(f_bfree=1_980_659,f_bavail=1_000_000,f_frsize=4096)):
            with patch.object(policy,"volume_layout",return_value=self.layout),patch.object(policy.os,"statvfs",return_value=space),self.assertRaises(ValueError):
                policy.apply_policy(self.config,True)


if __name__=="__main__":unittest.main()


class MountedBootIdentityTests(unittest.TestCase):
    def setUp(self):
        BootPolicyTest.setUp(self)

    def test_boot_apply_does_not_silently_remount_wrong_cached_identity(self):
        mismatch={**self.layout,"mountedReservedUid":0,"mountedReservedGid":0}
        with patch.object(policy,"volume_layout",return_value=mismatch),patch.object(policy.Path,"open") as opened,self.assertRaises(ValueError):
            policy.apply_policy(self.config,True)
        opened.assert_not_called()
