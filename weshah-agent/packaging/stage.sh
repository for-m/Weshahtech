#!/bin/sh
# Builds the exact file tree that ships to a router (used for the ipk AND the firmware image):
# comments/blank lines stripped from the shell sources (flash is the scarce resource on 4 MB
# devices), permissions normalised, nothing else changed.
#   stage.sh <output-dir>     (the directory must not exist yet)
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../files"
OUT="$1"
[ -n "$OUT" ] && [ ! -e "$OUT" ] || { echo "usage: $0 <new output dir>" >&2; exit 2; }
mkdir -p "$OUT"
( cd "$SRC" && find . -type d | sort ) | while read -r d; do mkdir -p "$OUT/$d"; done
( cd "$SRC" && find . -type f | sort ) | while read -r f; do
    case "$f" in
        ./etc/weshah-agent.conf|./lib/upgrade/keep.d/*|./usr/lib/weshah-agent/www/index.html|./usr/lib/weshah-agent/www/app.css|./usr/lib/weshah-agent/www/app.js) cp "$SRC/$f" "$OUT/$f" ;;
        *) awk 'NR == 1 && /^#!/ { print; next } /^[ \t]*#/ { next } /^[ \t]*$/ { next } { print }' "$SRC/$f" > "$OUT/$f" ;;
    esac
    if [ -x "$SRC/$f" ]; then chmod 755 "$OUT/$f"; else chmod 644 "$OUT/$f"; fi
done
chmod 600 "$OUT/etc/weshah-agent.conf"
# every shell file must still parse after minification
( cd "$OUT" && find . -type f | sort ) | while read -r f; do
    head -n 1 "$OUT/$f" | grep -q '^#!/bin/sh\|^#!/bin/sh /etc/rc.common' && sh -n "$OUT/$f" || { case "$f" in *.sh) sh -n "$OUT/$f" ;; esac; }
done
echo "staged $(cd "$OUT" && find . -type f | wc -l) files, $(cd "$OUT" && find . -type f -exec cat {} + | wc -c) bytes -> $OUT"
