#!/usr/bin/env bash
# Regenerates the engine's version data and test fixtures from the official Minecraft server.
#
# Usage:  tools/oracle/run-oracle.sh <minecraft-version>      (example: tools/oracle/run-oracle.sh 26.3)
# Needs:  bash, curl, unzip, python3 and a JDK new enough for that Minecraft version
#         (Java 25 for 26.x), either as $JAVA_HOME or as `java` on the PATH.
#
# The server jar is downloaded from Mojang into tools/oracle/build/ (ignored by git) and is never committed.
# Only the oracle's output (plain numbers and names) is written into the repository.
set -euo pipefail

version="${1:?usage: tools/oracle/run-oracle.sh <minecraft-version>}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$root/tools/oracle/build/$version"
java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
data_dir="$root/engine/src/main/resources/io/github/jriiik/seedfinder/versions/$version"
fixtures_dir="$root/engine/src/test/resources/fixtures/$version"
mkdir -p "$work"

echo "Looking up Minecraft $version in Mojang's version manifest..."
version_url="$(curl -fsSL https://piston-meta.mojang.com/mc/game/version_manifest_v2.json | python3 -c '
import json, sys
wanted = sys.argv[1]
urls = [v["url"] for v in json.load(sys.stdin)["versions"] if v["id"] == wanted]
if not urls:
    sys.exit("Minecraft version " + wanted + " is not in the version manifest")
print(urls[0])' "$version")"

read -r server_url server_sha1 java_major < <(curl -fsSL "$version_url" | python3 -c '
import json, sys
d = json.load(sys.stdin)
s = d["downloads"]["server"]
print(s["url"], s["sha1"], d.get("javaVersion", {}).get("majorVersion", 0))')

sha1_of() { python3 -c 'import hashlib, sys; print(hashlib.sha1(open(sys.argv[1], "rb").read()).hexdigest())' "$1"; }

server_jar="$work/server.jar"
if [[ ! -f "$server_jar" || "$(sha1_of "$server_jar")" != "$server_sha1" ]]; then
    echo "Downloading the official server jar..."
    curl -fsSL -o "$server_jar" "$server_url"
fi
if [[ "$(sha1_of "$server_jar")" != "$server_sha1" ]]; then
    echo "Checksum mismatch for $server_jar (expected $server_sha1)" >&2
    exit 1
fi

java_version="$("$java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
if (( java_version < java_major )); then
    echo "Minecraft $version needs Java $java_major, but '$java' is Java $java_version. Set JAVA_HOME to a newer JDK." >&2
    exit 1
fi

echo "Extracting the server and its libraries..."
rm -rf "$work/extracted"
unzip -q -o "$server_jar" 'META-INF/versions/*' 'META-INF/libraries/*' -d "$work/extracted"
classpath="$(find "$work/extracted/META-INF" -name '*.jar' | sort | tr '\n' ':')"

echo "Running the oracle..."
# Run from the work folder: the game writes its log files into the current directory.
(cd "$work" && "$java" -cp "$classpath" "$root/tools/oracle/Oracle.java" "$data_dir" "$fixtures_dir")
