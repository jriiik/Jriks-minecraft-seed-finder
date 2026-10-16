#!/usr/bin/env bash
# Regenerates the engine's version data and test fixtures from the official Minecraft server.
#
# Usage:  tools/oracle/run-oracle.sh <minecraft-version>      (examples: tools/oracle/run-oracle.sh 26.3,
#                                                               tools/oracle/run-oracle.sh 1.21.11)
# Needs:  bash, curl, unzip, python3 and a JDK new enough for that Minecraft version
#         (Java 25 for 26.x), either as $JAVA_HOME or as `java` on the PATH.
#         Versions with obfuscated names (their version JSON lists "server_mappings", e.g. 1.21.11) also need the
#         repository's Gradle wrapper to run on that JDK (Java 25 here): a small Fabric Loom project gets the game with
#         Mojang's names, which the oracle is written against.
#
# The oracle's sources are tools/oracle/<minecraft-version>/*.java when that folder exists (versions whose game code
# differs), else tools/oracle/*.java.
# The server jar is downloaded from Mojang into tools/oracle/build/ (ignored by git) and is never committed; so is the
# Loom project. Loom keeps the renamed game in Gradle's own cache. Only the oracle's output (plain numbers and names)
# is written into the repository.
set -euo pipefail

version="${1:?usage: tools/oracle/run-oracle.sh <minecraft-version>}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$root/tools/oracle/build/$version"
java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
data_dir="$root/engine/src/main/resources/io/github/jriiik/seedfinder/versions/$version"
fixtures_dir="$root/engine/src/test/resources/fixtures/$version"
sources="$root/tools/oracle"
if [[ -d "$root/tools/oracle/$version" ]]; then
    sources="$root/tools/oracle/$version"
fi
mkdir -p "$work"

echo "Looking up Minecraft $version in Mojang's version manifest..."
version_url="$(curl -fsSL https://piston-meta.mojang.com/mc/game/version_manifest_v2.json | python3 -c '
import json, sys
wanted = sys.argv[1]
urls = [v["url"] for v in json.load(sys.stdin)["versions"] if v["id"] == wanted]
if not urls:
    sys.exit("Minecraft version " + wanted + " is not in the version manifest")
print(urls[0])' "$version")"

read -r server_url server_sha1 java_major obfuscated < <(curl -fsSL "$version_url" | python3 -c '
import json, sys
d = json.load(sys.stdin)
s = d["downloads"]["server"]
print(s["url"], s["sha1"], d.get("javaVersion", {}).get("majorVersion", 0), "yes" if "server_mappings" in d["downloads"] else "no")')

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

if [[ "$obfuscated" != yes ]]; then
    echo "Extracting the server and its libraries..."
    rm -rf "$work/extracted"
    unzip -q -o "$server_jar" 'META-INF/versions/*' 'META-INF/libraries/*' -d "$work/extracted"
    classpath="$(find "$work/extracted/META-INF" -name '*.jar' | sort | tr '\n' ':')"
else
    # The oracle reads more than one source file, which Java's source launcher does from Java 22 on.
    if (( java_version < 22 )); then
        echo "The oracle needs Java 22 or newer, but '$java' is Java $java_version. Set JAVA_HOME to a newer JDK." >&2
        exit 1
    fi

    # The server jar's code has obfuscated names. Fabric Loom (the version the mod uses) renames the game with Mojang's
    # official mappings; the oracle runs on that jar plus exactly the libraries the server itself ships.
    loom_version="$(python3 -c '
import re, sys
section = None
for line in open(sys.argv[1], encoding="utf-8"):
    line = line.strip()
    header = re.fullmatch(r"\[(.+)\]", line)
    if header:
        section = header.group(1)
        continue
    entry = re.fullmatch(r"fabric-loom\s*=\s*\"([^\"]+)\"\s*(#.*)?", line)
    if section == "versions" and entry:
        print(entry.group(1))
        break
else:
    sys.exit("gradle/libs.versions.toml has no fabric-loom version under [versions]")' "$root/gradle/libs.versions.toml")"

    loom_dir="$work/loom"
    mkdir -p "$loom_dir"
    cat > "$loom_dir/settings.gradle.kts" <<EOF
// Written by tools/oracle/run-oracle.sh: only gets Minecraft $version renamed with Mojang's names for the oracle.
pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "oracle-minecraft-$version"
EOF
    cat > "$loom_dir/build.gradle.kts" <<EOF
// Written by tools/oracle/run-oracle.sh: only gets Minecraft $version renamed with Mojang's names for the oracle.
// Nothing is compiled here.
plugins {
    id("net.fabricmc.fabric-loom-remap") version "$loom_version"
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$version")
    mappings(loom.officialMojangMappings())
}

// Prints the runtime classpath (the renamed game and Loom's libraries), one "CP <path>" line per entry.
tasks.register("printClasspath") {
    val classpath = sourceSets.main.get().runtimeClasspath
    doLast {
        classpath.files.forEach { println("CP " + it.absolutePath) }
    }
}
EOF

    echo "Getting Minecraft $version with Mojang's names (Fabric Loom $loom_version; the first run downloads and renames the game)..."
    loom_log="$work/loom.log"
    if ! "$root/gradlew" --console=plain --no-daemon -p "$loom_dir" printClasspath > "$loom_log" 2>&1; then
        echo "The Loom project in $loom_dir failed; see $loom_log" >&2
        exit 1
    fi
    named=()
    while IFS= read -r entry; do
        if [[ "$entry" == */minecraft-merged-*.jar ]]; then
            named+=("$entry")
        fi
    done < <(sed -n 's/^CP //p' "$loom_log")
    if (( ${#named[@]} != 1 )); then
        echo "Expected exactly one renamed Minecraft jar (minecraft-merged-*.jar) from Loom, found ${#named[@]}; see $loom_log" >&2
        exit 1
    fi

    echo "Extracting the server's libraries..."
    rm -rf "$work/extracted"
    unzip -q -o "$server_jar" 'META-INF/libraries.list' 'META-INF/libraries/*' -d "$work/extracted"
    entries=("${named[0]}")
    # One line per library: "<sha256> <tab> <group:name:version> <tab> <path under META-INF/libraries/>".
    while IFS=$'\t' read -r _ library path || [[ -n "${library:-}" ]]; do
        if [[ -z "$library" ]]; then
            continue
        fi
        jar="$work/extracted/META-INF/libraries/$path"
        if [[ -z "$path" || ! -f "$jar" ]]; then
            echo "The server jar's libraries.list names $library at '$path', but the server jar doesn't contain it" >&2
            exit 1
        fi
        entries+=("$jar")
    done < "$work/extracted/META-INF/libraries.list"
    if (( ${#entries[@]} < 2 )); then
        echo "The server jar's libraries.list lists no libraries" >&2
        exit 1
    fi
    classpath="$(IFS=:; echo "${entries[*]}")"
fi

echo "Running the oracle ($sources)..."
# Run from the work folder: the game writes its log files into the current directory.
(cd "$work" && "$java" -cp "$classpath" "$sources/Oracle.java" "$data_dir" "$fixtures_dir")
