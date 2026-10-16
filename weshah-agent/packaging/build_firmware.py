#!/usr/bin/env python3
"""Assembles a Weshah firmware image from an official OpenWrt TP-Link "factory/sysupgrade" image.

Nothing is flashed or written to a router. The input image is only read. The kernel is carried over
byte for byte (nothing in the agent needs kernel changes); only the squashfs rootfs is rebuilt, from
the original file tree plus the Weshah payload, with the same layout the original image has:

    [512 B TP-Link v1 header][kernel][squashfs][0xFF to 4 KiB][deadc0de][0xFF ... total length unchanged]

The TP-Link header MD5 (md5sum1) cannot be regenerated without the vendor salt; it is zeroed rather than
left stale. OpenWrt's own `sysupgrade` does not check it (it checks magic, hardware id, model id and the
bootloader size, all kept), but TP-Link's web updater / bootloader recovery will reject this file.

usage: build_firmware.py --factory ORIG.bin --stage STAGE_DIR --work WORK_DIR --out OUT_DIR   (run as root)
"""
import argparse, hashlib, os, shutil, struct, subprocess, sys, time

SQ_ARGS = ["-comp", "xz", "-b", "262144", "-noappend", "-nopad", "-all-root", "-no-xattrs", "-quiet"]
ERASE = 65536

def run(*a, **k):
    return subprocess.run(a, check=True, **k)

