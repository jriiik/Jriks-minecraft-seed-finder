#!/usr/bin/env bash
# Prints the version of the next automatic release: the highest existing version among the repository's tags
# (two numbers, like "1.07" or "v1.07") with its second number raised by one, keeping its zero padding:
# 1.07 gives 1.08, 1.09 gives 1.10, 1.99 gives 1.100, and 2.00 (a release made by hand) gives 2.01.
# Reads the tag names, one per line, on standard input. Tags of any other form ("1.2.3", "beta") are ignored.
# Without any such tag it prints 1.00.
set -euo pipefail

best_major=-1
best_minor=-1
best_width=2
while IFS= read -r tag || [[ -n "$tag" ]]; do
    tag="${tag%$'\r'}"
    if [[ "$tag" =~ ^[vV]?([0-9]{1,9})\.([0-9]{1,9})$ ]]; then
        major=$((10#${BASH_REMATCH[1]}))
        minor_text="${BASH_REMATCH[2]}"
        minor=$((10#$minor_text))
        if (( major > best_major || (major == best_major && minor > best_minor) )); then
            best_major=$major
            best_minor=$minor
            best_width=${#minor_text}
        fi
    fi
done

if (( best_major < 0 )); then
    echo "1.00"
else
    printf '%d.%0*d\n' "$best_major" "$best_width" "$((best_minor + 1))"
fi
