import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name('jacoco-to-lcov.py')
SPEC = importlib.util.spec_from_file_location('converter', SCRIPT)
converter = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(converter)


class JacocoToLcovTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.root = self.directory / 'app/src/main/java'
        (self.root / 'example').mkdir(parents=True)
        (self.root / 'example/Java.java').write_text('a\nb\nc\n')
        (self.root / 'example/Kotlin.kt').write_text('a\nb\nc\n')
        self.xml = self.directory / 'report.xml'
        self.output = self.directory / 'lcov.info'

    def report(self, lines, name='Java.java', extra=''):
        self.xml.write_text(f'<report><package name="example"><sourcefile name="{name}">'
                            f'{lines}</sourcefile>{extra}</package></report>')

    def invoke(self):
        return subprocess.run([sys.executable, str(SCRIPT), '--xml', str(self.xml),
                               '--source-root', str(self.root), '--output', str(self.output)],
                              text=True, capture_output=True)

    def rejected(self):
        self.output.write_text('previous valid output')
        result = self.invoke()
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertEqual('previous valid output', self.output.read_text())

    def test_java_and_kotlin_boolean_hits_and_totals(self):
        for name in ('Java.java', 'Kotlin.kt'):
            with self.subTest(name=name):
                self.report('<line nr="3" mi="9" ci="2"/>'
                            '<line nr="1" mi="4" ci="0"/>'
                            '<line nr="2" mi="0" ci="0"/>', name)
                result = self.invoke()
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(f'SF:app/src/main/java/example/{name}\n'
                                 'DA:1,0\nDA:3,1\nLF:2\nLH:1\nend_of_record\n',
                                 self.output.read_text())

    def test_absent_source_exit_two(self):
        self.report('<line nr="1" mi="0" ci="1"/>', 'Missing.kt')
        self.rejected()

    def test_ambiguous_source_exit_two(self):
        self.report('<line nr="1" mi="1" ci="0"/>',
                    extra='<sourcefile name="Java.java"><line nr="1" mi="1" ci="0"/></sourcefile>')
        self.rejected()

    def test_invalid_lines_exit_two(self):
        for number in ('0', '-1', '4', 'oops', '١'):
            with self.subTest(number=number):
                self.report(f'<line nr="{number}" mi="0" ci="1"/>')
                self.rejected()

    def test_invalid_instruction_counts_exit_two(self):
        for attributes in ('mi="-1" ci="1"', 'mi="1"', 'mi="1" ci="NaN"'):
            with self.subTest(attributes=attributes):
                self.report(f'<line nr="1" {attributes}/>')
                self.rejected()

    def test_duplicate_line_exit_two(self):
        self.report('<line nr="1" mi="1" ci="0"/><line nr="1" mi="0" ci="1"/>')
        self.rejected()

    def test_zero_line_missing_source_skipped_with_visible_count(self):
        self.report('<line nr="1" mi="0" ci="1"/>',
                    extra='<sourcefile name="Missing.kt"/>')
        result = self.invoke()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('coverage skip zero-line source: example/Missing.kt', result.stderr)
        self.assertIn('coverage zero-line source skips=1', result.stderr)
        self.assertNotIn('Missing.kt', self.output.read_text())

    def test_missing_input_exit_two(self):
        self.rejected()

    def test_malformed_xml_exit_two(self):
        self.xml.write_text('<report>')
        self.rejected()

    def test_empty_report_exit_two(self):
        self.xml.write_text('<report/>')
        self.rejected()

    def test_path_escape_exit_two(self):
        self.report('', '../Java.java')
        self.rejected()

    def test_source_symlink_escape_exit_two(self):
        outside = self.directory / 'outside.kt'
        outside.write_text('a\n')
        (self.root / 'example/Escape.kt').symlink_to(outside)
        self.report('', 'Escape.kt')
        self.rejected()

    def test_snapshot_changed_source_fails_closed(self):
        saved = self.directory / 'snapshot.json'
        saved.write_text(json.dumps({'files': {'A.kt': 'old'}}))
        with patch.object(converter, 'snapshot', return_value={'files': {'A.kt': 'new'}}):
            with self.assertRaisesRegex(converter.CoverageError, 'identities changed'):
                converter.run(type('Args', (), {'snapshot': None, 'check_snapshot': saved})())

    def test_native_input_hash_mismatch_fails_before_java(self):
        native = self.directory / 'native.json'
        source = self.directory / 'A.class'
        source.write_bytes(b'changed bytes')
        native.write_text(json.dumps({'hashes': {str(source): 'previous hash'}}))
        with patch.object(converter.subprocess, 'run') as java:
            with self.assertRaisesRegex(converter.CoverageError, 'native input changed'):
                converter.verify_native(native)
            java.assert_not_called()


if __name__ == '__main__':
    unittest.main()
