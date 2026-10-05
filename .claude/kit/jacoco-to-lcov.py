#!/usr/bin/env python3
"""Convert native JaCoCo line instructions to boolean LCOV hits, fail closed."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


class CoverageError(ValueError):
    pass


def natural(value, positive=False):
    if not re.fullmatch('[0-9]+', value or ''):
        raise CoverageError(f"invalid integer: {value!r}")
    number = int(value)
    if positive and number == 0:
        raise CoverageError("line numbers must be positive")
    return number


def relative_source(package, name):
    if not re.fullmatch(r'[^/\\]+', name):
        raise CoverageError(f"invalid source name: {name}")
    relative = PurePosixPath(package) / name
    if relative.is_absolute() or '..' in relative.parts or '\\' in package:
        raise CoverageError(f"invalid source path: {relative}")
    return relative


def source_path(root, package, name):
    source = root / relative_source(package, name)
    if not source.is_file() or not source.resolve().is_relative_to(root.resolve()):
        raise CoverageError(f"absent source: {source}")
    return source


def source_lines(element, length):
    hits = {}
    for line in element.findall('line'):
        number = natural(line.get('nr'), positive=True)
        if number > length or number in hits:
            raise CoverageError(f"invalid or duplicate line: {number}")
        missed, covered = natural(line.get('mi')), natural(line.get('ci'))
        if missed + covered:
            hits[number] = int(covered > 0)
    return hits


def source_record(root, package, element, seen):
    source = source_path(root, package, element.get('name', ''))
    canonical = source.resolve()
    if canonical in seen:
        raise CoverageError(f"ambiguous source: {source}")
    seen.add(canonical)
    hits = source_lines(element, len(source.read_text().splitlines()))
    relative = source.relative_to(root).as_posix()
    return (f"SF:app/src/main/java/{relative}\n" +
            ''.join(f"DA:{n},{hit}\n" for n, hit in sorted(hits.items())) +
            f"LF:{len(hits)}\nLH:{sum(hits.values())}\nend_of_record\n")


def report_sources(report):
    skipped = 0
    for package in report.findall('package'):
        for element in package.findall('sourcefile'):
            if not element.findall('line'):
                print(f"coverage skip zero-line source: {package.get('name', '')}/{element.get('name', '')}", file=sys.stderr)
                skipped += 1
                continue
            yield package.get('name', ''), element
    print(f'coverage zero-line source skips={skipped}', file=sys.stderr)


def convert(xml, root):
    report = ET.parse(xml).getroot()
    if report.tag != 'report':
        raise CoverageError('not a JaCoCo report')
    seen = set()
    records = [source_record(root, package, element, seen)
               for package, element in report_sources(report)]
    if not records:
        raise CoverageError('report contains no source files')
    return ''.join(records)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def snapshot():
    # No credentials/local.properties are read. These are compilation/capture inputs.
    files = list(Path('app/src').rglob('*')) + list(Path('.claude/kit').glob('*'))
    files += list(Path('gradle').rglob('*'))
    files += [Path(p) for p in ('app/build.gradle', 'build.gradle', 'settings.gradle',
                               'gradle.properties', 'gradlew', '.claude/quartermaster/crap.json')]
    return {'files': {str(p): digest(p) for p in files if p.is_file()},
            'refs': command('git', 'rev-parse', 'HEAD', 'Base'),
            'mergeBase': command('git', 'merge-base', 'HEAD', 'Base'),
            'lizard': command('lizard', '--version'),
            'python': sys.version, 'java': subprocess.check_output(
                ['java', '-version'], stderr=subprocess.STDOUT, text=True)}


# The native report's own class inputs and resolved core are the authority. No class globs.
IDENTITY_CHECK = r'''
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.jacoco.core.analysis.*;
import org.jacoco.core.tools.ExecFileLoader;
class VerifyJacoco {
    public static void main(String[] args) throws Exception {
        ExecFileLoader loader = new ExecFileLoader();
        loader.load(new File(args[0]));
        if (loader.getSessionInfoStore().getInfos().isEmpty())
            throw new IOException("missing fresh test session");
        long started = Long.parseLong(args[2]);
        for (var session : loader.getSessionInfoStore().getInfos())
            if (session.getStartTimeStamp() < started)
                throw new IOException("stale execution session");
        CoverageBuilder builder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
        for (String path : Files.readAllLines(Path.of(args[1])))
            analyzer.analyzeAll(new File(path));
        if (!builder.getNoMatchClasses().isEmpty())
            throw new IOException("execution/class ID mismatch: " + builder.getNoMatchClasses());
        long matched = builder.getClasses().stream()
            .filter(c -> loader.getExecutionDataStore().get(c.getId()) != null).count();
        if (matched == 0) throw new IOException("no executed production classes");
        System.out.println("JaCoCo matched executed production classes=" + matched);
    }
}
'''


def verify_native(path):
    native = json.loads(path.read_text())
    for file, expected in native['hashes'].items():
        if digest(file) != expected:
            raise CoverageError(f"native input changed: {file}")
    directory = path.parent
    checker = directory / 'VerifyJacoco.java'
    checker.write_text(IDENTITY_CHECK)
    classes = directory / 'classes.txt'
    classes.write_text(''.join(f"{file}\n" for file in native['classes']))
    subprocess.run(['java', '--class-path', os.pathsep.join(native['classpath']),
                    str(checker), native['exec'], str(classes), native['started']], check=True)


def publish(output, text):
    with tempfile.NamedTemporaryFile(mode='w', dir=output.parent, delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(text)
    try:
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)


def snapshot_action(args):
    if args.snapshot:
        args.snapshot.write_text(json.dumps(snapshot(), sort_keys=True))
        return True
    if args.check_snapshot:
        if json.loads(args.check_snapshot.read_text()) != snapshot():
            raise CoverageError('source/build/tool/base identities changed during capture')
        return True
    return False


def run(args):
    if snapshot_action(args):
        return
    if args.native:
        verify_native(args.native)
    if not all((args.xml, args.source_root, args.output)):
        raise CoverageError('--xml, --source-root and --output are required')
    publish(args.output, convert(args.xml, args.source_root))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('xml', 'source-root', 'output', 'snapshot', 'check-snapshot', 'native'):
        parser.add_argument('--' + name, type=Path)
    try:
        run(parser.parse_args())
    except (OSError, ValueError, ET.ParseError, subprocess.CalledProcessError, KeyError) as error:
        print(f'coverage UNVERIFIED: {error}', file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
