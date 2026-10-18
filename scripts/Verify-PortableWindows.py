"""Verify the portable Game Price Search package through CMD and bundled Java.

Usage: python scripts/Verify-PortableWindows.py PATH_TO_EXTRACTED_PACKAGE
Stages a copy in a path with spaces and writes evidence under target/.
Exercises local startup, assets, validation, protections, shutdown and restart.
It makes no upstream API requests and does not test browser localStorage.
"""

from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import socket
import subprocess
import time
import uuid


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def verify_manifest(package: Path) -> int:
    """Check every packaged file, including unexpected additions, before launch."""
    entries: dict[str, str] = {}
    for line in (package / "SHA256SUMS.txt").read_text(encoding="ascii").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        require(match is not None, "Invalid SHA256SUMS.txt line")
        digest, name = match.groups()
        parts = PurePosixPath(name).parts
        require(bool(parts) and not PurePosixPath(name).is_absolute()
                and ".." not in parts and "\\" not in name and ":" not in name,
                "Unsafe package manifest path")
        require(name not in entries and name != "SHA256SUMS.txt", "Duplicate or self-referencing manifest entry")
        path = package.joinpath(*parts)
        require(path.is_file() and sha256(path) == digest, f"Package hash mismatch: {name}")
        entries[name] = digest
    actual = {path.relative_to(package).as_posix() for path in package.rglob("*")
              if path.is_file() and path != package / "SHA256SUMS.txt"}
    require(set(entries) == actual, "Manifest does not cover exactly all packaged files")
    return len(entries)


