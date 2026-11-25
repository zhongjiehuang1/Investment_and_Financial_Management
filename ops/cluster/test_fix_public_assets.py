import os
from pathlib import Path
import stat
import tempfile
import unittest

import fix_public_assets as fix


class PublicAssetPermissionsTest(unittest.TestCase):
    def test_public_files_become_readable_without_exposing_private_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            outside = root / 'outside'
            outside.write_text('private')
            outside.chmod(0o600)
            for name in fix.APPS:
                app = root / name
                (app / 'assets').mkdir(parents=True)
                (app / 'WEB-INF').mkdir()
                (app / 'assets' / 'page.js').write_text('public')
                (app / 'WEB-INF' / 'web.xml').write_text('private')
                (app / 'config.properties').write_text('private')
                (app / '.hidden').write_text('private')
                (app / 'link').symlink_to(outside)
                for item in (app, app/'assets', app/'WEB-INF'):
                    item.chmod(0o750)
                for item in (app/'assets'/'page.js', app/'WEB-INF'/'web.xml', app/'config.properties', app/'.hidden'):
                    item.chmod(0o640)
            fix.expose(root)
            for name in fix.APPS:
                app=root/name
                self.assertEqual(0o755, stat.S_IMODE(app.stat().st_mode))
                self.assertEqual(0o644, stat.S_IMODE((app/'assets'/'page.js').stat().st_mode))
                self.assertEqual(0o750, stat.S_IMODE((app/'WEB-INF').stat().st_mode))
                self.assertEqual(0o640, stat.S_IMODE((app/'WEB-INF'/'web.xml').stat().st_mode))
                self.assertEqual(0o640, stat.S_IMODE((app/'config.properties').stat().st_mode))
                self.assertEqual(0o640, stat.S_IMODE((app/'.hidden').stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE(outside.stat().st_mode))

    def test_missing_or_symlinked_context_is_rejected_before_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            for name in fix.APPS[:2]:
                (root/name).mkdir(mode=0o750)
            with self.assertRaises(ValueError):
                fix.expose(root)
            self.assertEqual(0o750, stat.S_IMODE((root/fix.APPS[0]).stat().st_mode))
            (root/fix.APPS[2]).symlink_to(root/fix.APPS[0], target_is_directory=True)
            with self.assertRaises(ValueError):
                fix.expose(root)


if __name__ == '__main__':
    unittest.main()

