import os
from unittest.mock import patch
import tempfile
import unittest
from pathlib import Path
from toolchain import snapshot,verify,retain

class ToolchainTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        for name in ('tools/rig/rig','tools/rig/rig.py','tools/rig/private_input.py','tools/compat/catalog.py','scripts/lib/harness-lock.sh','scripts/lib/owned-process.py'):
            path=self.root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(name)
        self.identity=snapshot(self.root)
    def test_helper_and_supervisor_bytes_are_bound(self):
        verify(self.root,self.identity)
        (self.root/'tools/rig/private_input.py').write_text('changed')
        with self.assertRaisesRegex(ValueError,'sources changed'):verify(self.root,self.identity)
    def test_new_runtime_helper_is_not_implicitly_accepted(self):
        (self.root/'tools/rig/new.py').write_text('new')
        with self.assertRaises(ValueError):verify(self.root,self.identity)
    def test_test_changes_do_not_change_runtime_identity(self):
        (self.root/'tools/rig/test_added.py').write_text('test')
        verify(self.root,self.identity)
    def test_retained_sources_remain_inspectable_after_worktree_edit(self):
        destination=self.root/'retained';retain(self.root,destination,self.identity)
        (self.root/'tools/rig/rig.py').write_text('later change')
        verify(destination,self.identity)
        (destination/'tools/rig/rig.py').write_text('tampered evidence')
        with self.assertRaises(ValueError):verify(destination,self.identity)


class SettingsIdentityTests(unittest.TestCase):
    def test_actual_manifest_snapshot_retains_settings_launcher_and_product_sources(self):
        repo=Path(__file__).resolve().parents[2]
        frozen=snapshot(repo)
        for name in ('tools/settings/settings_file.py', 'tools/rig/rig_settings.py',
                     'common/src/main/java/dev/vox/lss/common/config/SettingsCli.java',
                     'common/src/main/resources/dev/vox/lss/settings/client.yaml',
                     'common/build.gradle', 'tools/verify/run-gradle.sh'):
            self.assertIn(name, frozen)
        with tempfile.TemporaryDirectory() as temp:
            retained=Path(temp)/'tool-sources'
            retain(repo, retained, frozen)
            verify(retained, frozen)
            (retained/'common/src/main/resources/dev/vox/lss/settings/client.yaml').write_text('changed codec schema')
            with self.assertRaisesRegex(ValueError,'sources changed'): verify(retained, frozen)

    def test_prepared_codec_bytes_are_bound_even_when_classpath_location_is_unchanged(self):
        from toolchain import settings_codec, verify_settings_codec
        runtime={'generated_files':{'config/lss-client-config.yaml':'config_version: 1'}}
        with tempfile.TemporaryDirectory() as temp:
            classes=Path(temp)/'classes';classes.mkdir()
            member=classes/'SettingsCli.class';member.write_bytes(b'first codec')
            with patch.dict(os.environ, {'LSS_SETTINGS_CLASSPATH':str(classes)}):
                frozen=settings_codec(runtime)
                verify_settings_codec(runtime, frozen)
                member.write_bytes(b'changed codec')
                with self.assertRaisesRegex(ValueError,'codec bytes changed'):
                    verify_settings_codec(runtime, frozen)
