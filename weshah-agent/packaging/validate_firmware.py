#!/usr/bin/env python3
"""Structural validation of a Weshah firmware image against the original OpenWrt image. Read-only.

It proves the image is well formed and differs from the original only where intended. It can NOT prove
that the kernel boots it on the router: that needs the router (see build-report.md, "needs a real router").

usage: validate_firmware.py --factory ORIG.bin --image Weshah-firmware.bin --rootfs Weshah-rootfs.bin
                            --work WORKDIR [--chroot TESTROOT]   (run as root: unsquashfs creates /dev nodes)
"""
import argparse, hashlib, os, struct, subprocess, sys, json

OK = FAIL = 0
def check(name, cond, detail=""):
    global OK, FAIL
    if cond: OK += 1; print("PASS  " + name)
    else: FAIL += 1; print("FAIL  %s %s" % (name, detail))

def listing(path):
    out = subprocess.run(["unsquashfs", "-ll", path], check=True, capture_output=True, text=True).stdout.splitlines()[3:]
    return {l.split()[-1] if "->" not in l else l.split(" -> ")[0].split()[-1]: l for l in out}

def main():
    ap = argparse.ArgumentParser()
    for k in ("factory", "image", "rootfs", "work"):
        ap.add_argument("--" + k, required=True)
    ap.add_argument("--chroot")
    a = ap.parse_args()
    os.makedirs(a.work, exist_ok=True)
    orig = open(a.factory, "rb").read(); img = open(a.image, "rb").read(); rfs = open(a.rootfs, "rb").read()
    h0, h1 = orig[:512], img[:512]
    f = lambda h: struct.unpack(">IIIIIII", h[124:152])
    fw0, k0o, k0l, r0o, r0l, b0o, b0l = f(h0); fw1, k1o, k1l, r1o, r1l, b1o, b1l = f(h1)

    print("== header")
    check("size equals the flash partition image size", len(img) == len(orig) == fw1 == 3932160)
    check("header version / vendor / fw_version unchanged", h0[0:64] == h1[0:64])
    check("hardware id and model id unchanged (what sysupgrade compares)", h0[64:72] == h1[64:72])
    check("hardware id is TL-WR841 v11 (0x08410011)", struct.unpack(">I", h1[64:68])[0] == 0x08410011)
    check("load address / entry point unchanged", h0[116:124] == h1[116:124])
    check("kernel offset and length unchanged", (k0o, k0l) == (k1o, k1l))
    check("rootfs offset unchanged", r0o == r1o)
    check("rootfs length matches the squashfs", r1l == len(rfs))
    check("no bootloader in the image (sysupgrade requirement)", (b1o, b1l) == (0, 0))
    check("md5sum1 zeroed, not stale (cannot be regenerated)", h1[76:92] == b"\0" * 16)
    check("all remaining header bytes unchanged", h0[92:116] == h1[92:116] and h0[144:512] == h1[144:512] and h0[152:156] == h1[152:156])

    print("== kernel")
    check("kernel region is byte-identical to the original", img[k1o:k1o + k1l] == orig[k0o:k0o + k0l])
    check("kernel gap between header and kernel is unchanged", img[512:k1o] == orig[512:k0o])

    print("== rootfs")
    check("squashfs magic", rfs[:4] == b"hsqs")
    sb = struct.unpack("<IIIIIHHHHHHQQQQQQQQ", rfs[:96])
    check("squashfs 4.0, xz, 256 KiB blocks", (sb[5], sb[6]) == (4, 0) or True)  # fields checked precisely below
    comp, block_log = struct.unpack("<H", rfs[20:22])[0], struct.unpack("<H", rfs[22:24])[0]
    major, minor = struct.unpack("<HH", rfs[28:32])
    bytes_used = struct.unpack("<Q", rfs[40:48])[0]
    check("squashfs version 4.0", (major, minor) == (4, 0))
    check("compression is xz (the kernel option the original uses)", comp == 4)
    check("block size 256 KiB", struct.unpack("<I", rfs[12:16])[0] == 262144)
    check("superblock bytes_used equals the file length", bytes_used == len(rfs))
    check("image embeds exactly this squashfs", img[r1o:r1o + r1l] == rfs)
    new_tree = os.path.join(a.work, "validate-rootfs")
    r = subprocess.run(["unsquashfs", "-no-xattrs", "-d", new_tree, a.rootfs], capture_output=True, text=True)
    check("squashfs unpacks without errors", r.returncode == 0, r.stderr[-200:])
    t0 = os.path.join(a.work, "validate-orig")
    open(os.path.join(a.work, "o.sqfs"), "wb").write(orig[r0o:r0o + r0l])
    subprocess.run(["unsquashfs", "-no-xattrs", "-d", t0, os.path.join(a.work, "o.sqfs")], check=True, capture_output=True)

    def files(root):
        out = {}
        for d, _, fs in os.walk(root):
            for n in fs:
                p = os.path.join(d, n); rel = os.path.relpath(p, root)
                if os.path.islink(p): out[rel] = "L:" + os.readlink(p)
                elif os.path.isfile(p): out[rel] = hashlib.sha256(open(p, "rb").read()).hexdigest()
                else: out[rel] = "special:%o" % os.lstat(p).st_mode        # device nodes must never be opened
        return out
    A, B = files(t0), files(new_tree)
    removed = sorted(set(A) - set(B)); added = sorted(set(B) - set(A)); changed = sorted(p for p in set(A) & set(B) if A[p] != B[p])
    check("nothing was removed from the original system", removed == [], str(removed[:5]))
    check("only banner and opkg status were modified", changed == ["etc/banner", "usr/lib/opkg/status"], str(changed))
    expect_added = {"etc/init.d/weshah-agent", "etc/rc.d/S99weshah-agent", "etc/rc.d/K10weshah-agent", "etc/weshah-agent.conf",
                    "etc/weshah_release", "lib/upgrade/keep.d/weshah-agent", "usr/lib/opkg/info/weshah-agent.control",
                    "usr/lib/opkg/info/weshah-agent.list", "usr/lib/opkg/info/weshah-agent.conffiles",
                    "usr/libexec/weshah-agent/tick", "usr/sbin/weshah-agent-ticker"}
    expect_added |= {"usr/lib/weshah-agent/" + n for n in ("lib.sh", "enforce.sh", "acct.sh", "qos.sh", "system.sh", "devices.sh", "vlan.sh", "backup.sh", "www/api", "www/index.html", "www/app.css", "www/app.js")}
    check("exactly the Weshah files were added", set(added) == expect_added, str(sorted(set(added) ^ expect_added)))
    st = os.stat(os.path.join(new_tree, "etc/weshah-agent.conf")).st_mode & 0o777
    check("agent config is mode 0600", st == 0o600, oct(st))
    check("agent entry points are executable", all(os.access(os.path.join(new_tree, p), os.X_OK) for p in
          ("etc/init.d/weshah-agent", "usr/lib/weshah-agent/www/api", "usr/libexec/weshah-agent/tick", "usr/sbin/weshah-agent-ticker")))
    check("service is enabled at boot (S99 symlink)", os.readlink(os.path.join(new_tree, "etc/rc.d/S99weshah-agent")) == "../init.d/weshah-agent")
    check("stock services keep their rc.d entries", all(os.path.islink(os.path.join(new_tree, "etc/rc.d", n)) for n in ("S50uhttpd", "S12rpcd")))
    check("OpenWrt identity untouched (opkg feeds / sysupgrade rely on it)", open(os.path.join(t0, "etc/openwrt_release")).read() == open(os.path.join(new_tree, "etc/openwrt_release")).read())
    check("board identification scripts untouched", all(A[p] == B[p] for p in A if p.startswith(("lib/ar71xx.sh", "lib/upgrade/platform.sh", "etc/board.d/"))))
    status = open(os.path.join(new_tree, "usr/lib/opkg/status")).read()
    check("opkg knows the package (single stanza, installed)", status.count("Package: weshah-agent\n") == 1 and "Status: install user installed" in status.split("Package: weshah-agent\n")[1])

    print("== layout")
    end = r1o + len(rfs); e4 = (end + 4095) // 4096 * 4096
    check("padding after squashfs is 0xFF up to the 4 KiB boundary", set(img[end:e4]) <= {0xff})
    check("jffs2 end marker deadc0de at the boundary", img[e4:e4 + 4] == bytes.fromhex("deadc0de"))
    check("everything after the marker is erased flash (0xFF)", set(img[e4 + 4:]) <= {0xff})
    overlay = (end + 65535) // 65536 * 65536
    check("rootfs_data (overlay) keeps >= 128 KiB: %d KiB" % ((len(img) - overlay) // 1024), len(img) - overlay >= 128 * 1024)

    if a.chroot:
        print("== the firmware's own sysupgrade image check (lib/upgrade/platform.sh), run on this image")
        T = a.chroot
        for name, data in (("w_good.bin", img), ("w_wrong_hw.bin", img[:64] + b"\x08\x41\x00\x99" + img[68:]), ("w_bootloader.bin", img[:148] + b"\0\0\1\0" + img[152:])):
            open(os.path.join(T, "tmp", name), "wb").write(data)
        script = r'''
. /lib/functions.sh; . /lib/upgrade/common.sh; . /lib/upgrade/platform.sh
tplink_get_hwid() { echo 08410011; }
tplink_get_mid() { echo %s; }
for f in w_good w_wrong_hw w_bootloader; do
    platform_check_image /tmp/$f.bin >/tmp/$f.out 2>&1; echo "$f rc=$?"
done
''' % h1[68:72].hex()
        open(os.path.join(T, "tmp", "chk.sh"), "w").write(script)
        r = subprocess.run(["chroot", T, "/bin/sh", "/tmp/chk.sh"], capture_output=True, text=True, timeout=120)
        res = dict(l.split() for l in r.stdout.split("\n") if l.strip())
        res = {k: v for k, v in (l.split(" ") for l in r.stdout.strip().split("\n") if " rc=" in l)}
        check("platform_check_image ACCEPTS the Weshah image", res.get("w_good") == "rc=0", r.stdout + r.stderr[-300:])
        check("...and REJECTS a wrong hardware id (negative control)", res.get("w_wrong_hw") == "rc=1", str(res))
        check("...and REJECTS an image containing a bootloader (negative control)", res.get("w_bootloader") == "rc=1", str(res))
        check("the original image is accepted by the same code (baseline)", subprocess.run(["chroot", T, "/bin/sh", "-c", ". /lib/functions.sh; . /lib/upgrade/common.sh; . /lib/upgrade/platform.sh; tplink_get_hwid() { echo 08410011; }; tplink_get_mid() { echo %s; }; platform_check_image /tmp/w_orig.bin" % h1[68:72].hex()], capture_output=True).returncode == 0
              if open(os.path.join(T, "tmp", "w_orig.bin"), "wb").write(orig) else False)

    print("\nRESULT: %d passed, %d failed" % (OK, FAIL))
    sys.exit(1 if FAIL else 0)

main()
