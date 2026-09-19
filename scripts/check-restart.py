#!/usr/bin/env python3
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = "http://localhost:8080"
key = str(uuid.uuid4())
body = dict(merchantId="RESTART_TEST", orderId="ORD-RESTART", amount=100.50, currency="EUR", paymentMethod="CARD")
request = urllib.request.Request(BASE + "/api/v1/payments/deposit", data=json.dumps(body).encode(), headers={
    "Content-Type": "application/json", "Idempotency-Key": key, "X-Provider-Scenario": "SOFT_THEN_SUCCESS"
})
with urllib.request.urlopen(request, timeout=10) as response:
    first = json.load(response)
assert first["status"] == "RETRY_SCHEDULED", first
assert first["attemptCount"] == 1, first
subprocess.run(["docker", "compose", "restart", "app"], cwd=ROOT, check=True)
deadline = time.monotonic() + 60
while time.monotonic() < deadline:
    try:
        with urllib.request.urlopen(BASE + "/api/v1/payments/" + first["transactionId"], timeout=2) as response:
            result = json.load(response)
        payment = result["payment"]
        if payment["status"] == "SUCCESS":
            assert payment["transactionId"] == first["transactionId"]
            assert payment["attemptCount"] == 3, payment
            assert len([e for e in result["events"] if e["message"].startswith("Calling provider")]) == 3
            print("PASS: persisted soft decline resumed after application restart; same payment, 3 total attempts.")
            break
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        pass
    time.sleep(1)
else:
    raise AssertionError("Payment did not recover within 60 seconds")
