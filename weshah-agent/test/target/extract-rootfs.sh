#!/bin/sh
# Carves the squashfs rootfs out of a TP-Link "factory" image and unpacks it.
# The original image is only read. usage: extract-rootfs.sh <factory.bin> <outdir>
set -e
IMG="$1"; OUT="$2"
[ -f "$IMG" ] && [ -n "$OUT" ] || { echo "usage: $0 <factory.bin> <outdir>" >&2; exit 2; }
mkdir -p "$OUT"
python3 -I - "$IMG" "$OUT/rootfs.squashfs" <<'PY'
import struct, sys
d = open(sys.argv[1], 'rb').read()
rofs, rlen = struct.unpack('>II', d[136:144])          # TP-Link v1 header: rootfs offset/length
assert d[rofs:rofs + 4] == b'hsqs', 'no squashfs magic at the rootfs offset'
open(sys.argv[2], 'wb').write(d[rofs:rofs + rlen])
print('rootfs: offset=0x%x length=%d' % (rofs, rlen))
PY
unsquashfs -no-xattrs -d "$OUT/rootfs" "$OUT/rootfs.squashfs" >/dev/null
echo "extracted to $OUT/rootfs ($(cat "$OUT/rootfs/etc/openwrt_release" | grep DISTRIB_DESCRIPTION))"
