#!/usr/bin/env python3
"""
Upload stress test script.
Simulates concurrent file uploads to trigger the FD leak and page cache inflation.
"""

import os
import sys
import tempfile
import threading
import requests
import time

BASE_URL = os.environ.get("BASE_URL", "http://127.0.0.1:8080")
UPLOAD_URL = f"{BASE_URL}/upload"

CONCURRENCY = int(os.environ.get("CONCURRENCY", 50))
TOTAL_REQUESTS = int(os.environ.get("TOTAL_REQUESTS", 5000))
FILE_SIZE_KB = int(os.environ.get("FILE_SIZE_KB", 512))

success_count = 0
fail_count = 0
count_lock = threading.Lock()

def generate_temp_file(size_kb):
    fd, path = tempfile.mkstemp(suffix=".bin")
    try:
        os.write(fd, os.urandom(size_kb * 1024))
    finally:
        os.close(fd)
    return path

def worker(paths):
    global success_count, fail_count
    for path in paths:
        try:
            with open(path, "rb") as f:
                files = {"file": (os.path.basename(path), f, "application/octet-stream")}
                resp = requests.post(UPLOAD_URL, files=files, timeout=30)
            if resp.status_code == 200:
                with count_lock:
                    success_count += 1
            else:
                with count_lock:
                    fail_count += 1
        except Exception as e:
            with count_lock:
                fail_count += 1

def main():
    print(f"Target: {UPLOAD_URL}")
    print(f"Concurrency: {CONCURRENCY}, Total requests: {TOTAL_REQUESTS}, File size: {FILE_SIZE_KB}KB")

    tmp_files = []
    print("Generating temp files...")
    for i in range(min(CONCURRENCY, TOTAL_REQUESTS)):
        tmp_files.append(generate_temp_file(FILE_SIZE_KB))

    per_worker = TOTAL_REQUESTS // CONCURRENCY
    threads = []

    start = time.time()
    for i in range(CONCURRENCY):
        paths = [tmp_files[i % len(tmp_files)] for _ in range(per_worker)]
        t = threading.Thread(target=worker, args=(paths,))
        threads.append(t)
        t.start()

    for t in threads:
        t.join()

    elapsed = time.time() - start
    print(f"Done in {elapsed:.1f}s | Success: {success_count} | Fail: {fail_count}")

    for path in tmp_files:
        os.remove(path)

if __name__ == "__main__":
    main()
