#!/bin/sh
# Builds weshah-agent_<ver>_all.ipk in OpenWrt's native layout (tar.gz of debian-binary, control.tar.gz,
# data.tar.gz). Pure shell/tar/gzip: no OpenWrt SDK is needed because the package is architecture independent.
#   build-ipk.sh <stage-dir> <output.ipk>
set -e
STAGE="$1"; OUT="$2"
[ -d "$STAGE/usr/lib/weshah-agent" ] && [ -n "$OUT" ] || { echo "usage: $0 <stage-dir> <out.ipk>" >&2; exit 2; }
VER=2.1.0-1
T=$(mktemp -d)
mkdir -p "$T/control" "$T/data"
cp -a "$STAGE/." "$T/data/"
SIZE=$(cd "$T/data" && find . -type f -exec cat {} + | wc -c)
sed "s/@SIZE@/$SIZE/" "$(dirname "$0")/control.in" > "$T/control/control"
printf '/etc/weshah-agent.conf\n' > "$T/control/conffiles"
cat > "$T/control/postinst" <<'EOT'
#!/bin/sh
[ -n "${IPKG_INSTROOT}" ] && exit 0
/etc/init.d/weshah-agent enable
/etc/init.d/weshah-agent restart
exit 0
EOT
cat > "$T/control/prerm" <<'EOT'
#!/bin/sh
[ -n "${IPKG_INSTROOT}" ] && exit 0
/etc/init.d/weshah-agent stop
/etc/init.d/weshah-agent disable
exit 0
EOT
chmod 755 "$T/control/postinst" "$T/control/prerm"
tar --owner=0 --group=0 --numeric-owner --sort=name --mtime='2026-01-01 00:00:00' -C "$T/control" -czf "$T/control.tar.gz" .
tar --owner=0 --group=0 --numeric-owner --sort=name --mtime='2026-01-01 00:00:00' -C "$T/data" -czf "$T/data.tar.gz" .
echo '2.0' > "$T/debian-binary"
( cd "$T" && tar --owner=0 --group=0 --numeric-owner --sort=name --mtime='2026-01-01 00:00:00' -czf "$OUT" ./debian-binary ./data.tar.gz ./control.tar.gz )
echo "built $OUT ($(wc -c < "$OUT") bytes, installed size $SIZE)"
