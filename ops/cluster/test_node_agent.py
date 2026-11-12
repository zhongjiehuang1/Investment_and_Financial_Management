import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("node_agent", Path(__file__).with_name("node_agent.py"))
agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)


class FreshStatusTest(unittest.TestCase):
    def test_recovery_authority_requires_replicated_local_writable_view_and_mysql(self):
        observer = agent.Agent.__new__(agent.Agent)
        local = "11111111-1111-4111-8111-111111111111"
        peer = "22222222-2222-4222-8222-222222222222"
        config = {"groupId": "group-a", "localMemberUuid": local, "memberUuids": [local, peer]}
        state = {"localId": local, "groupId": "group-a", "consistency": "BEFORE_AND_AFTER", "readOnly": 0,
                 "viewId": "view-1", "members": [{"id": local, "state": "ONLINE", "role": "PRIMARY"},
                                                   {"id": peer, "state": "ONLINE", "role": "PRIMARY"}]}
        observer.config = {"readyMode": "replicated", "fenceAttestationFile": "/does-not-exist", "replication": config}
        observer.mysql_ready = lambda: True
        observer.group_state = lambda: state
        self.assertEqual(local, observer.authority_identity())
        observer.group_state = lambda: {**state, "members": [state["members"][0]]}
        self.assertIsNone(observer.authority_identity())
        observer.group_state = lambda: state
        observer.mysql_ready = lambda: False
        self.assertIsNone(observer.authority_identity())
        observer.mysql_ready = lambda: True
        observer.config["readyDelegate"] = "http://127.0.0.1:18081/"
        self.assertIsNone(observer.authority_identity())
        observer.config = {}
        self.assertIsNone(observer.authority_identity())

    def test_status_reports_same_local_replication_view_and_fence_flag(self):
        observer = agent.Agent.__new__(agent.Agent)
        observer.config = {"nodeId": 1}
        observer.mysql_ready = lambda: True
        observer.tomcat_ready = lambda: True
        observer.reserve = lambda: {"guaranteed": True, "volumes": []}
        observer.replication_mode = lambda: "dual"
        status = observer.status()
        self.assertEqual("dual", status["replicationMode"])
        self.assertFalse(status["singleNodeAuthorized"])
        observer.replication_mode = lambda: "single"
        status = observer.status()
        self.assertEqual("single", status["replicationMode"])
        self.assertTrue(status["singleNodeAuthorized"])

    def test_only_correct_fresh_healthy_identity_is_accepted(self):
        status = {"nodeId": 2, "observedAtMillis": 1000, "mysqlReady": True, "tomcatReady": True}
        self.assertTrue(agent.snapshot_fresh(status, 2, 2000))
        self.assertFalse(agent.snapshot_fresh(status, 1, 2000))
        self.assertFalse(agent.snapshot_fresh(status, 2, 999))
        self.assertFalse(agent.snapshot_fresh(status, 2, 11001))
        for key in ("mysqlReady", "tomcatReady"):
            self.assertFalse(agent.snapshot_fresh({**status, key: False}, 2, 2000))
            self.assertFalse(agent.snapshot_fresh({**status, key: "true"}, 2, 2000))

    def test_replicated_writer_requires_real_protected_disk_headroom(self):
        floor = 4_000_000_000
        healthy = {"reserveGuaranteed": True, "capacityModelValidated": True, "volumes": [{"availableBytes": floor}]}
        self.assertTrue(agent.write_headroom(healthy))
        self.assertFalse(agent.write_headroom({**healthy,"reserveGuaranteed":False}))
        for unverified in (None, False, "true"):
            self.assertFalse(agent.write_headroom({**healthy,"capacityModelValidated":unverified}))
        self.assertFalse(agent.write_headroom({**healthy,"volumes":[]}))
        self.assertFalse(agent.write_headroom({**healthy,"volumes":[{"availableBytes":floor-1}]}))
        self.assertFalse(agent.write_headroom({**healthy,"volumes":[{"availableBytes":True}]}))

    def test_unvalidated_capacity_model_keeps_readiness_closed(self):
        observer = agent.Agent.__new__(agent.Agent)
        for flag in (None, False, "true"):
            observer.config = {"capacityModelValidated": flag}
            self.assertFalse(observer.ready())