class PortableSmoke:
    def __init__(self, package: Path, evidence: Path):
        self.package = package
        self.launcher = package / "GamePriceSearch.cmd"
        self.evidence = evidence
        self.process: subprocess.Popen | None = None
        self.logs = []
        self.ready = False
        # Release immediately before startup. If another process takes the port,
        # the explicit --port startup must fail rather than choosing a new one.
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            self.port = reservation.getsockname()[1]

    def request(self, path: str, method: str = "GET", extra: dict | None = None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=4)
        try:
            connection.request(method, path, headers=extra or {})
            response = connection.getresponse()
            return response.status, dict((name.lower(), value) for name, value in response.getheaders()), response.read().decode("utf-8")
        finally:
            connection.close()

    def json(self, path: str, method: str = "GET", extra: dict | None = None):
        status, headers, content = self.request(path, method, extra)
        return status, headers, json.loads(content)

    def start(self, label: str) -> None:
        require(self.process is None, "A test process is already running")
        self.ready = False
        log = (self.evidence / f"{label}.log").open("w", encoding="utf-8")
        self.logs.append(log)
        environment = os.environ.copy()
        # Inherited JVM agents/options must not change what the package test runs.
        for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
            environment.pop(name, None)
        environment["GAME_PRICE_SMOKE_LAUNCHER"] = str(self.launcher)
        comspec = environment.get("ComSpec") or str(Path(environment.get("SystemRoot", r"C:\Windows")) / "System32" / "cmd.exe")
        # cmd /s /c requires enclosing quotes around the command. Environment
        # expansion occurs once inside quotes; delayed expansion is disabled.
        command = (subprocess.list2cmdline([comspec]) + ' /d /s /v:off /c '
                   + f'""%GAME_PRICE_SMOKE_LAUNCHER%" --port {self.port} --data-dir "{self.evidence / "watchlist data"}""')
        self.process = subprocess.Popen(command, executable=comspec, cwd=self.evidence,
                                        env=environment, stdout=log, stderr=subprocess.STDOUT,
                                        creationflags=subprocess.CREATE_NO_WINDOW)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise AssertionError(f"Launcher exited during startup; inspect {self.evidence / (label + '.log')}")
            try:
                log_text = (self.evidence / f"{label}.log").read_text(encoding="utf-8", errors="replace")
                if f"Game Price Search: http://127.0.0.1:{self.port}" in log_text:
                    status, _, body = self.json("/api/games/readiness")
                    if status == 200 and body.get("status") == "ready":
                        self.ready = True
                        return
            except OSError:
                pass
            time.sleep(0.15)
        raise AssertionError(f"Portable application startup timed out; inspect {self.evidence}")

    def assert_no_upstream(self) -> None:
        status, _, health = self.json("/api/games/health")
        require(status == 200 and health.get("status") == "ready", "Local initial health is not ready")
        require(health.get("upstreamRequests") == 0 and health.get("upstreamFailures") == 0,
                "Local verification unexpectedly contacted the upstream API")
        require(health.get("lastSuccessAt") is None and health.get("lastFailureAt") is None,
                "Initial health incorrectly reports an upstream observation")

    def stop(self) -> None:
        require(self.process is not None, "No test process is running")
        status, _, body = self.json("/api/shutdown", "POST", {"X-App-Action": "change"})
        require(status == 200 and body.get("stopped") is True, "Shutdown request failed")
        require(self.process.wait(timeout=10) == 0, "Launcher did not propagate successful Java exit")
        self.process = None
        self.ready = False

    def verify(self) -> None:
        self.start("first-start")
        self.assert_no_upstream()
        for path, content_type, marker in (
                ("/", "text/html", "Game Price Search"),
                ("/games.js", "text/javascript", ""),
                ("/games.css", "text/css", "")):
            status, headers, content = self.request(path)
            require(status == 200 and bool(content) and marker in content, f"Asset failed: {path}")
            require(headers.get("content-type", "").startswith(content_type), f"Wrong asset content type: {path}")
            require(headers.get("x-content-type-options") == "nosniff", "Missing nosniff protection")
            require(headers.get("referrer-policy") == "no-referrer", "Missing referrer policy")
            require(headers.get("cache-control") == "no-store", "Missing cache policy")
            require(headers.get("cross-origin-resource-policy") == "same-origin", "Missing resource origin policy")
            policy = headers.get("content-security-policy", "")
            require("default-src 'none'" in policy and "script-src 'self'" in policy
                    and "connect-src 'self'" in policy and "frame-ancestors 'none'" in policy,
                    "Missing application content security policy")
        for path in ("/api/games/search", "/api/games/search?q=", "/api/games/search?q=%20",
                     "/api/games/search?q=Hades&q=Portal", "/api/games/search?q=Hades&extra=1",
                     "/api/games/game?id=invalid", "/api/games/game"):
            require(self.json(path)[0] == 400, f"Invalid input was accepted: {path}")
        for path in ("/documents", "/api/documents", "/api/search?q=linux"):
            require(self.request(path)[0] == 404, f"Unexpected unrelated route: {path}")
        require(self.request("/", "POST")[0] == 405, "POST root was accepted")
        require(self.request("/", "OPTIONS")[0] == 405, "OPTIONS root was accepted")
        require(self.request("/", extra={"Origin": "https://unrelated.example"})[0] == 403,
                "Cross-origin request was accepted")
        require(self.request("/", extra={"Host": f"localhost:{self.port}"})[0] == 403,
                "Wrong Host header was accepted")
        require(self.request("/api/shutdown", "POST")[0] == 403, "Unmarked shutdown was accepted")
        require(self.request("/api/shutdown", "POST", {"X-App-Action": "change", "Origin": "https://unrelated.example"})[0] == 403,
                "Cross-origin marked shutdown was accepted")
        self.assert_no_upstream()
        print("PASS: CMD and bundled runtime from a path with spaces; local readiness/health; assets and protections; input/method/origin validation", flush=True)
        self.stop()
        self.start("restart")
        self.assert_no_upstream()
        self.stop()
        print("PASS: clean shutdown and same-port restart; no upstream requests were made", flush=True)

    def cleanup(self) -> None:
        if self.process is not None and self.process.poll() is None:
            try:
                if self.ready:
                    self.json("/api/shutdown", "POST", {"X-App-Action": "change"})
                    self.process.wait(timeout=8)
            except (OSError, ValueError, subprocess.TimeoutExpired):
                pass
            if self.process.poll() is None:
                # Only the still-running CMD process created here and its own
                # Java child are eligible for fallback cleanup.
                taskkill = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "taskkill.exe"
                result = subprocess.run([str(taskkill), "/PID", str(self.process.pid), "/T", "/F"],
                                        capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW,
                                        timeout=10, check=False)
                (self.evidence / "cleanup.log").write_text(result.stdout + result.stderr, encoding="utf-8")
                self.process.wait(timeout=5)
        for log in self.logs:
            log.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package_directory", type=Path, help="Extracted folder containing GamePriceSearch.cmd, app and runtime")
    arguments = parser.parse_args()
    if os.name != "nt":
        parser.error("This verifies the Windows CMD launcher and must run on Windows")
    # Lexical abspath avoids optional GetFinalPathNameByHandle directory queries,
    # which can be denied by restricted Windows accounts despite ordinary access.
    source = Path(os.path.abspath(arguments.package_directory))
    for name in ("GamePriceSearch.cmd", "runtime/bin/java.exe", "runtime/bin/javaw.exe", "SHA256SUMS.txt"):
        if not (source / name).is_file():
            parser.error(f"Missing package file: {source / name}")
    jars = list((source / "app").glob("*.jar"))
    if len(jars) != 1 or jars[0].name != "game-price-search-1.0.0.jar":
        parser.error("The package must contain only app/game-price-search-1.0.0.jar")
    project = Path(os.path.abspath(__file__)).parent.parent
    evidence = project / "target" / ("package smoke with spaces " + uuid.uuid4().hex)
    if evidence.is_relative_to(source):
        parser.error("The package cannot contain this repository")
    evidence.mkdir(parents=True)
    package = evidence / "package with spaces" / "GamePriceSearch"
    print("Evidence:", evidence, flush=True)
    shutil.copytree(source, package)
    smoke = PortableSmoke(package, evidence)
    result = {"passed": False, "sourcePackage": str(source), "stagedPackage": str(package),
              "workingDirectory": str(evidence), "launcher": "GamePriceSearch.cmd",
              "externalApiWorkflowTested": False, "browserStorageTested": False}
    try:
        result["manifestFiles"] = verify_manifest(package)
        print(f"PASS: {result['manifestFiles']} packaged file hashes verified", flush=True)
        smoke.verify()
        result["passed"] = True
        result["checks"] = ["full file manifest", "CMD launcher", "bundled Java", "package and cwd paths with spaces",
                            "external working directory", "local readiness", "initial health without upstream observation",
                            "HTML CSS JS assets", "security headers", "input validation", "method validation",
                            "Host and Origin validation", "marked shutdown", "clean shutdown", "same-port restart"]
    except Exception as error:
        result["error"] = str(error)
        raise
    finally:
        try:
            smoke.cleanup()
        finally:
            (evidence / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
