#!/usr/bin/env python3
"""Start a real Forge server with the built jar, wait for "Done", run a few commands, stop it.

Fails if the server crashes, takes too long, or the log is missing expected lines or contains errors
from this mod. Used by CI as a production-like check (reobfuscated jar, real mods folder).
"""
import re
import subprocess
import sys
import threading
import time

server_dir, timeout = sys.argv[1], int(sys.argv[2])
expect = sys.argv[3:]

proc = subprocess.Popen(["bash", "./run.sh", "nogui"], cwd=server_dir, stdin=subprocess.PIPE,
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
lines = []
done = threading.Event()

def reader():
    for line in proc.stdout:
        sys.stdout.write(line)
        lines.append(line)
        if re.search(r"Done \(\d", line):
            done.set()
threading.Thread(target=reader, daemon=True).start()

start = time.time()
while not done.is_set() and proc.poll() is None and time.time() - start < timeout:
    time.sleep(1)

if not done.is_set():
    print(f"::error::Server did not finish starting within {timeout}s (exit={proc.poll()})")
    proc.kill()
    sys.exit(1)

# Exercise the mod a little on the running server: the item exists and can be given.
for cmd in ["forge mods", "give @a ae2declogger:declogger", "list", "stop"]:
    proc.stdin.write(cmd + "\n")
    proc.stdin.flush()
    time.sleep(2)

try:
    code = proc.wait(timeout=120)
except subprocess.TimeoutExpired:
    print("::error::Server did not stop")
    proc.kill()
    sys.exit(1)
time.sleep(1)
log = "".join(lines)

failed = False
if code != 0:
    print(f"::error::Server exited with code {code}")
    failed = True
for needle in expect:
    if needle not in log:
        print(f"::error::Expected log line not found: {needle}")
        failed = True
bad = [l for l in lines if ("ae2declogger" in l.lower() or "declogger" in l.lower())
       and re.search(r"/(ERROR|FATAL)\]|Exception", l)]
if bad:
    print("::error::Errors mentioning this mod:\n" + "".join(bad[:20]))
    failed = True
if "Crash report" in log or "Exception in server tick loop" in log:
    print("::error::Server crashed")
    failed = True
print("smoke test", "FAILED" if failed else "passed")
sys.exit(1 if failed else 0)
