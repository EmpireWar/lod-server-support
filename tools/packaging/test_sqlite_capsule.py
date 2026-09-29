import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from tools.packaging import sqlite_capsule as gate
from tools.tests import check_artifacts


def archive(entries):
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w') as jar:
        for name, value in entries.items():
            jar.writestr(name, value)
    return data.getvalue()


class CapsuleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.entries = gate.fixture_entries()
        self.pin = patch.object(gate, 'PINS', dict(gate.PINS,
                         capsuleSha256=gate.digest(self.entries[gate.CAPSULE])))
        self.pin.start()
        self.addCleanup(self.pin.stop)

    def check(self, entries, nested=False):
        path = self.root / 'candidate.jar'
        data = archive(entries)
        path.write_bytes(archive({'META-INF/jars/common.jar': data}) if nested else data)
        problems = []
        gate.check(path, problems)
        return problems

    def test_valid_flat_and_nested_capsules(self):
        self.assertEqual([], self.check(self.entries))
        self.assertEqual([], self.check(self.entries, nested=True))

    def test_coherently_modified_descriptor_and_capsule_still_fail_the_pin(self):
        changed = gate.fixture_entries(self.entries[gate.CAPSULE] + b'changed')
        self.assertTrue(any('independent checked-in digest' in p for p in self.check(changed)))

    def test_missing_native_license_class_and_module_are_rejected(self):
        for missing in (gate.NATIVES[0], gate.LICENSE, 'org/sqlite/JDBC.class',
                        'META-INF/versions/9/module-info.class'):
            with self.subTest(missing=missing):
                with zipfile.ZipFile(io.BytesIO(self.entries[gate.CAPSULE])) as jar:
                    damaged = archive({n: jar.read(n) for n in jar.namelist() if n != missing})
                self.assertTrue(any('missing ' + missing in p for p in self.check(gate.fixture_entries(damaged))))

    def test_flat_classes_providers_metadata_and_mod_dependencies_are_rejected(self):
        for name, data in {
            'org/sqlite/JDBC.class': b'x',
            'META-INF/versions/9/org/sqlite/JDBC.class': b'x',
            'META-INF/versions/9/module-info.class': b'org.xerial.sqlitejdbc org/sqlite',
            'META-INF/MANIFEST.MF': b'Manifest-Version: 1.0\r\nAutomatic-Module-Name: org.xerial.sql\r\n itejdbc\r\n',
            'dev/vox/AccidentalHostConsumer.class': b'org/sqlite/SQLiteDataSource',
            'dev/vox/lss/shaded/org/sqlite/JDBC.class': b'x',
            'dev/vox/lss/jdbc/PrivateSqliteBridge.class': b'x',
            'META-INF/services/java.sql.Driver': b'org.sqlite.JDBC',
            'fabric.mod.json': b'{"depends":{"sqlite_jdbc":"*"}}',
            'META-INF/jarjar/metadata.json': b'{"group":"org.xerial"}',
            'META-INF/neoforge.mods.toml': b'modId="sqlite_jdbc"',
            'META-INF/jarjar/sqlite-jdbc.jar': archive({'x': b'x'}),
        }.items():
            with self.subTest(name=name):
                self.assertTrue(self.check(dict(self.entries, **{name: data})))

    def test_duplicate_or_incomplete_runtimes_are_rejected(self):
        for missing in (gate.CAPSULE, gate.BRIDGE, gate.DESCRIPTOR):
            self.assertTrue(self.check({n: b for n, b in self.entries.items() if n != missing}))
        entries = dict(self.entries, **{'META-INF/jars/duplicate.jar': archive(self.entries)})
        self.assertTrue(any('found 2' in p for p in self.check(entries)))

    def test_duplicate_zip_entry_is_rejected(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as jar:
            for name, value in self.entries.items():
                jar.writestr(name, value)
            with self.assertWarns(UserWarning):
                jar.writestr(gate.CAPSULE, self.entries[gate.CAPSULE])
        path = self.root / 'duplicate.jar'
        path.write_bytes(data.getvalue())
        problems = []
        gate.check(path, problems)
        self.assertTrue(any('duplicate ZIP' in p for p in problems))

    def test_brand_pair_checks_resources_as_well_as_classes(self):
        lss, vss = self.root / 'lss.jar', self.root / 'vss.jar'
        lss.write_bytes(archive(self.entries))
        for changed in (gate.CAPSULE, gate.BRIDGE, gate.DESCRIPTOR):
            vss.write_bytes(archive(dict(self.entries, **{changed: self.entries[changed] + b'x'
                                      if isinstance(self.entries[changed], bytes) else self.entries[changed] + 'x'})))
            problems = []
            gate.check_pair(lss, vss, problems)
            self.assertTrue(problems, changed)

    def test_fixture_walker_enters_the_opaque_capsule(self):
        name = 'dev/vox/UnwantedFixture.class'
        payload = archive({gate.CAPSULE: archive({name: b'x'})})
        self.assertEqual([gate.CAPSULE + '!/' + name],
                         check_artifacts.violations(payload, {'dev/vox/UnwantedFixture'}))

    def test_fixture_walker_reads_the_opaque_bridge_class_name(self):
        payload = archive({gate.BRIDGE: gate.fixture_class('dev/vox/UnwantedFixture$Nested')})
        self.assertEqual([gate.BRIDGE], check_artifacts.violations(payload, {'dev/vox/UnwantedFixture'}))


if __name__ == '__main__':
    unittest.main()
