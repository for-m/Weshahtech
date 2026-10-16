#!/bin/sh
# start|stop the real ubusd, rpcd and the agent's uhttpd inside the test root.
WORK="$2"; T="$WORK/testroot"
case "$1" in
start)
    mountpoint -q "$T/proc" || mount -t proc proc "$T/proc"
    mkdir -p "$T/var/run" "$T/tmp/run"
    chroot "$T" /bin/sh -c 'ubusd >/tmp/ubusd.log 2>&1 & sleep 1; rpcd >/tmp/rpcd.log 2>&1 & sleep 1; mkdir -p /tmp/weshah-agent; /usr/sbin/uhttpd -f -h /usr/lib/weshah-agent/www -x /api -p 127.0.0.1:8765 -t 120 -T 30 -N 12 -n 4 -D -S >/tmp/uhttpd.log 2>&1 &' &
    i=0; while [ $i -lt 40 ]; do curl -s -o /dev/null -m 2 http://127.0.0.1:8765/api/v1/status && break; i=$((i+1)); sleep 1; done
    curl -s -o /dev/null -m 2 -w 'uhttpd http %{http_code}\n' http://127.0.0.1:8765/api/v1/status ;;
stop)
    # one fixed string per pkill: -f patterns are matched against the whole command line
    for p in "qemu-mips-static /usr/sbin/uhttpd" "qemu-mips-static /sbin/rpcd" "qemu-mips-static /usr/sbin/rpcd" "qemu-mips-static /sbin/ubusd"; do
        pkill -f "$p" 2>/dev/null
    done
    i=0; while [ $i -lt 10 ] && curl -s -o /dev/null -m 1 http://127.0.0.1:8765/api/v1/status; do sleep 1; i=$((i + 1)); done
    true ;;
esac