def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--factory", required=True); ap.add_argument("--stage", required=True)
    ap.add_argument("--work", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--control", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "control.in"))
    a = ap.parse_args()
    for d in (a.work, a.out):
        os.makedirs(d, exist_ok=True)

    orig = open(a.factory, "rb").read()
    hdr = bytearray(orig[:512])
    ver, = struct.unpack(">I", hdr[0:4]);  assert ver == 0x01000000, "not a TP-Link v1 header"
    hw_id, hw_rev = struct.unpack(">II", hdr[64:72])
    fw_len, kofs, klen, rofs, rlen, bofs, blen = struct.unpack(">IIIIIII", hdr[124:152])
    assert fw_len == len(orig) and bofs == 0 and blen == 0, "unexpected image layout"
    assert orig[rofs:rofs + 4] == b"hsqs", "no squashfs at the rootfs offset"
    kernel = orig[kofs:kofs + klen]
    orig_sq = orig[rofs:rofs + rlen]
    print("original: hw_id=0x%08x rev=%d  kernel=%d @0x%x  rootfs=%d @0x%x  total=%d" % (hw_id, hw_rev, klen, kofs, rlen, rofs, fw_len))

    # 1. unpack the original rootfs
    sq0 = os.path.join(a.work, "orig-rootfs.squashfs"); open(sq0, "wb").write(orig_sq)
    tree = os.path.join(a.work, "rootfs")
    if os.path.exists(tree):
        sys.exit("work dir already holds a rootfs; use a fresh --work")
    run("unsquashfs", "-no-xattrs", "-d", tree, sq0, stdout=subprocess.DEVNULL)

    # 2. Weshah payload (identical to what the ipk installs)
    run("cp", "-a", os.path.join(a.stage, "."), tree)
    for s in ("S99weshah-agent", "K10weshah-agent"):              # what `/etc/init.d/weshah-agent enable` creates
        os.symlink("../init.d/weshah-agent", os.path.join(tree, "etc/rc.d", s))
    base_desc = [l.split("=", 1)[1].strip("'\n") for l in open(os.path.join(tree, "etc/openwrt_release")) if l.startswith("DISTRIB_DESCRIPTION")][0]
    with open(os.path.join(tree, "etc/weshah_release"), "w") as f:
        f.write("WESHAH_VERSION='2.1.0'\nWESHAH_BASE='%s'\nWESHAH_BASE_IMAGE_SHA256='%s'\nWESHAH_BOARD='tl-wr841n-v11'\n" % (base_desc, hashlib.sha256(orig).hexdigest()))
    with open(os.path.join(tree, "etc/banner"), "a") as f:
        f.write(" Weshah Network Manager Agent 2.1.0 (LAN, port 8765)\n")

    # 3. register the package with opkg exactly as an installed ipk would be (so `opkg upgrade/remove` work)
    payload = []
    for root, _, files in os.walk(a.stage):
        for fn in files:
            payload.append("/" + os.path.relpath(os.path.join(root, fn), a.stage))
    payload.sort()
    size = sum(os.path.getsize(os.path.join(a.stage, p.lstrip("/"))) for p in payload)
    control = open(a.control).read().replace("@SIZE@", str(size))
    info = os.path.join(tree, "usr/lib/opkg/info")
    open(os.path.join(info, "weshah-agent.control"), "w").write(control)
    open(os.path.join(info, "weshah-agent.list"), "w").write("\n".join(payload) + "\n")
    conf_sha = hashlib.sha256(open(os.path.join(a.stage, "etc/weshah-agent.conf"), "rb").read()).hexdigest()
    open(os.path.join(info, "weshah-agent.conffiles"), "w").write("/etc/weshah-agent.conf\n")
    status = control.rstrip("\n") + "\nStatus: install user installed\nConffiles:\n /etc/weshah-agent.conf %s\nInstalled-Time: %d\n\n" % (conf_sha, int(time.time()))
    with open(os.path.join(tree, "usr/lib/opkg/status"), "a") as f:
        if not open(os.path.join(tree, "usr/lib/opkg/status")).read().endswith("\n\n"):
            f.write("\n")
        f.write(status)

    # 4. rebuild the squashfs (the control build of the unmodified tree gives an identical file listing)
    sq1 = os.path.join(a.out, "Weshah-rootfs.bin")
    if os.path.exists(sq1):
        os.unlink(sq1)
    run("mksquashfs", tree, sq1, *SQ_ARGS, "-mkfs-time", "1605125398", stdout=subprocess.DEVNULL)
    new = open(sq1, "rb").read()
    print("rootfs: %d -> %d bytes (%+d)" % (rlen, len(new), len(new) - rlen))

    # 5. assemble with the original layout
    end = rofs + len(new)
    e4 = (end + 4095) // 4096 * 4096
    if e4 + 4 > fw_len:
        sys.exit("rootfs does not fit: needs %d, image holds %d" % (e4 + 4, fw_len))
    img = bytearray(b"\xff" * fw_len)
    struct.pack_into(">I", hdr, 140, len(new))      # rootfs_len
    hdr[76:92] = b"\0" * 16                           # md5sum1: not computable, see module docstring
    img[0:512] = hdr
    img[kofs:kofs + klen] = kernel
    img[rofs:end] = new
    img[e4:e4 + 4] = bytes.fromhex("deadc0de")
    out = os.path.join(a.out, "Weshah-firmware.bin")
    open(out, "wb").write(img)
    overlay_start = (end + ERASE - 1) // ERASE * ERASE
    orig_overlay = ((rofs + rlen + ERASE - 1) // ERASE * ERASE)
    print("image:  %d bytes; squashfs ends 0x%x, marker 0x%x; overlay (rootfs_data) from 0x%x: %d KiB free (stock image: %d KiB)" % (
        len(img), end, e4, overlay_start, (fw_len - overlay_start) // 1024, (fw_len - orig_overlay) // 1024))
    open(os.path.join(a.work, "build.json"), "w").write(
        '{"orig_sha256":"%s","kernel_sha256":"%s","rootfs_len":%d,"squashfs_end":%d,"marker":%d,"overlay_start":%d,"overlay_kib":%d,"stock_overlay_kib":%d}\n' % (
            hashlib.sha256(orig).hexdigest(), hashlib.sha256(kernel).hexdigest(), len(new), end, e4, overlay_start,
            (fw_len - overlay_start) // 1024, (fw_len - orig_overlay) // 1024))

main()
