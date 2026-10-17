"""Real Docker regression using exclusively newly named containers and tmpfs DB storage.

Reports are value-free. No production environment, Docker socket mount, existing
volume, AWS, deployment workflow or global Docker cleanup is used.
"""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import http.client
import io
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import uuid

DB_IMAGE = "mariadb:12.2.2@sha256:6a4c4bbf0447c2247801309513fcc0dd17c3f35390368139ce419387fdd1144d"
SCANNER_IMAGE = "aquasec/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa"
private_values = []


def redact(text):
    for value in private_values:
        text = text.replace(value, "[REDACTED]")
    return text


def command(args, *, input=None, check=True, log=None):
    result = subprocess.run(args, input=input, capture_output=True)
    if log:
        Path(log).write_text(redact((result.stdout + result.stderr).decode("utf-8", "replace")), encoding="utf-8")
    if check and result.returncode:
        raise RuntimeError("Tool failed: " + args[0] + "; exit=" + str(result.returncode))
    return result


def docker(*args, **kwargs):
    return command(["docker", *map(str, args)], **kwargs)


def inspect(name):
    return json.loads(docker("inspect", name).stdout)[0]


def wait_db(name):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if docker("exec", name, "healthcheck.sh", "--connect", "--innodb_initialized", check=False).returncode == 0:
            return
        time.sleep(2)
    raise RuntimeError("Disposable DB health timeout")


def request(port, path, expected=200, *, method="GET", data=None, token=None, extra=None):
    # This fixture acts as a trusted edge; production Caddy overwrites client claims.
    headers = {"Content-Type": "application/json", "X-Forwarded-For": "198.51.100.10", **(extra or {})}
    if token:
        headers["Authorization"] = "Bearer " + token
    payload = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", payload, headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status, response_headers, body = response.status, response.headers, response.read()
    assert status == expected, f"HTTP contract failed: {method} {path}; expected={expected}, actual={status}"
    return json.loads(body) if body else None, response_headers


def discover_transport_peer(root, temp, network, nonce, created):
    """Observe this disposable host-published path, including Docker Desktop NAT, without app IP logging."""
    source = temp / "PeerProbe.java"
    source.write_text(
        'import com.sun.net.httpserver.HttpServer; import java.net.InetSocketAddress;\n'
        'class PeerProbe { public static void main(String[] args) throws Exception {\n'
        ' var server = HttpServer.create(new InetSocketAddress(8080), 0);\n'
        ' server.createContext("/", exchange -> {\n'
        '  byte[] peer = exchange.getRemoteAddress().getAddress().getHostAddress().getBytes(java.nio.charset.StandardCharsets.US_ASCII);\n'
        '  exchange.sendResponseHeaders(200, peer.length);\n'
        '  try (var out = exchange.getResponseBody()) { out.write(peer); }\n'
        ' }); server.start(); } }\n', encoding="utf-8")
    build_image = re.search(r"^FROM (\S+) AS build$", (root / "Dockerfile").read_text(encoding="utf-8"), re.M).group(1)
    name = "inventory76-peer-" + nonce
    created.append(("container", name))
    docker("run", "-d", "--name", name, "--network", network,
           "--label", f"inventory.issue76={nonce}", "--publish", "127.0.0.1::8080",
           "--read-only", "--user", "10001:10001", "--cap-drop=ALL", "--security-opt=no-new-privileges:true",
           "--pids-limit=64", "--memory=256m", "--memory-swap=256m",
           "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m,mode=1777", "--no-healthcheck",
           "--mount", f"type=bind,source={source},target=/probe/PeerProbe.java,readonly",
           "--entrypoint", "java", build_image, "-Xmx96m", "--add-modules", "jdk.httpserver", "/probe/PeerProbe.java")
    port = int(inspect(name)["NetworkSettings"]["Ports"]["8080/tcp"][0]["HostPort"])
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=2) as response:
                peer = response.read(64).decode("ascii")
            import ipaddress
            return str(ipaddress.ip_address(peer))
        except (urllib.error.URLError, http.client.HTTPException, ConnectionError, TimeoutError):
            time.sleep(1)
    raise RuntimeError("Disposable transport peer observation failed")


