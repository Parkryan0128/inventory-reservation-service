"""Exercise the production stack over HTTPS on the disposable CI runner."""
import http.client
import http.cookies
import json
import socket
import ssl
import sys
import time

TLS = ssl.create_default_context(cafile=sys.argv[1])


class LocalConnection(http.client.HTTPSConnection):
    def connect(self):
        # Keep certificate/hostname verification, but route this CI hostname locally.
        self.sock = TLS.wrap_socket(
            socket.create_connection(("127.0.0.1", 8443), timeout=10),
            server_hostname="inventory.test",
        )


class Browser:
    def __init__(self):
        self.cookies = http.cookies.SimpleCookie()
        self.csrf = {}

    def request(self, path, method="GET", data=None, expected=200, csrf=True, host=None):
        connection = LocalConnection("inventory.test", 8443, context=TLS, timeout=10)
        headers = {"Cookie": "; ".join(f"{key}={value.value}" for key, value in self.cookies.items())}
        if csrf:
            headers.update(self.csrf)
        if host:
            headers["Host"] = host
        payload = None
        if data is not None:
            payload = json.dumps(data)
            headers["Content-Type"] = "application/json"
        connection.request(method, path, payload, headers)
        response = connection.getresponse()
        body = response.read()
        status = response.status
        content_type = response.getheader("Content-Type", "")
        for key, value in response.getheaders():
            if key.lower() == "set-cookie":
                cookie = http.cookies.SimpleCookie(value)
                if "JSESSIONID" in cookie:
                    assert cookie["JSESSIONID"]["secure"], "Missing Secure on public session cookie"
                    assert cookie["JSESSIONID"]["httponly"], "Missing HttpOnly on session cookie"
                    assert cookie["JSESSIONID"]["samesite"].lower() == "strict"
                self.cookies.load(value)
        connection.close()
        assert status == expected, f"{method} {path}: expected {expected}, got {status}: {body[:300]!r}"
        return json.loads(body) if "json" in content_type and body else body

    def start(self):
        token = self.request("/api/csrf")
        assert "JSESSIONID" in self.cookies, "CSRF endpoint did not establish a browser session"
        self.csrf = {token["headerName"]: token["token"]}


browser = Browser()
for attempt in range(30):
    try:
        assert browser.request("/actuator/health")["status"] == "UP"
        break
    except (OSError, AssertionError):
        if attempt == 29:
            raise
        time.sleep(1)

for path in ("/", "/demo.html", "/demo.js", "/demo-client.js", "/demo.css"):
    assert browser.request(path), f"Empty asset: {path}"
for path in ("/index.html", "/api/admin/status", "/api/orders", "/actuator/prometheus"):
    browser.request(path, expected=403)
browser.request("/api/demo/manual", method="POST", csrf=False, expected=403)
browser.start()
status = browser.request("/api/demo/status")
assert status["database"] == "PostgreSQL" and status["eventsEnabled"] is True
assert browser.request("/api/demo/run/race", method="POST")["passed"] is True
assert browser.request("/api/demo/run/race", method="POST", expected=429)["code"] == "DEMO_RATE_LIMITED"
first = browser.request("/api/demo/manual", method="POST")
buy = browser.request("/api/demo/manual/actions", method="POST", data={"action": "BUY", "quantity": 1})
assert buy["order"]["status"] == "RESERVED"

visitor = Browser()
visitor.start()
second = visitor.request("/api/demo/manual", method="POST")
assert second["inventory"]["id"] == first["inventory"]["id"]
assert second["inventory"]["reserved"] == buy["inventory"]["reserved"]
assert second["order"] is None, "Another visitor can see this browser's active order"
visitor.request("/api/demo/manual/actions", method="POST", data={"action": "CANCEL", "quantity": 1}, expected=400)
time.sleep(0.6)
paid = browser.request("/api/demo/manual/actions", method="POST", data={"action": "PAY", "quantity": 1})
assert paid["order"]["status"] == "CONFIRMED"

for attempt in range(30):
    status = browser.request("/api/demo/status")
    if status["pendingEvents"] == 0 and status["auditReceipts"] >= 4:
        break
    if attempt == 29:
        raise AssertionError(f"Kafka delivery did not finish: {status}")
    time.sleep(1)
print("Public HTTPS smoke passed: secure sessions, CSRF, isolated orders, shared inventory, Kafka, closed admin API.")
