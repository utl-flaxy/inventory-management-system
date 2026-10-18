"""Controlled in-flight orders and SIGTERM, only on the caller's disposable fixture."""
from concurrent.futures import ThreadPoolExecutor
import http.client
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def http_order(port, product, token, key):
    payload = json.dumps({"items": [{"productId": product, "quantity": 1}]}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/api/v1/me/orders", payload,
        {"Content-Type": "application/json", "Authorization": "Bearer " + token, "Idempotency-Key": key}, method="POST")
    try:
        try:
            response = urllib.request.urlopen(req, timeout=40)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, json.loads(response.read())
    except (urllib.error.URLError, http.client.HTTPException, ConnectionError, TimeoutError):
        return None, None


def new_request_stopped(port):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/livez", timeout=0.5) as response:
            return False
    except urllib.error.HTTPError:
        # A 4xx/5xx is still an HTTP response, not proof that Tomcat stopped accepting work.
        return False
    except (urllib.error.URLError, http.client.HTTPException, ConnectionError, TimeoutError):
        return True


def exercise_shutdown(*, docker, inspect, db_query, restart, app, db, port, token, stop, mode, private_values=None):
    """mode=commit releases the DB blocker after Tomcat stops accepting new work;
    mode=deadline keeps it until the app stops, exposing premature Docker SIGKILL.
    Returns observations; the caller checks the contract, including baseline failures.
    """
    product = int(db_query(db, "INSERT INTO products(name,price,stock,version) VALUES('shutdown-probe',100,1,0); SELECT LAST_INSERT_ID();"))
    key = str(uuid.uuid4())
    if private_values is not None: private_values.append(key)
    session = subprocess.Popen(["docker", "exec", "-i", db, "sh", "-c",
        'MYSQL_PWD="${MARIADB_ROOT_PASSWORD:-$MYSQL_ROOT_PASSWORD}" mariadb -uroot --batch --skip-column-names --unbuffered "$MARIADB_DATABASE"'],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    executor = ThreadPoolExecutor(max_workers=3)
    existing = stopping = None
    released = False
    def release():
        nonlocal released
        if not released:
            session.stdin.write(b"ROLLBACK;\nquit\n"); session.stdin.flush(); session.stdin.close()
            released = True
    try:
        session.stdin.write(f"START TRANSACTION; SELECT id FROM products WHERE id={product} FOR UPDATE; SELECT 'LOCKED';\n".encode())
        session.stdin.flush()
        def locked():
            assert session.stdout.readline().strip() == str(product).encode(), "Disposable row lock observation failed"
            assert session.stdout.readline().strip() == b"LOCKED", "Disposable row lock not acquired"
        executor.submit(locked).result(timeout=5)
        existing = executor.submit(http_order, port, product, token, key)
        deadline = time.monotonic() + 5
        while int(db_query(db, "SELECT COUNT(*) FROM information_schema.INNODB_LOCK_WAITS;")) == 0:
            assert time.monotonic() < deadline, "In-flight order did not reach its independent DB transaction"
            time.sleep(0.05)
        assert not existing.done(), "The request must be in flight when SIGTERM arrives"
        configured_stop_timeout = inspect(app)["Config"].get("StopTimeout")
        started = time.monotonic()
        stopping = executor.submit(stop)
        deadline = time.monotonic() + 5
        while True:
            logs = docker("logs", app).stdout.decode("utf-8", "replace")
            if "Commencing graceful shutdown" in logs:
                break
            assert time.monotonic() < deadline, "Graceful shutdown did not start"
            time.sleep(0.05)
        deadline = time.monotonic() + 3
        while not new_request_stopped(port):
            assert time.monotonic() < deadline, "New HTTP work was still accepted during shutdown"
            time.sleep(0.05)
        if mode == "commit":
            assert not existing.done(), "Existing work must survive while new work is refused"
            release()
        stopping.result(timeout=40)
        elapsed = round(time.monotonic() - started, 3)
        release()
        status, body = existing.result(timeout=5)
        state = inspect(app)["State"]
        log_bytes = docker("logs", app).stdout
        assert token.encode() not in log_bytes and key.encode() not in log_bytes, "Shutdown logs exposed authentication/idempotency data"
        records = []
        for line in log_bytes.decode("utf-8", "replace").splitlines():
            if line.startswith("{"):
                records.append(json.loads(line))
        pool_closed = any(r.get("logger_name") == "com.zaxxer.hikari.HikariDataSource" and "Shutdown completed" in r.get("message", "") for r in records)
        app_connections = int(db_query(db, "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER='inventory_app';"))
        data = db_query(db, f"SELECT stock,version,(SELECT COUNT(*) FROM order_items WHERE product_id={product}),"
                               f"(SELECT COUNT(*) FROM inventory_movements WHERE product_id={product}) FROM products WHERE id={product};")
        report = dict(mode=mode, new_work_stopped=True, existing_was_in_flight=True, http_status=status,
                      configured_stop_timeout_seconds=configured_stop_timeout,
                      problem_code=body.get("code") if isinstance(body, dict) else None,
                      exit_code=state["ExitCode"], oom=state["OOMKilled"], forced_kill=state["ExitCode"] == 137,
                      stop_seconds=elapsed, pool_shutdown_completed=pool_closed, app_db_connections_after_stop=app_connections,
                      business_state=[int(x) for x in data.split()], db_lock_waits_after_stop=int(db_query(db,"SELECT COUNT(*) FROM information_schema.INNODB_LOCK_WAITS;")))
        port = restart()
        retry, result = http_order(port, product, token, key)
        assert retry == 200, "A valid JWT/idempotent order must work after restart"
        assert result["totalPrice"] == 100 and len(result["items"]) == 1
        item = result["items"][0]
        assert (item["productId"], item["productName"], item["unitPrice"], item["quantity"], item["lineTotal"]) == (
            product, "shutdown-probe", 100, 1, 100), "Restart must preserve the order's monetary/product snapshot"
        replay, duplicate = http_order(port, product, token, key)
        assert replay == 200 and result["id"] == duplicate["id"], "Restart must preserve idempotency"
        after = db_query(db, f"SELECT stock,version,(SELECT COUNT(*) FROM order_items WHERE product_id={product}),"
                                f"(SELECT COUNT(*) FROM inventory_movements WHERE product_id={product}) FROM products WHERE id={product};")
        assert [int(x) for x in after.split()] == [0, 1, 1, 1], "Retry must commit exactly one order/movement"
        report["same_key_retry_and_replay_after_restart"] = True
        return report, port
    finally:
        try: release()
        except (BrokenPipeError, OSError): pass
        try: session.wait(timeout=5)
        except subprocess.TimeoutExpired: session.kill(); session.wait()
        executor.shutdown(wait=True, cancel_futures=True)