class ReplicationPolicyTest(unittest.TestCase):
    def setUp(self):
        self.cfg = {"groupId": "group-a", "localMemberUuid": "node-a", "memberUuids": ["node-a", "node-b"]}
        self.state = {"localId": "node-a", "groupId": "group-a", "consistency": "BEFORE_AND_AFTER", "readOnly": 0,
                      "viewId": "view-3", "members": [{"id": "node-a", "state": "ONLINE", "role": "PRIMARY"},
                                                        {"id": "node-b", "state": "ONLINE", "role": "PRIMARY"}]}
        self.proof = {"kind": "operator-confirmed-cloud-fence", "groupId": "group-a", "viewId": "view-3",
                      "survivorId": "node-a", "fencedId": "node-b", "issuedAtMillis": 1000, "expiresAtMillis": 2000}

    def test_both_members_need_expected_identity_and_equal_primaries(self):
        self.assertEqual("dual", agent.replication_policy(self.state, self.cfg, None, 1500))
        for change in ({"consistency": "EVENTUAL"}, {"readOnly": 1}, {"groupId": "other"}, {"viewId": ""},
                       {"members": [self.state["members"][0], {"id": "node-b", "state": "ONLINE", "role": "SECONDARY"}]},
                       {"members": [self.state["members"][0], {"id": "node-c", "state": "ONLINE", "role": "PRIMARY"}]}):
            with self.subTest(change=change):
                self.assertIsNone(agent.replication_policy({**self.state, **change}, self.cfg, None, 1500))

    def test_single_member_requires_correct_unexpired_view_bound_attestation(self):
        single = {**self.state, "members": [self.state["members"][0]]}
        self.assertIsNone(agent.replication_policy(single, self.cfg, None, 1500))
        self.assertEqual("single", agent.replication_policy(single, self.cfg, self.proof, 1500))
        for change in ({"viewId": "old"}, {"fencedId": "node-a"}, {"survivorId": "node-b"},
                       {"issuedAtMillis": 1600}, {"expiresAtMillis": 1500}, {"expiresAtMillis": 3_602_000},
                       {"kind": "timeout-only"}):
            with self.subTest(change=change):
                self.assertIsNone(agent.replication_policy(single, self.cfg, {**self.proof, **change}, 1500))

    def test_readonly_or_secondary_node_never_becomes_authority(self):
        single = {**self.state, "members": [{"id": "node-a", "state": "ONLINE", "role": "SECONDARY"}]}
        self.assertIsNone(agent.replication_policy(single, self.cfg, self.proof, 1500))
        self.assertIsNone(agent.replication_policy({**single, "readOnly": 1}, self.cfg, self.proof, 1500))

    def test_three_member_majority_and_minority_fencing(self):
        cfg = {**self.cfg, "memberUuids": ["node-a", "node-b", "node-c"]}
        three = {**self.state, "members": self.state["members"] + [{"id": "node-c", "state": "ONLINE", "role": "PRIMARY"}]}
        two = {**three, "members": three["members"][:2]}
        one = {**three, "members": three["members"][:1]}
        self.assertEqual("quorum", agent.replication_policy(three, cfg, None, 1500))
        self.assertEqual("quorum", agent.replication_policy(two, cfg, None, 1500))
        self.assertIsNone(agent.replication_policy(one, cfg, None, 1500))
        proof = {k:v for k,v in self.proof.items() if k != "fencedId"}
        self.assertIsNone(agent.replication_policy(one, cfg, {**proof, "fencedIds": ["node-b"]}, 1500))
        self.assertEqual("single", agent.replication_policy(one, cfg, {**proof, "fencedIds": ["node-b", "node-c"]}, 1500))
        self.assertIsNone(agent.replication_policy(one, cfg, {**proof, "fencedIds": ["node-b", "node-c", "node-c"]}, 1500))
        self.assertIsNone(agent.replication_policy({**two, "members": two["members"] + [{"id": "unknown", "state": "ONLINE", "role": "PRIMARY"}]}, cfg, None, 1500))
        self.assertIsNone(agent.replication_policy(two, {**cfg, "memberUuids": ["node-a", "node-b", "node-b"]}, None, 1500))

    def test_member_mapping_requires_unique_configured_physical_ids(self):
        observer = agent.Agent.__new__(agent.Agent)
        observer.config = {"nodeId": 1, "replication": {**self.cfg, "memberUuids": ["node-a", "node-b", "node-c"],
                           "memberNodeIds": {"node-a":1,"node-b":2,"node-c":3}}}
        self.assertEqual([1,2], observer.online_node_ids(self.state))
        digest=observer.membership_digest();self.assertEqual(64,len(digest))
        observer.config["replication"]["memberNodeIds"]["node-c"] = 4
        self.assertNotEqual(digest,observer.membership_digest())
        observer.config["replication"]["memberNodeIds"]["node-c"] = 2
        self.assertIsNone(observer.online_node_ids(self.state))
        observer.config["replication"]["memberNodeIds"]["node-c"] = True
        self.assertIsNone(observer.online_node_ids(self.state))

    def test_three_member_readiness_rejects_different_membership_on_a_peer(self):
        observer = agent.Agent.__new__(agent.Agent)
        cfg={**self.cfg,"memberUuids":["node-a","node-b","node-c"],"memberNodeIds":{"node-a":1,"node-b":2,"node-c":3}}
        observer.config={"readyMode":"replicated","nodeId":1,"replication":cfg,
                         "peerStatusUrls":[{"nodeId":2,"url":"peer-2"},{"nodeId":3,"url":"peer-3"}],
                         "recoveryChecks":["r1","r2","r3"],"capacityModelValidated":True}
        observer.token="test";observer.replication_view=lambda:("quorum",{"viewId":"view-3"},[1,2])
        digest=observer.membership_digest();now=1500
        base={"observedAtMillis":now,"mysqlReady":True,"tomcatReady":True,"reserveGuaranteed":True,"capacityModelValidated":True,
              "volumes":[{"availableBytes":4_000_000_000}],"membershipDigest":digest}
        observer.status=lambda:{**base,"nodeId":1,"memberUuid":"node-a","onlineNodeIds":[1,2],"viewId":"view-3"}
        peer={**base,"nodeId":2,"memberUuid":"node-b"}
        with patch.object(agent.time,"time",return_value=now/1000),patch.object(agent,"request",side_effect=lambda url,*args:peer if url=="peer-2" else None):
            self.assertTrue(observer.ready())
            peer["membershipDigest"]="old-membership"
            self.assertFalse(observer.ready())

    def test_recovery_authority_requires_matching_membership_before_tomcat_recovery(self):
        observer=agent.Agent.__new__(agent.Agent)
        cfg={**self.cfg,"memberUuids":["node-a","node-b","node-c"],"memberNodeIds":{"node-a":1,"node-b":2,"node-c":3}}
        observer.config={"nodeId":1,"replication":cfg,"fenceAttestationFile":"/missing",
                         "peerStatusUrls":[{"nodeId":2,"url":"peer-2"},{"nodeId":3,"url":"peer-3"}]}
        observer.token="test";observer.group_state=lambda:self.state
        peer={"nodeId":2,"memberUuid":"node-b","mysqlReady":True,"tomcatReady":False,
              "observedAtMillis":1500,"membershipDigest":observer.membership_digest()}
        with patch.object(agent.time,"time",return_value=1.5),patch.object(agent,"request",return_value=peer):
            self.assertTrue(observer.peer_membership_matches(self.state,[1,2]))
            peer["membershipDigest"]="old-membership"
            self.assertFalse(observer.peer_membership_matches(self.state,[1,2]))


if __name__ == "__main__": unittest.main()
