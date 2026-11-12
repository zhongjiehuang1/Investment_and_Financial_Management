import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('stage_mysql',Path(__file__).with_name('stage_mysql_replication.py'))
stage=importlib.util.module_from_spec(spec);spec.loader.exec_module(stage)


class MysqlStageTest(unittest.TestCase):
    def test_both_peers_use_safe_distinct_loopback_ports_and_same_group(self):
        group='11111111-1111-4111-8111-111111111111'
        one,two=stage.render(1,group),stage.render(2,group)
        self.assertIn('port=331\n',one);self.assertIn('port=332\n',two)
        self.assertIn('local_address=127.0.0.1:331',one)
        self.assertIn('local_address=127.0.0.1:332',two)
        for config in (one,two):
            self.assertNotIn('transaction_write_set_extraction',config)  # Removed in the installed MySQL release.
            for required in ('bind_address=127.0.0.1','super_read_only=ON','gtid_mode=ON','skip_replica_start=ON',
                             'group_replication_start_on_boot=OFF','group_replication_consistency=BEFORE_AND_AFTER',
                             'group_replication_single_primary_mode=OFF','group_replication_enforce_update_everywhere_checks=ON',
                             'group_replication_communication_stack=MYSQL','require_secure_transport=ON',
                             'group_replication_ssl_mode=VERIFY_IDENTITY','group_replication_recovery_use_ssl=ON',
                             'group_replication_recovery_ssl_verify_server_cert=ON','group_replication_recovery_get_public_key=OFF',
                             'innodb_flush_log_at_trx_commit=1','sync_binlog=1',group):
                self.assertIn(required,config)

    def test_invalid_node_or_group_is_rejected(self):
        with self.assertRaises(ValueError):stage.render(3,'11111111-1111-4111-8111-111111111111')
        with self.assertRaises(ValueError):stage.render(1,'not-a-uuid')
        with self.assertRaises(ValueError):stage.render(1,'11111111-1111-4111-8111-111111111111',(1,2,2))
        with self.assertRaises(ValueError):stage.render(1,'11111111-1111-4111-8111-111111111111',(1,10))

    def test_extra_or_unmapped_physical_members_are_refused(self):
        group='11111111-1111-4111-8111-111111111111'
        for members in ((1,2,3),(10,20),(10,20,30)):
            with self.assertRaises(ValueError):stage.render(members[0],group,members)
        for node in (1,2):
            rendered=stage.render(node,group)
            self.assertIn(f'server_id={node}\n',rendered)
            self.assertIn(f'auto_increment_offset={node}\n',rendered)
            self.assertIn('auto_increment_increment=7\n',rendered)
            self.assertIn('group_replication_auto_increment_increment=7\n',rendered)


if __name__=='__main__':unittest.main()


class SecureStageTests(unittest.TestCase):
    def test_split_unauthenticated_group_port_and_unprotected_high_port_rejected(self):
        group='11111111-1111-4111-8111-111111111111'
        with self.assertRaises(ValueError):stage.render(1,group,port_map={1:(331,361),2:(332,362)})
        with self.assertRaises(ValueError):stage.render(1,group,port_map={1:(3306,3306),2:(3307,3307)})
        with self.assertRaises(ValueError):stage.render(1,group,tls_directory='/tls\ninject=ON')


class FragmentStageTests(unittest.TestCase):
    def test_explicit_reviewed_fragment_bounds_match_the_staged_mysql_policy(self):
        group='11111111-1111-4111-8111-111111111111'
        for value in (524288,1048576):
            self.assertIn('group_replication_communication_max_message_size='+str(value),stage.render(1,group,fragment_bytes=value))
        with self.assertRaises(ValueError):stage.render(1,group,fragment_bytes=10485760)
