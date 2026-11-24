"""采购 / 并发 / 权限 E2E 共用工具（purchase_* 与 perm_* 脚本使用）。

- 复用 lib.py 的 request/Client/Checker（只读引用，不修改 lib.py）；
- 每个账号只登录一次：令牌缓存在 .perm_tokens.json（chmod 600，已加入 .gitignore），
  再次运行先用 /auth/me 校验，失效才重新登录；
- 不打印任何口令或令牌。
"""
import json
import os
import subprocess
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import (BASE, HERE, REPO, ROLES, Checker, Client, Resp, gen_password, idem, login_client,  # noqa: E402
                 owner_credentials, request, rows)

ACCOUNTS = os.path.join(HERE, ".accounts.json")
PERM_ACCOUNTS = os.path.join(HERE, ".perm_accounts.json")
TOKENS = os.path.join(HERE, ".perm_tokens.json")
COMPOSE = os.path.join(REPO, "deploy", "docker-compose.prod.yml")
TODAY = "2026-11-23"


def _write_secret(path, obj):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
    os.chmod(path, 0o600)


def _read_json(path):
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def accounts():
    """优先用另一代理建的 .accounts.json，其次用本代理兜底建的 .perm_accounts.json。"""
    for path in (ACCOUNTS, PERM_ACCOUNTS):
        acc = _read_json(path)
        if all(r in acc for r in ROLES):
            return acc
    raise RuntimeError("测试账号文件不存在或不完整")


_clients = {}


def client(role):
    """按角色返回已登录 Client；进程内与跨进程（令牌缓存）都只登录一次。"""
    if role in _clients:
        return _clients[role]
    acc = accounts()[role]
    cache = _read_json(TOKENS)
    entry = cache.get(acc["loginName"])
    if entry:
        c = Client(role, entry["token"], entry.get("user"))
        if request("GET", "/auth/me", token=c.token).status == 200:
            _clients[role] = c
            return c
    c, data = login_client(role, acc["loginName"], acc["password"])
    if data.get("mustChangePassword"):
        raise RuntimeError(f"{role} 仍需改密")
    cache[acc["loginName"]] = {"token": c.token, "user": c.user}
    _write_secret(TOKENS, cache)
    _clients[role] = c
    return c


def db(sql):
    """只读查库，返回行列表（每行为字符串列表）。"""
    inner = ("MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql --protocol=socket --default-character-set=utf8mb4 "
             "-uroot mold_dev -N -B")  # SQL 走 stdin，避免反引号/引号被 shell 解释
    out = subprocess.run(["docker", "compose", "-f", COMPOSE, "exec", "-T", "mysql", "sh", "-c", inner],
                         input=sql, capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        raise RuntimeError("查库失败: " + out.stderr.strip()[:300])
    return [line.split("\t") for line in out.stdout.splitlines() if line.strip()]


def db_one(sql):
    r = db(sql)
    return r[0][0] if r and r[0] else None


def tag():
    return uuid.uuid4().hex[:10].upper()


def make_supplier(fin, name=None):
    r = fin.post("/mold/counterparties", {"name": name or f"E2E供应商-{tag()}", "type": "SUPPLIER"})
    if r.status not in (200, 201):
        raise RuntimeError(f"建供应商失败 {r!r}")
    return r.data["id"]


def purchase_body(cp, items=None, currency="CNY", code=None):
    return {
        "code": code or f"E2E-PO-{tag()}",
        "counterpartyId": cp,
        "purchaseDate": TODAY,
        "allocationStatus": "PUBLIC",
        "currency": currency,
        "taxIncluded": True,
        "items": items or [{"lineNo": 1, "name": "E2E 模架钢", "qty": "10", "unit": "块", "unitPrice": "100"}],
        "note": "E2E",
    }


def make_confirmed_purchase(fin, cp, items=None, currency="CNY"):
    r = fin.post("/mold/purchases", purchase_body(cp, items, currency))
    if r.status != 201:
        raise RuntimeError(f"建采购失败 {r!r}")
    pid = r.data["id"]
    c = fin.post(f"/mold/purchases/{pid}/confirm")
    if c.status != 200:
        raise RuntimeError(f"确认采购失败 {c!r}")
    return pid, c.data


def pay_body(amount, currency="CNY", note="E2E"):
    return {"amount": amount, "currency": currency, "occurredOn": TODAY, "method": "BANK", "note": note}


def _multipart(fields, filename, content, mime):
    boundary = "----e2e" + uuid.uuid4().hex
    parts = []
    for k, v in fields.items():
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode())
    parts.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
                  f"Content-Type: {mime}\r\n\r\n").encode() + content + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def upload(c, owner_type, owner_id, category, filename, content, mime, key=True):
    """lib.request 只发 JSON，这里自建 multipart 请求。"""
    import urllib.error
    import urllib.request
    body, ctype = _multipart({"ownerType": owner_type, "ownerId": owner_id, "category": category},
                             filename, content, mime)
    h = {"Authorization": "Bearer " + c.token, "Content-Type": ctype, "Accept": "application/json"}
    if key:
        h["Idempotency-Key"] = idem() if key is True else key
    req = urllib.request.Request(BASE + "/mold/attachments", data=body, method="POST", headers=h)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return Resp(r.status, r.read(), dict(r.headers))
    except urllib.error.HTTPError as e:
        return Resp(e.code, e.read(), dict(e.headers or {}))


def save_report(name, result):
    path = os.path.join(HERE, f"{name}_report.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    return path