def memory(name):
    result = docker("exec", name, "sh", "-c",
                    "cat /sys/fs/cgroup/memory.current; cat /sys/fs/cgroup/memory.peak; grep -E '^(VmRSS|Threads):' /proc/1/status", check=False)
    text = result.stdout.decode("utf-8", "replace")
    values = re.findall(r"^(\d+)\r?$", text, re.M)
    rss = re.search(r"VmRSS:\s*(\d+)", text)
    threads = re.search(r"Threads:\s*(\d+)", text)
    return dict(cgroup_bytes=int(values[0]) if values else None,
                cgroup_peak_bytes=int(values[1]) if len(values) > 1 else None,
                rss_bytes=int(rss[1]) * 1024 if rss else None,
                threads=int(threads[1]) if threads else None)


def wait_app(name, *, ready=True, timeout=180):
    deadline = time.monotonic() + timeout
    samples = []
    while time.monotonic() < deadline:
        info = inspect(name)
        if info["State"]["Running"]:
            samples.append(memory(name))
            binding = info["NetworkSettings"]["Ports"].get("8080/tcp")
            if binding:
                port = int(binding[0]["HostPort"])
                try:
                    body, _ = request(port, "/readyz")
                    assert body == {"status": "UP"}
                    if not ready:
                        raise RuntimeError("Broken DB target unexpectedly became ready")
                    return port, samples
                except (urllib.error.URLError, http.client.HTTPException, ConnectionError, TimeoutError, AssertionError):
                    if not ready and info["State"].get("ExitCode"):
                        return None, samples
        elif not ready:
            return None, samples
        time.sleep(2)
    if not ready:
        return None, samples
    raise RuntimeError("Disposable app readiness timeout")


def db_query(name, sql):
    return docker("exec", "-i", name, "sh", "-c",
                  'MYSQL_PWD="$MARIADB_ROOT_PASSWORD" mariadb --user=root --batch --skip-column-names "$MARIADB_DATABASE"',
                  input=sql.encode()).stdout.decode().strip()


def provision_admin(image, network, db, username, database_password, password):
    # A separate, bounded non-root CLI process: no app/JWT environment, secret argv, file or published port.
    private_values.extend([database_password, password])
    payload = bytearray((database_password + "\n" + password + "\n" + password + "\n").encode())
    try:
        result = docker("run", "--rm", "-i", "--network", network, "--user", "10001:10001",
                        "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges:true",
                        "--pids-limit=64", "--memory=256m", "--memory-swap=256m", "--ulimit", "core=0",
                        "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m,mode=1777", "--no-healthcheck",
                        "--env", "PROVISION_JDBC_URL=jdbc:mariadb://db:3306/inventory76",
                        "--env", "PROVISION_DB_USER=bootstrap_operator",
                        "--env", "PROVISION_ADMIN_USERNAME=" + username,
                        "--entrypoint", "java", image,
                        "-Dloader.main=com.example.demo.provisioning.InitialAdminCommand",
                        "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher",
                        "--passwords-stdin", input=payload, check=False)
    finally:
        payload[:] = b"\0" * len(payload)
    text = (result.stdout + result.stderr).decode("utf-8", "replace")
    assert all(value not in text for value in private_values), "Offline command leaked a credential"
    assert not re.search(r"\$2[aby]\$\d{2}\$[A-Za-z0-9./]{53}", text), "Offline command leaked a password hash"
    status = re.findall(r"^initial-admin: ([A-Z_]+)\r?$", text, re.M)
    assert len(status) == 1, "Offline command must emit exactly one value-free status"
    return result.returncode, status[0]


