"""Actual filesystem identity controls; synthetic manifests, no native execution."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
import checks
import verify


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class PortabilityTest(unittest.TestCase):
    def setUp(self):
        self.addCleanup(setattr, sys, 'path', list(sys.path))
        modules = patch.dict(sys.modules)
        modules.start()
        self.addCleanup(modules.stop)
        for name in ('toolchain', 'rig_settings'):
            sys.modules.pop(name, None)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / 'owned run'
        self.repo = self.base / 'relocated repository'
        self.active = self.base / 'portable tools'
        (self.root / 'settings-tools').mkdir(parents=True)
        (self.repo / 'tools/rig').mkdir(parents=True)
        self.active.mkdir()
        staged = []
        for name in ('drive.py', 'checks.py', 'verify.py', 'finalize.py'):
            for target in (self.root / 'settings-tools' / name, self.active / name):
                shutil.copy2(Path(__file__).parent / name, target)
            staged.append({'target': 'settings-tools/' + name, 'sha256': sha(self.active / name)})
        tools = {}
        for name in ('rig.py', 'native_window.py', 'private_input.py', 'ui_snapshot_wait.py', 'rig_settings.py'):
            target = self.repo / 'tools/rig' / name
            target.write_text('# synthetic dependency ' + name + '\n')
            tools['tools/rig/' + name] = sha(target)
        target = self.repo / 'tools/rig/toolchain.py'
        shutil.copy2(Path(__file__).resolve().parents[2] / 'tools/rig/toolchain.py', target)
        tools['tools/rig/toolchain.py'] = sha(target)
        helper=self.repo/'tools/settings/settings_file.py';helper.parent.mkdir();helper.write_text('# synthetic shared settings helper\n');tools['tools/settings/settings_file.py']=sha(helper)
        runtime = {'stage_files': staged}
        bound = {'runtime_hash': digest(runtime), 'staged_inputs': staged, 'runtime_tools': tools}
        self.manifest = {'run_manifest': bound, 'run_hash': digest(bound), 'runtime_hash': digest(runtime)}
        (self.root / 'runtime.json').write_text(json.dumps(runtime))
        (self.root / 'manifest.json').write_text(json.dumps(self.manifest))
        self.files = {'entrypoint': self.active / 'drive.py'}

    def test_relocated_repository_and_portable_entrypoints_pass(self):
        self.assertEqual(self.repo.resolve(), checks.verify_tool_identity(self.root, self.repo, self.files))

    def test_foreign_cached_codec_helpers_are_rejected(self):
        for name in ('toolchain', 'rig_settings'):
            with self.subTest(name=name), patch.dict(sys.modules):
                sys.modules[name] = types.SimpleNamespace(__file__=self.active / (name + '.py'))
                with self.assertRaisesRegex(ValueError, 'active rig import differs: ' + name):
                    checks.verify_tool_identity(self.root, self.repo, self.files)

    def test_unbound_toolchain_is_rejected_before_import(self):
        del self.manifest['run_manifest']['runtime_tools']['tools/rig/toolchain.py']
        self.manifest['run_hash'] = digest(self.manifest['run_manifest'])
        (self.root / 'manifest.json').write_text(json.dumps(self.manifest))
        with self.assertRaisesRegex(ValueError, 'required owned rig dependency unbound: toolchain.py'):
            checks.verify_tool_identity(self.root, self.repo, self.files)
        self.assertNotIn('toolchain', sys.modules)

    def test_yaml_codec_binding_survives_portable_bootstrap(self):
        helper = self.repo / 'tools/rig/rig_settings.py'
        helper.write_text('def codec_identity(): return {"format": 1, "entries": ["synthetic codec"]}\n')
        bound = self.manifest['run_manifest']
        bound['runtime_tools']['tools/rig/rig_settings.py'] = sha(helper)
        runtime = json.loads((self.root / 'runtime.json').read_text())
        runtime['generated_files'] = {'config/lss-client-config.yaml': 'synthetic'}
        bound['settings_codec'] = {'format': 1, 'entries': ['synthetic codec']}
        bound['runtime_hash'] = self.manifest['runtime_hash'] = digest(runtime)
        self.manifest['run_hash'] = digest(bound)
        (self.root / 'runtime.json').write_text(json.dumps(runtime))
        (self.root / 'manifest.json').write_text(json.dumps(self.manifest))
        self.assertEqual(self.repo.resolve(), checks.verify_tool_identity(self.root, self.repo, self.files))
        bound['settings_codec']['entries'] = ['different codec']
        self.manifest['run_hash'] = digest(bound)
        (self.root / 'manifest.json').write_text(json.dumps(self.manifest))
        with self.assertRaisesRegex(ValueError, 'prepared settings codec bytes changed'):
            checks.verify_tool_identity(self.root, self.repo, self.files)

    def test_changed_active_helpers_rejected_before_acceptance(self):
        for name in ('checks.py', 'verify.py', 'drive.py', 'finalize.py'):
            with self.subTest(name=name):
                p = self.active / name
                original = p.read_bytes()
                p.write_bytes(original + b'\n# changed active dependency\n')
                with self.assertRaisesRegex(ValueError, 'active settings tool differs'):
                    checks.verify_tool_identity(self.root, self.repo, self.files)
                p.write_bytes(original)

    def test_actual_verifier_rejects_changed_import_before_receipt_read(self):
        changed = self.active / 'checks.py'
        changed.write_text('# changed imported helper\n')
        original = checks.__file__
        try:
            checks.__file__ = str(changed)
            with self.assertRaisesRegex(ValueError, 'active settings tool differs: checks.py'):
                verify.verify(self.root, self.repo)
        finally:
            checks.__file__ = original
        self.assertFalse((self.root / 'evidence/standalone-settings/receipt.json').exists())

    def test_staged_tool_and_selected_repository_changes_rejected(self):
        for path, error in ((self.root/'settings-tools/verify.py', 'staged settings tool changed'),
                            (self.repo/'tools/rig/private_input.py', 'selected repository tool differs'),
                            (self.repo/'tools/rig/toolchain.py', 'selected repository tool differs')):
            with self.subTest(path=path):
                raw = path.read_bytes();path.write_bytes(raw+b'\n# changed\n')
                with self.assertRaisesRegex(ValueError, error):
                    checks.verify_tool_identity(self.root, self.repo, self.files)
                path.write_bytes(raw)

    def test_runtime_and_manifest_rebinding_rejected(self):
        (self.root/'runtime.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'run input identity changed'):
            checks.verify_tool_identity(self.root, self.repo, self.files)

    def test_active_import_location_must_use_selected_repo(self):
        checks.verify_active_rig(self.repo, {'rig': types.SimpleNamespace(__file__=self.repo/'tools/rig/rig.py')})
        with self.assertRaisesRegex(ValueError, 'active rig import differs'):
            checks.verify_active_rig(self.repo, {'rig': types.SimpleNamespace(__file__=self.active/'rig.py')})


if __name__ == '__main__':
    unittest.main()
