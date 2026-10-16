#!/usr/bin/env python3
"""Deterministic TCP transfer for rate tests.
  xfer.py sink   <port> <nbytes>            accept one connection, read nbytes, reply b'K'
  xfer.py send   <host> <port> <nbytes>     connect, send nbytes, wait for b'K'; prints seconds
  xfer.py source <port> <nbytes>            accept one connection, send nbytes
  xfer.py fetch  <host> <port> <nbytes>     connect, read nbytes; prints seconds
Seconds are measured until the last byte is delivered, never until a process happens to exit."""
import socket, sys, time

CH = b"\0" * 16384

def serve(port):
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", port)); s.listen(1); return s.accept()[0]

def main():
    mode = sys.argv[1]
    if mode == "sink":
        c = serve(int(sys.argv[2])); n = int(sys.argv[3]); got = 0
        while got < n:
            d = c.recv(65536)
            if not d: break
            got += len(d)
        c.sendall(b"K"); c.close(); return 0 if got == n else 1
    if mode == "source":
        c = serve(int(sys.argv[2])); n = int(sys.argv[3])
        while n > 0:
            k = min(n, len(CH)); c.sendall(CH[:k]); n -= k
        c.close(); return 0
    host, port, n = sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
    c = socket.create_connection((host, port), timeout=60)
    t = time.time()
    if mode == "send":
        left = n
        while left > 0:
            k = min(left, len(CH)); c.sendall(CH[:k]); left -= k
        c.shutdown(socket.SHUT_WR)
        ok = c.recv(1) == b"K"
    else:
        got = 0
        while got < n:
            d = c.recv(65536)
            if not d: break
            got += len(d)
        ok = got == n
    print("%.2f" % (time.time() - t) if ok else "BADXFER")
    return 0 if ok else 1

sys.exit(main())