def api_regression(port, db, image, network):
    checks = []
    password = secrets.token_urlsafe(18)
    private_values.append(password)
    for username in ["docker_user", "admin"]:
        body, _ = request(port, "/auth/register", method="POST", data={"username": username, "password": password, "role": "ROLE_ADMIN"})
        assert body["role"] == "ROLE_USER" and "password" not in body
    assert db_query(db, "SELECT COUNT(*) FROM users WHERE role='ROLE_ADMIN';") == "0"
    operator_secret = secrets.token_hex(24)
    private_values.append(operator_secret)
    # Test-generated hex credential travels only as SQL stdin to this disposable DB.
    db_query(db, "CREATE USER 'bootstrap_operator'@'%' IDENTIFIED BY '" + operator_secret + "'; "
             "GRANT SELECT (username,role), INSERT (username,password,role) ON inventory76.users TO 'bootstrap_operator'@'%';")
    # Different names and independent CLI processes prove cross-process serialization, not just username UNIQUE.
    names = ["docker_admin_" + str(i) for i in range(3)]
    with ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(lambda name: provision_admin(image, network, db, name, operator_secret, password), names))
    assert results.count((0, "CREATED")) == 1 and results.count((0, "ADMIN_PRESENT")) == 2
    administrator = names[results.index((0, "CREATED"))]
    before = db_query(db, "SELECT id,username,password,role FROM users WHERE role='ROLE_ADMIN';")
    stored_hash = before.split("\t")[2]
    private_values.append(stored_hash)
    assert stored_hash.startswith("$2a$10$")
    assert provision_admin(image, network, db, "another_operator", operator_secret, secrets.token_urlsafe(18)) == (0, "ADMIN_PRESENT")
    assert db_query(db, "SELECT id,username,password,role FROM users WHERE role='ROLE_ADMIN';") == before
    wrong_password = secrets.token_hex(24)
    private_values.append(wrong_password)
    assert provision_admin(image, network, db, "another_operator", wrong_password, password) == (74, "DATABASE_FAILED")
    assert db_query(db, "SELECT COUNT(*) FROM users WHERE role='ROLE_ADMIN';") == "1"
    checks.append("Offline initial ADMIN: 3 concurrent processes, one insert, least-privilege DB account, rerun unchanged, failed credentials redacted")
    tokens = {}
    for username in ["docker_user", administrator]:
        body, _ = request(port, "/auth/login", method="POST", data={"username": username, "password": password})
        tokens["docker_admin" if username == administrator else username] = body["token"]
        private_values.append(body["token"])
    user, admin = tokens["docker_user"], tokens["docker_admin"]
    for _ in range(5):
        request(port, "/auth/login", 401, method="POST",
                data={"username": "docker_user", "password": "wrong-disposable-password"},
                extra={"X-Forwarded-For": "198.51.100.66"})
    limited, retry = request(port, "/auth/login", 429, method="POST",
                             data={"username": "docker_user", "password": password},
                             extra={"X-Forwarded-For": "198.51.100.66"})
    assert limited["code"] == "LOGIN_RATE_LIMITED" and int(retry["Retry-After"]) >= 1
    other, _ = request(port, "/auth/login", method="POST", data={"username": "docker_user", "password": password},
                       extra={"X-Forwarded-For": "198.51.100.67"})
    private_values.append(other["token"])
    unavailable, _ = request(port, "/auth/login", 503, method="POST",
                              data={"username": "docker_user", "password": password},
                              extra={"Forwarded": "for=198.51.100.68"})
    assert unavailable["code"] == "LOGIN_SOURCE_UNAVAILABLE"
    checks.append("Verified disposable transport peer, distinct forwarded clients, attacker 401/429 Retry-After, victim 200, malformed proxy 503")
    registration_source = {"X-Forwarded-For": "198.51.100.70"}
    for _ in range(5):
        request(port, "/auth/register", 409, method="POST",
                data={"username": "docker_user", "password": password}, extra=registration_source)
    denied, retry = request(port, "/auth/register", 429, method="POST",
                            data={"username": "should_not_exist", "password": password}, extra=registration_source)
    assert denied["code"] == "REGISTRATION_RATE_LIMITED" and int(retry["Retry-After"]) >= 1
    assert db_query(db, "SELECT COUNT(*) FROM users WHERE username='should_not_exist';") == "0"
    request(port, "/auth/register", 409, method="POST", data={"username": "docker_user", "password": password},
            extra={"X-Forwarded-For": "198.51.100.71"})
    independent, _ = request(port, "/auth/login", method="POST", data={"username": "docker_user", "password": password},
                             extra=registration_source)
    private_values.append(independent["token"])
    unavailable, _ = request(port, "/auth/register", 503, method="POST",
                              data={"username": "should_not_exist", "password": password},
                              extra={"Forwarded": "for=198.51.100.72"})
    assert unavailable["code"] == "REGISTRATION_SOURCE_UNAVAILABLE"
    checks.append("Registration: five admitted duplicate failures, 429 Retry-After before insert, independent client and login, malformed proxy 503")
    request(port, "/products", 401)
    request(port, "/products", 401, token="invalid-docker-test-token")
    request(port, "/api/v1/admin/orders", 403, token=user)
    request(port, "/products", 403, method="POST", token=user, data={"name": "fixture", "price": 100, "stock": 10})
    checks.append("JWT signature verification and USER/ADMIN authorization")
    product, headers = request(port, "/products", method="POST", token=admin,
                               data={"name": "docker-fixture", "price": 100, "stock": 10})
    assert headers.get("X-Content-Type-Options") == "nosniff" and headers.get("X-Frame-Options") == "DENY"
    pid = product["id"]
    order, _ = request(port, "/api/v1/me/orders", method="POST", token=user,
                       data={"items": [{"productId": pid, "quantity": 2}]},
                       extra={"Idempotency-Key": "docker-order-1"})
    repeated, _ = request(port, "/api/v1/me/orders", method="POST", token=user,
                          data={"items": [{"productId": pid, "quantity": 2}]},
                          extra={"Idempotency-Key": "docker-order-1"})
    assert order["id"] == repeated["id"] and order["totalPrice"] == 200
    actual, _ = request(port, f"/products/{pid}", token=user)
    assert actual["stock"] == 8
    order_state_query = ("SELECT (SELECT COUNT(*) FROM orders), (SELECT COUNT(*) FROM order_items), "
                         "(SELECT COUNT(*) FROM inventory_movements), "
                         "(SELECT SUM(stock) FROM products), (SELECT SUM(version) FROM products);")
    before_oversize = db_query(db, order_state_query)
    oversized, _ = request(port, "/api/v1/me/orders", 400, method="POST", token=user,
                           data={"items": [{"productId": pid, "quantity": 1}] * 101},
                           extra={"Idempotency-Key": "docker-order-1"})
    assert oversized["code"] == "VALIDATION_ERROR" and "items" in oversized["errors"]
    assert db_query(db, order_state_query) == before_oversize
    retry, _ = request(port, "/api/v1/me/orders", method="POST", token=user,
                       data={"items": [{"productId": pid, "quantity": 2}]},
                       extra={"Idempotency-Key": "docker-order-1"})
    assert retry["id"] == order["id"] and db_query(db, order_state_query) == before_oversize
    checks.append("Order item limit: 101 entries rejected with validation ProblemDetail, DB counts/stock/version unchanged, original key retry preserved")
    adjustment, _ = request(port, f"/api/v1/admin/products/{pid}/inventory-adjustments", method="POST", token=admin,
                            data={"delta": 3, "reason": "RECEIPT"},
                            extra={"Idempotency-Key": "docker-inventory-1"})
    assert adjustment["delta"] == 3
    actual, _ = request(port, f"/products/{pid}", token=user)
    assert actual["stock"] == 11
    request(port, f"/api/v1/me/orders/{order['id']}/cancel", method="POST", token=user)
    actual, _ = request(port, f"/products/{pid}", token=user)
    assert actual["stock"] == 13
    checks.append("Order idempotency, stock decrement, inventory adjustment, cancellation transaction")
    error, _ = request(port, "/products", 400, method="POST", token=admin,
                       data={"name": "", "price": 0, "stock": -1})
    assert isinstance(error, dict) and "password" not in error
    request(port, "/v3/api-docs", 404, token=user)
    request(port, "/h2-console", 404, token=user)
    checks.extend(["Security headers", "Validation error response", "Production-only endpoint isolation"])
    return checks, tokens, password, pid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="target/docker-validation")
    parser.add_argument("--image", help="Test an already built development image; build evidence recorded separately")
    parser.add_argument("--baseline-image")
    parser.add_argument("--boot4-image")
    parser.add_argument("--scan", action="store_true")
    parser.add_argument("--capacity-probes", action="store_true", help="Record constrained startup and deliberate heap OOM separately")
    args = parser.parse_args()
    root, output = Path.cwd(), Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    revision = command(["git", "rev-parse", "HEAD"]).stdout.decode().strip()
    epoch = command(["git", "show", "-s", "--format=%ct", "HEAD"]).stdout.decode().strip()
    nonce = uuid.uuid4().hex[:12]
    project, app, db, network = [f"inventory76-{nonce}{suffix}" for suffix in ["", "-app", "-db", "-net"]]
    image = args.image or f"inventory-issue76:{nonce}"
    report = dict(source_revision=revision, source_dirty=bool(command(["git", "status", "--porcelain"]).stdout),
                  platform="linux/amd64", production_operations=False, existing_volumes_used=False,
                  image=image, clean_archive_build=not bool(args.image), checks=[])
    created = []
    with tempfile.TemporaryDirectory(prefix="inventory76-") as temporary:
        temp = Path(temporary)
        try:
            if not args.image:
                context = temp / "context"
                context.mkdir()
                archive = command(["git", "archive", "--format=tar", "HEAD"]).stdout
                with tarfile.open(fileobj=io.BytesIO(archive)) as source:
                    source.extractall(context, filter="data")
                assert not (context / "target").exists()
                started = time.monotonic()
                docker("build", "--progress=plain", "--build-arg", f"REVISION={revision}", "--build-arg",
                       f"SOURCE_DATE_EPOCH={epoch}", "-t", image, context, log=output / "build.log")
                report["build_seconds"] = round(time.monotonic() - started, 2)
            info = inspect(image)
            report.update(image_id=info["Id"], image_bytes=info["Size"], runtime_user=info["Config"]["User"],
                          revision_label=info["Config"]["Labels"]["org.opencontainers.image.revision"])
            assert info["Config"]["User"] == "10001:10001"
            if not args.image:
                assert report["revision_label"] == revision
            identity = docker("run", "--rm", "--entrypoint", "sh", image, "-c",
                              'id -u; id -g; test ! -w /app; test ! -w /app/app.jar; '
                              '! command -v javac; ! command -v mvn; ! command -v jcmd').stdout.decode().splitlines()
            assert identity[:2] == ["10001", "10001"]
            report["identity"] = {"uid": 10001, "gid": 10001, "app_and_jar_not_writable": True,
                                  "jdk_maven_jcmd_absent": True}
            version = docker("run", "--rm", "--memory=1g", "--entrypoint", "java", image, "-version")
            report["java_version"] = (version.stdout + version.stderr).decode().strip()
            assert 'version "17.' in report["java_version"]
            flags = docker("run", "--rm", "--memory=1g", "--entrypoint", "java", image,
                           "-XX:+PrintFlagsFinal", "-version").stdout.decode()
            report["jvm_flags_at_1g"] = {}
            for flag in ["MaxHeapSize", "MaxMetaspaceSize", "MaxDirectMemorySize", "ReservedCodeCacheSize",
                         "ThreadStackSize", "ExitOnOutOfMemoryError"]:
                match = re.search(r"\b" + flag + r"\s+[:=]+\s+(\S+)", flags)
                assert match, "Missing effective JVM memory flag: " + flag
                report["jvm_flags_at_1g"][flag] = match[1]
            assert int(report["jvm_flags_at_1g"]["MaxHeapSize"]) <= 300 * 1024 * 1024
            assert report["jvm_flags_at_1g"]["ExitOnOutOfMemoryError"] == "true"
            docker("network", "create", "--label", f"inventory.issue76={nonce}", network)
            created.append(("network", network))
            database_env = dict(MARIADB_ROOT_PASSWORD=secrets.token_hex(24), MARIADB_DATABASE="inventory76",
                                MARIADB_USER="inventory_app", MARIADB_PASSWORD=secrets.token_hex(24))
            private_values.extend([database_env["MARIADB_ROOT_PASSWORD"], database_env["MARIADB_PASSWORD"]])
            db_env = temp / "db.env"
            db_env.write_text("".join(f"{k}={v}\n" for k, v in database_env.items()), encoding="utf-8")
            db_env.chmod(0o600)
            created.append(("container", db))
            docker("run", "-d", "--name", db, "--network", network, "--network-alias", "db",
                   "--label", f"inventory.issue76={nonce}", "--memory=512m", "--memory-swap=512m",
                   "--tmpfs", "/var/lib/mysql:rw,nosuid,size=256m", "--env-file", db_env,
                   "--mount", f"type=bind,source={root / 'scripts/db-init/01-app-privileges.sh'},target=/docker-entrypoint-initdb.d/01-app-privileges.sh,readonly",
                   DB_IMAGE, "--innodb-buffer-pool-size=128M", "--max-connections=50", "--performance-schema=OFF")
            wait_db(db)
            db_before = inspect(db)
            assert all(mount["Type"] != "volume" for mount in db_before["Mounts"])
            app_env = temp / "app.env"
            app_settings = dict(SPRING_PROFILES_ACTIVE="prod",
                                SPRING_DATASOURCE_URL="jdbc:mariadb://db:3306/inventory76",
                                SPRING_DATASOURCE_USERNAME="inventory_app",
                                SPRING_DATASOURCE_PASSWORD=database_env["MARIADB_PASSWORD"],
                                JWT_SECRET=secrets.token_hex(32))
            app_settings["APP_LOGIN_RATE_LIMIT_TRUSTED_PROXIES"] = discover_transport_peer(
                root, temp, network, nonce, created)
            private_values.append(app_settings["APP_LOGIN_RATE_LIMIT_TRUSTED_PROXIES"])
            report["login_client_identity"] = {"observed_disposable_peer": True, "trusted_peer_count": 1,
                                               "production_peer_verified": False}
            private_values.append(app_settings["JWT_SECRET"])
            app_env.write_text("".join(f"{k}={v}\n" for k, v in app_settings.items()), encoding="utf-8")
            app_env.chmod(0o600)
            def compose_file(path, selected_image, *, hardened=True, broken=False, limit="1g"):
                service = dict(image=selected_image, container_name=app, env_file=[str(app_env)],
                               ports=["127.0.0.1::8080"], mem_limit=limit, memswap_limit=limit,
                               labels={"inventory.issue76": nonce},
                               networks={"disposable": None})
                if hardened:
                    service.update(user="10001:10001", read_only=True, cap_drop=["ALL"],
                                   security_opt=["no-new-privileges:true"], pids_limit=256,
                                   tmpfs=["/tmp:rw,noexec,nosuid,size=32m,mode=1777"])
                if broken:
                    # Guaranteed container-local failure; never resolve an external DB hostname.
                    service["environment"] = {"SPRING_DATASOURCE_URL": "jdbc:mariadb://127.0.0.1:1/inventory76",
                                              "SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT": "2500"}
                path.write_text(json.dumps({"services": {"app": service},
                                           "networks": {"disposable": {"external": True, "name": network}}}), encoding="utf-8")
                path.chmod(0o600)
            def cutover(path):
                docker("compose", "-p", project, "-f", path, "up", "-d", "--no-deps", "--no-build",
                       "--pull", "never", "--force-recreate", "app")
            created.append(("container", app))
            candidate = temp / "candidate.json"
            compose_file(candidate, info["Id"])
            cutover(candidate)
            port, samples = wait_app(app)
            report["checks"].append("JRE startup and /readyz=200 with status only")
            assert inspect(app)["HostConfig"]["ReadonlyRootfs"] is True
            report["checks"].append("Read-only rootfs, non-root, dropped capabilities, bounded tmpfs")
            report["api_checks"], tokens, password, pid = api_regression(port, db, image, network)
            migrations = db_query(db, "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank;").splitlines()
            assert migrations == ["1", "2", "3", "4", "5", "6"]
            report["migrations"] = migrations
            report["app_startup_peak_cgroup_bytes"] = max((s["cgroup_bytes"] or 0) for s in samples)
            report["app_after_api"] = memory(app)
            report["db_after_api"] = memory(db)
            report["checks"].append("MariaDB connection and unchanged Flyway V1-V6")
            data_signature = db_query(db, "SELECT (SELECT COUNT(*) FROM orders), (SELECT SUM(stock) FROM products);")
            admin_signature = db_query(db, "SELECT id,username,password,role FROM users WHERE role='ROLE_ADMIN';")
            previous = temp / "previous.json"
            old_image = args.baseline_image or info["Id"]
            compose_file(previous, inspect(old_image)["Id"], hardened=not bool(args.baseline_image))
            cutover(previous)
            wait_app(app)
            cutover(candidate)
            wait_app(app)
            broken = temp / "broken.json"
            compose_file(broken, info["Id"], broken=True)
            cutover(broken)
            wait_app(app, ready=False, timeout=15)
            cutover(previous)
            port, _ = wait_app(app)
            assert inspect(app)["Image"] == inspect(old_image)["Id"]
            db_after = inspect(db)
            assert db_after["Id"] == db_before["Id"] and db_after["State"]["StartedAt"] == db_before["State"]["StartedAt"]
            assert db_query(db, "SELECT (SELECT COUNT(*) FROM orders), (SELECT SUM(stock) FROM products);") == data_signature
            assert db_query(db, "SELECT id,username,password,role FROM users WHERE role='ROLE_ADMIN';") == admin_signature
            report["initial_admin_provisioning"] = dict(concurrent_cli_processes=3, created_admins=1,
                    existing_admin_result="ADMIN_PRESENT", db_permissions=["SELECT", "INSERT"],
                    db_select_columns=["username", "role"], db_insert_columns=["username", "password", "role"],
                    bad_db_credentials_exit=74, credentials_not_in_command_output=True,
                    identity_hash_and_role_unchanged_after_app_restarts=True, default_startup_created_admins=0)
            request(port, f"/products/{pid}", token=tokens["docker_user"])
            report["rollback"] = {"actual_app_only_cutover": True, "failed_readiness_then_old_image_restored": True,
                                  "failure_target": "container loopback 127.0.0.1:1",
                                  "db_id_and_start_time_unchanged": True, "order_and_stock_data_unchanged": True,
                                  "previous_image_id": inspect(old_image)["Id"],
                                  "legacy_jdk_root_image": bool(args.baseline_image)}
            if args.baseline_image:
                report["baseline_image_bytes"] = inspect(args.baseline_image)["Size"]
            if args.capacity_probes:
                report["capacity_probes"] = []
                for limit in ["384m", "512m"]:
                    small = temp / ("capacity-" + limit + ".json")
                    compose_file(small, info["Id"], limit=limit)
                    cutover(small)
                    try:
                        small_port, _ = wait_app(app, timeout=60)
                        request(small_port, f"/products/{pid}", token=tokens["docker_user"])
                        outcome = {"limit": limit, "startup": "READY", "memory": memory(app),
                                   "sustained_load_validated": False}
                    except (RuntimeError, AssertionError, urllib.error.URLError):
                        state = inspect(app)["State"]
                        outcome = {"limit": limit, "startup": "FAILED", "exit": state["ExitCode"],
                                   "oom_killed": state["OOMKilled"], "sustained_load_validated": False}
                    report["capacity_probes"].append(outcome)
                oom = temp / "heap-oom.json"
                compose_file(oom, info["Id"], limit="512m")
                settings = json.loads(oom.read_text(encoding="utf-8"))
                settings["services"]["app"]["environment"] = {
                    "JAVA_TOOL_OPTIONS": "-Xmx16m -XX:+ExitOnOutOfMemoryError"}
                oom.write_text(json.dumps(settings), encoding="utf-8")
                cutover(oom)
                deadline = time.monotonic() + 60
                while inspect(app)["State"]["Running"] and time.monotonic() < deadline:
                    time.sleep(1)
                state = inspect(app)["State"]
                assert not state["Running"] and state["ExitCode"] != 0, "Heap OOM must terminate the app"
                diagnostic = docker("logs", app)
                text = (diagnostic.stdout + diagnostic.stderr).decode("utf-8", "replace")
                assert "OutOfMemoryError" in text, "Failure must be verified as actual heap OOM"
                report["heap_oom_probe"] = {"exit": state["ExitCode"], "actual_heap_oom": True,
                                            "rootfs_remains_read_only": True, "heap_dump_enabled": False}
                cutover(candidate)
                wait_app(app)
            if args.boot4_image:
                future = temp / "boot4.json"
                compose_file(future, inspect(args.boot4_image)["Id"])
                cutover(future)
                port, future_samples = wait_app(app)
                body, _ = request(port, "/auth/login", method="POST",
                                  data={"username": "docker_user", "password": password})
                private_values.append(body["token"])
                request(port, f"/products/{pid}", token=body["token"])
                request(port, "/api/v1/admin/orders", 403, token=body["token"])
                assert db_query(db, "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank;").splitlines() == migrations
                report["boot4_runtime_poc"] = dict(readiness=True, jwt=True, user_authorization=True,
                    migrations_unchanged=True, image_id=inspect(args.boot4_image)["Id"],
                    startup_peak_cgroup_bytes=max((s["cgroup_bytes"] or 0) for s in future_samples))
            if args.scan:
                image_archive = output / "image.tar"
                docker("save", "-o", image_archive, info["Id"])
                scan = docker("run", "--rm", "--mount", f"type=bind,source={output},target=/scan,readonly",
                              SCANNER_IMAGE, "image", "--scanners", "vuln", "--format", "json", "--exit-code", "0",
                              "--db-repository", "mirror.gcr.io/aquasec/trivy-db:2",
                              "--db-repository", "ghcr.io/aquasecurity/trivy-db:2",
                              "--java-db-repository", "mirror.gcr.io/aquasec/trivy-java-db:1",
                              "--java-db-repository", "ghcr.io/aquasecurity/trivy-java-db:1",
                              "--input", "/scan/image.tar", log=output / "image-scan-output.log")
                document = json.loads(scan.stdout)
                (output / "image-scan.json").write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
                findings = [v for result in document.get("Results", []) for v in result.get("Vulnerabilities", [])]
                report["image_scan"] = dict(mode="AUDIT_ONLY_NOT_A_SECURITY_PASS", scanner_image=SCANNER_IMAGE,
                                            scanner_exit=scan.returncode, findings=len(findings),
                                            severity=dict(Counter(v["Severity"] for v in findings)),
                                            os_findings=sum(len(x.get("Vulnerabilities", [])) for x in document.get("Results", []) if x.get("Class") == "os-pkgs"),
                                            library_findings=sum(len(x.get("Vulnerabilities", [])) for x in document.get("Results", []) if x.get("Class") == "lang-pkgs"))
                image_archive.unlink()
            report["result"] = "PASS"
        except Exception as error:
            report["result"], report["failure"] = "FAIL", redact(str(error))
            raise
        finally:
            for name in [app, db]:
                docker("logs", "--tail", "200", name, check=False, log=output / (name + ".log"))
            # Ownership check before deleting only resources created by this invocation.
            for kind, name in reversed(created):
                result = docker("inspect" if kind == "container" else "network", *(["inspect"] if kind == "network" else []), name, check=False)
                if result.returncode == 0:
                    observed = json.loads(result.stdout)[0]
                    labels = observed.get("Config", {}).get("Labels", {}) if kind == "container" else observed.get("Labels", {})
                    assert labels.get("inventory.issue76") == nonce, "Refusing cleanup of an unowned resource"
                    docker("rm" if kind == "container" else "network", *(["-f"] if kind == "container" else ["rm"]), name)
            (output / "summary.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"result": report["result"], "uid": 10001, "gid": 10001,
                      "image_bytes": report["image_bytes"], "checks": report["checks"], "migrations": report["migrations"],
                      "rollback": report["rollback"], "image_scan": report.get("image_scan")}))


if __name__ == "__main__":
    main()
