"""E2E 公共库：仅用标准库 urllib。不打印任何口令或令牌。"""
import json
import os
import secrets
import string
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = os.environ.get("E2E_BASE", "http://127.0.0.1:8080/api/admin")
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
ACCOUNTS_FILE = os.path.join(HERE, ".accounts.json")
ROLES = ["OWNER", "FINANCE", "PM", "PROGRAMMER", "MACHINIST", "VIEWER"]


def read_env(path=os.path.join(REPO, "deploy", ".env")):
    env = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            v = v.strip()
            if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
                v = v[1:-1]
            env[k.strip()] = v
    return env


def gen_password(n=20):
    alphabet = string.ascii_letters + string.digits
    while True:
        pw = "".join(secrets.choice(alphabet) for _ in range(n - 2)) + secrets.choice("!@#%^*") + secrets.choice(string.digits)
        if any(c.islower() for c in pw) and any(c.isupper() for c in pw):
            return pw


def idem():
    return "e2e-" + uuid.uuid4().hex


class Resp:
    def __init__(self, status, body, headers):
        self.status = status
        self.raw = body or b""
        self.headers = {k.lower(): v for k, v in (headers or {}).items()}
        try:
            self.json = json.loads(self.raw.decode("utf-8")) if self.raw else None
        except Exception:
            self.json = None

    @property
    def data(self):
        return self.json.get("data") if isinstance(self.json, dict) else None

    @property
    def error_code(self):
        d = self.data
        return d.get("errorCode") if isinstance(d, dict) else None

    @property
    def details(self):
        d = self.data
        return d.get("details") if isinstance(d, dict) else None

    @property
    def msg(self):
        return self.json.get("msg") if isinstance(self.json, dict) else None

    def text(self):
        return self.raw.decode("utf-8", "replace")

    def __repr__(self):
        extra = ""
        if self.details:
            extra = " " + json.dumps(self.details, ensure_ascii=False)[:200]
        return f"<{self.status} {self.error_code or ''} {self.msg or ''}{extra}>"


def request(method, path, token=None, body=None, idem_key=None, if_match=None, query=None, headers=None):
    url = BASE + path
    if query:
        q = {k: v for k, v in query.items() if v is not None}
        url += ("&" if "?" in url else "?") + urllib.parse.urlencode(q)
    h = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        h["Content-Type"] = "application/json"
    if token:
        h["Authorization"] = "Bearer " + token
    if idem_key:
        h["Idempotency-Key"] = idem_key
    if if_match is not None:
        h["If-Match"] = f'"{if_match}"'
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return Resp(r.status, r.read(), dict(r.headers))
        except urllib.error.HTTPError as e:
            return Resp(e.code, e.read(), dict(e.headers or {}))
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            if attempt == 2:
                raise
            time.sleep(1)


def login(login_name, password):
    for _ in range(3):
        r = request("POST", "/auth/login", body={"loginName": login_name, "password": password})
        if r.status != 429:
            return r
        wait = int(r.headers.get("retry-after", "10") or 10)
        print(f"  [login] {login_name} 被限流，等待 {wait}s", file=sys.stderr)
        time.sleep(min(wait, 90) + 1)
    return r


class Client:
    """带令牌的调用者；令牌只保存在内存中，不落盘不打印。"""

    def __init__(self, name, token, user=None):
        self.name = name
        self.token = token
        self.user = user or {}

    def __repr__(self):
        return f"<Client {self.name}>"

    @property
    def id(self):
        return self.user.get("id")

    def call(self, method, path, **kw):
        return request(method, path, token=self.token, **kw)

    def get(self, path, **kw):
        return self.call("GET", path, **kw)

    def _key(self, k):
        return idem() if k is True else (k or None)

    def post(self, path, body=None, idem_key=True, **kw):
        return self.call("POST", path, body=body if body is not None else {}, idem_key=self._key(idem_key), **kw)

    def patch(self, path, body, if_match, idem_key=True, **kw):
        return self.call("PATCH", path, body=body, if_match=if_match, idem_key=self._key(idem_key), **kw)

    def put(self, path, body, if_match=None, idem_key=True, **kw):
        return self.call("PUT", path, body=body, if_match=if_match, idem_key=self._key(idem_key), **kw)

    def delete(self, path, if_match=None, idem_key=True, **kw):
        return self.call("DELETE", path, if_match=if_match, idem_key=self._key(idem_key), **kw)


def login_client(name, login_name, password):
    r = login(login_name, password)
    if r.status != 200 or not r.data or not r.data.get("accessToken"):
        raise RuntimeError(f"{name} 登录失败: {r!r}")
    return Client(name, r.data["accessToken"], r.data.get("user")), r.data


class Checker:
    def __init__(self):
        self.passed = 0
        self.failed = []
        self.section = ""

    def sec(self, title):
        self.section = title
        print(f"\n== {title} ==")

    def check(self, name, cond, detail=""):
        if cond:
            self.passed += 1
            print(f"  PASS {name}")
        else:
            self.failed.append({"section": self.section, "name": name, "detail": str(detail)[:500]})
            print(f"  FAIL {name} :: {str(detail)[:500]}")
        return bool(cond)

    def status(self, name, resp, expected, code=None):
        exp = expected if isinstance(expected, (list, tuple, set)) else [expected]
        ok = resp.status in exp and (code is None or resp.error_code == code)
        return self.check(name, ok, f"期望 {expected}{' ' + code if code else ''}，实际 {resp!r}")

    def summary(self):
        total = self.passed + len(self.failed)
        print(f"\n结果：通过 {self.passed} / {total}，失败 {len(self.failed)}")
        for f in self.failed:
            print(f"  - [{f['section']}] {f['name']} :: {f['detail']}")
        return {"passed": self.passed, "failed": self.failed}


def load_accounts():
    with open(ACCOUNTS_FILE, encoding="utf-8") as f:
        return json.load(f)


def save_accounts(acc):
    fd = os.open(ACCOUNTS_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(acc, f, ensure_ascii=False, indent=2)
    os.chmod(ACCOUNTS_FILE, 0o600)


def rows(resp):
    d = resp.data
    if isinstance(d, dict):
        for k in ("rows", "items", "records", "list"):
            if isinstance(d.get(k), list):
                return d[k]
    if isinstance(d, list):
        return d
    return []


def owner_credentials():
    env = read_env()
    return env["MOLD_BOOTSTRAP_OWNER_LOGIN"], env["MOLD_BOOTSTRAP_OWNER_PASSWORD"]


if __name__ == "__main__":
    lg, pw = owner_credentials()
    c, d = login_client("owner", lg, pw)
    print("owner 登录 OK, roles=", c.user.get("roles"), "mustChangePassword=", d.get("mustChangePassword"))
    r = c.get("/mold/users", query={"pageSize": 50, "keyword": "e2e_"})
    print("users:", r.status, (r.data or {}).get("total"), [(u.get("loginName"), u.get("status"), u.get("roles")) for u in rows(r)])
