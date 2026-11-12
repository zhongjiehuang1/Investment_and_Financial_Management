import unittest
from stage_nginx import app_config


class CoordinatorRoutingTest(unittest.TestCase):
    def test_either_node_can_serve_the_local_coordinator(self):
        for node in (1,2):
            for coordinator in (1,2):
                with self.subTest(node=node,coordinator=coordinator):
                    text=app_config('starhub','starhub',node,'private-test-token','http://127.0.0.1',coordinator)
                    expected=8080 if node==coordinator else 18081
                    self.assertIn(f'proxy_pass http://127.0.0.1:{expected}/starhub/realtime;',text)
                    self.assertIn(f'proxy_pass http://127.0.0.1:{expected};',text)
                    self.assertIn(f'{{"serverId":{node}}}',text)
                    self.assertIn('proxy_read_timeout 360s;',text)
                    self.assertIn('proxy_read_timeout 1800s;',text)

    def test_rejects_an_unknown_coordinator(self):
        with self.assertRaises(ValueError):app_config('iafm','finance',1,'token',None,0)

    def test_third_physical_node_has_separate_logical_shard_and_tunnel_port(self):
        text=app_config('iafm','finance',3,'token','http://127.0.0.1',2,1,18082)
        self.assertIn('{"serverId":3}',text)
        self.assertIn('X-IAFM-Ingress-Node "1"',text)
        self.assertIn('proxy_pass http://127.0.0.1:18082;',text)
        with self.assertRaises(ValueError):app_config('iafm','finance',3,'token','http://127.0.0.1',2)
        self.assertIn('{"serverId":15}',app_config('iafm','finance',15,'token','http://127.0.0.1',2,1))


if __name__ == '__main__': unittest.main()
