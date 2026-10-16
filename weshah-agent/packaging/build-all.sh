#!/bin/sh
# Builds every deliverable from the repo sources and the ORIGINAL OpenWrt image, then validates the image.
# Nothing is flashed or written to a router. Needs: root (unsquashfs creates /dev nodes), squashfs-tools, python3, xz.
#   build-all.sh <openwrt-18.06.9-...-tl-wr841-v11-squashfs-factory.bin> <new work dir> <output dir> [testroot]
# `testroot` (optional) is an extracted firmware rootfs prepared by test/target/setup.sh; with it the firmware's
# own platform_check_image is also run on the result.
set -e
ORIG="$1"; WORK="$2"; OUT="$3"; TESTROOT="$4"
[ -f "$ORIG" ] && [ -n "$WORK" ] && [ -n "$OUT" ] || { echo "usage: $0 <factory.bin> <new work dir> <output dir> [testroot]" >&2; exit 2; }
[ ! -e "$WORK" ] || { echo "work dir must not exist" >&2; exit 2; }
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$WORK" "$OUT"
sh "$HERE/stage.sh" "$WORK/stage"
sh "$HERE/build-ipk.sh" "$WORK/stage" "$OUT/weshah-agent_2.1.0-1_all.ipk"
python3 "$HERE/build_firmware.py" --factory "$ORIG" --stage "$WORK/stage" --work "$WORK/fw" --out "$WORK/img"
cp "$WORK/img/Weshah-firmware.bin" "$WORK/img/Weshah-rootfs.bin" "$OUT/"
python3 "$HERE/validate_firmware.py" --factory "$ORIG" --image "$OUT/Weshah-firmware.bin" --rootfs "$OUT/Weshah-rootfs.bin" \
    --work "$WORK/validate" ${TESTROOT:+--chroot "$TESTROOT"} | tee "$OUT/validation.log"
( cd "$OUT" && sha256sum weshah-agent_2.1.0-1_all.ipk Weshah-rootfs.bin Weshah-firmware.bin > SHA256SUMS )
echo "done: $OUT"
