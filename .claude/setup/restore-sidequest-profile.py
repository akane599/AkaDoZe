#!/usr/bin/env python3
"""Recreate the android-kit Sidequest routing profile from sidequest-android-kit.json.

Usage: python3 .claude/setup/restore-sidequest-profile.py [--profile ID] [--project PATH]

Starts from Sidequest's built-in "coding" profile, then adds/edits every category so its name,
description, contract, route, fallback, read-only flag and enabled state match the snapshot.
With --project it also points that project's board at the profile. Re-running is safe.
"""
import argparse
import glob
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def sidequest_cli():
    found = sorted(glob.glob(os.path.expanduser(
        "~/.claude/plugins/cache/eigenwise-toolshed/sidequest/*/bin/sidequest.js")))
    if not found:
        sys.exit("Sidequest plugin not found; install sidequest@eigenwise-toolshed first.")
    return found[-1]


def run(cli, *args, check=True):
    result = subprocess.run(["node", cli, *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        sys.exit(f"sidequest {' '.join(args[:3])} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result


def category_args(c):
    args = ["--name", c["name"], "--description", c["description"], "--contract", c["contract"],
            "--route-model", c["route"]["model"], "--route-effort", c["route"]["effort"],
            "--readonly", "true" if c["readonly"] else "false"]
    if c["fallback"]:
        args += ["--fallback-model", c["fallback"]["model"], "--fallback-effort", c["fallback"]["effort"]]
    else:
        args += ["--no-fallback"]
    return args


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", help="profile id to create (default: the snapshot's id)")
    parser.add_argument("--project", help="also switch this project's board to the profile")
    opts = parser.parse_args()

    snapshot = json.load(open(os.path.join(HERE, "sidequest-android-kit.json"), encoding="utf-8"))
    snapshot = snapshot.get("profile", snapshot)  # raw `profile get --json` output works too
    profile = opts.profile or snapshot["id"]
    cli = sidequest_cli()

    existing = json.loads(run(cli, "profile", "list", "--json").stdout)
    ids = {p["id"] for p in (existing.get("profiles", existing) if isinstance(existing, dict) else existing)}
    if profile not in ids:
        name = snapshot["name"] if profile == snapshot["id"] else profile  # names must be unique
        run(cli, "profile", "create", profile, "--from", "coding", "--name", name)
    run(cli, "profile", "edit", profile, "--description", snapshot["description"], check=False)

    current = json.loads(run(cli, "profile", "get", profile, "--json").stdout)["profile"]["categories"]
    have = {c["id"] for c in current}
    for c in snapshot["categories"]:
        verb = "edit" if c["id"] in have else "add"
        run(cli, "category", verb, c["id"], "--profile", profile, *category_args(c))
        if not c["enabled"]:
            run(cli, "category", "disable", c["id"], "--profile", profile)
    wanted = {c["id"] for c in snapshot["categories"]}
    for extra in sorted(have - wanted):
        run(cli, "category", "disable", extra, "--profile", profile)

    if opts.project:
        run(cli, "profile", "use", profile, "--project", opts.project)
    print(f"profile {profile}: {len(snapshot['categories'])} categories restored"
          + (f", board {opts.project} uses it" if opts.project else ""))


if __name__ == "__main__":
    main()
