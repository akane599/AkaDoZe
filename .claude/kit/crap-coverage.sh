#!/usr/bin/env bash
# One native AGP capture; never publish partial or stale coverage.
set -euo pipefail
fail() { printf 'coverage UNVERIFIED: %s\n' "$*" >&2; exit 2; }
command -v lizard >/dev/null || fail 'lizard missing (no installer fallback)'
[[ $(lizard --version) == 1.24.0 ]] || fail 'lizard must be 1.24.0'
command -v python3 >/dev/null || fail 'python3 missing'
command -v timeout >/dev/null || fail 'timeout missing'
command -v java >/dev/null || fail 'java missing'
[[ -n ${QUARTERMASTER_COVERAGE_DIR:-} ]] || fail 'QUARTERMASTER_COVERAGE_DIR missing'
[[ -d $QUARTERMASTER_COVERAGE_DIR && ! -L $QUARTERMASTER_COVERAGE_DIR ]] || fail 'coverage directory must exist and not be a symlink'
[[ -z $(find "$QUARTERMASTER_COVERAGE_DIR" -mindepth 1 -print -quit) ]] || fail 'coverage directory must start empty'
[[ -f app/build.gradle && -f .claude/kit/gradle-check.sh ]] || fail 'run from checkout root'
export QUARTERMASTER_COVERAGE_DIR
export CRAP_CAPTURE_STARTED_MS=$(python3 -c 'import time; print(time.time_ns() // 1000000)')
converter=.claude/kit/jacoco-to-lcov.py
python3 "$converter" --snapshot "$QUARTERMASTER_COVERAGE_DIR/inputs.json"
# Export the native report task's actual inputs, not guessed Kotlin class directories.
cat >"$QUARTERMASTER_COVERAGE_DIR/native.gradle" <<'GRADLE'
import groovy.json.JsonOutput
import java.security.MessageDigest
gradle.projectsEvaluated {
    def report = gradle.rootProject.project(':app').tasks.named('createDebugUnitTestCoverageReport')
    report.configure {
        doFirst {
            def classes = classFileCollection.files.collect { it.absolutePath }.sort()
            def cp = jacocoClasspath.files.collect { it.absolutePath }.sort()
            def exec = jacocoHostTestCoverageFile.get().asFile.absolutePath
            def inputs = []
            (classes + cp + [exec]).each { name ->
                def file = new File(name)
                if (file.isDirectory()) file.eachFileRecurse { child -> if (child.isFile()) inputs.add(child) }
                else inputs.add(file)
            }
            def hashes = inputs.collectEntries { file ->
                [(file.absolutePath): MessageDigest.getInstance('SHA-256').digest(file.bytes).encodeHex().toString()]
            }
            new File(System.getenv('QUARTERMASTER_COVERAGE_DIR'), 'native.json').text = JsonOutput.toJson(
                [classes: classes, classpath: cp, exec: exec, hashes: hashes,
                 started: System.getenv('CRAP_CAPTURE_STARTED_MS'),
                 xml: new File(outputReportDir.get().asFile, 'report.xml').absolutePath])
        }
    }
}
GRADLE
xml=app/build/reports/coverage/test/debug/report.xml
rm -f "$xml"
start=$SECONDS
# Keep rerun/no-cache/no-daemon: fresh tests, no cached execution, bounded descendants.
# Native pilot: 33s (full suite, two workers); 120s allows over 3x margin.
set +e
timeout --kill-after=30s 120s bash .claude/kit/gradle-check.sh \
    -PcrapCoverage :app:createDebugUnitTestCoverageReport --max-workers=2 \
    --no-daemon --no-build-cache --rerun-tasks \
    --init-script "$QUARTERMASTER_COVERAGE_DIR/native.gradle"
rc=$?
set -e
printf 'coverage Gradle exit=%s duration=%ss\n' "$rc" "$((SECONDS - start))" >&2
[[ $rc -eq 0 ]] || exit "$rc"
[[ -s $xml && -s $QUARTERMASTER_COVERAGE_DIR/native.json ]] || fail 'native report/identity manifest missing'
python3 "$converter" --check-snapshot "$QUARTERMASTER_COVERAGE_DIR/inputs.json"
python3 "$converter" --native "$QUARTERMASTER_COVERAGE_DIR/native.json" \
    --xml "$xml" --source-root app/src/main/java --output "$QUARTERMASTER_COVERAGE_DIR/lcov.pending"
python3 "$converter" --check-snapshot "$QUARTERMASTER_COVERAGE_DIR/inputs.json"
mv "$QUARTERMASTER_COVERAGE_DIR/lcov.pending" "$QUARTERMASTER_COVERAGE_DIR/lcov.info"
