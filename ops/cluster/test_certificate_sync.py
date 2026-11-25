import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("certificate_sync", Path(__file__).with_name("certificate_sync.py"))
certificates = importlib.util.module_from_spec(spec)
spec.loader.exec_module(certificates)


class CertificateEnvelopeTest(unittest.TestCase):
    def bundle(self):
        return {domain: {"fullchain": "public-certificate", "privkey": "private-key"} for domain in certificates.DOMAINS}

    def test_transport_rejects_path_and_domain_injection(self):
        bundle = self.bundle()
        bundle["../../etc/ssh/authorized_keys"] = bundle.pop(certificates.DOMAINS[0])
        with self.assertRaises(ValueError):
            certificates.validated_payload(json.dumps(bundle).encode())

    def test_transport_rejects_unexpected_fields_and_oversized_input(self):
        bundle = self.bundle()
        bundle[certificates.DOMAINS[0]]["destination"] = "/etc/ssh/authorized_keys"
        with self.assertRaises(ValueError):
            certificates.validated_payload(json.dumps(bundle).encode())
        with self.assertRaises(ValueError):
            certificates.validated_payload(b" " * (certificates.LIMIT + 1))

    def test_only_three_fixed_domains_reach_certificate_validation(self):
        bundle = self.bundle()
        self.assertEqual(bundle, certificates.validated_payload(json.dumps(bundle).encode()))


if __name__ == "__main__":
    unittest.main()
