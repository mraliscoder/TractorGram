"""Inspect the signed TractorGram APK and write its release metadata."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


PACKAGE = "network.zov.messenger"
PUBLISHED_SIGNER = "ecee5b126d9a6757b3f396557e8bcb6c73871592ba729e3333c0f409790c3159"
PUBLISHED_CODE = 70381
UNIVERSAL_ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}


def run_tool(build_tools, name, *args):
    suffix = (".bat" if name == "apksigner" else ".exe") if os.name == "nt" else ""
    return subprocess.check_output([str(build_tools / (name + suffix)), *args],
                                   encoding="utf-8", errors="replace")


def inspect_apk(apk, build_tools):
    badging = run_tool(build_tools, "aapt", "dump", "badging", str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^sdkVersion:'([0-9]+)'", badging, re.M)
    native = re.search(r"^native-code: (.+)$", badging, re.M)
    if not package or not sdk or not native:
        raise ValueError("APK badging lacks package, minSdk or native ABIs")
    verification = run_tool(build_tools, "apksigner", "verify", "--print-certs", str(apk))
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$", verification, re.M)
    if len(signers) != 1:
        raise ValueError("Expected a single verified APK signer")
    application_id, code, version = package.groups()
    with apk.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return {
        "applicationId": application_id,
        "versionName": version,
        "versionCode": int(code),
        "minSdk": int(sdk[1]),
        "abis": sorted(set(re.findall(r"'([^']+)'", native[1]))),
        "apkSha256": digest,
        "signerSha256": signers[0].lower(),
    }


def release_metadata(apk, build_tools, commit):
    info = inspect_apk(apk, build_tools)
    if apk.name != "app.apk" or info["applicationId"] != PACKAGE or set(info["abis"]) != UNIVERSAL_ABIS:
        raise ValueError("Expected the universal TractorGram app.apk")
    if info["versionCode"] <= PUBLISHED_CODE or info["versionCode"] % 10 != 1:
        raise ValueError("Expected a newer bundleAfat APK versionCode")
    if info["signerSha256"] != PUBLISHED_SIGNER:
        raise ValueError("APK signer differs from the published TractorGram release")
    if not re.fullmatch(r"[0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5}", info["versionName"]):
        raise ValueError("Expected a three-part stable versionName")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Expected the full Git commit SHA")
    return {"schemaVersion": 1, "channel": "github-stable", "buildCommit": commit, "apk": apk.name, **info}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--build-tools", required=True, type=Path)
    parser.add_argument("--commit")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if bool(args.commit) != bool(args.output):
        parser.error("--commit and --output must be supplied together")
    if args.output:
        metadata = release_metadata(args.apk, args.build_tools, args.commit)
        args.output.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    else:
        print(json.dumps(inspect_apk(args.apk, args.build_tools), indent=2))


if __name__ == "__main__":
    main()
