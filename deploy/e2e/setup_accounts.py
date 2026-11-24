"""为每个角色准备一个 e2e_ 账号：不存在就创建，存在就复用（启用 + 校正角色 + 重置口令），然后首登改密。
口令随机生成，只写入 .accounts.json（权限 600），不打印。"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import (ACCOUNTS_FILE, HERE, ROLES, gen_password, login_client, owner_credentials, rows,
                 save_accounts)


def find_user(owner, login_name):
    r = owner.get("/mold/users", query={"keyword": login_name, "pageSize": 50})
    if r.status != 200:
        raise RuntimeError(f"查询账号失败 {r!r}")
    for u in rows(r):
        if u.get("loginName") == login_name:
            return u
    return None


def ensure(owner, login_name, display, roles):
    """返回 (user, 临时口令, created|reused)。"""
    temp = gen_password()
    u = find_user(owner, login_name)
    if u is None:
        r = owner.post("/mold/users", {"loginName": login_name, "displayName": display, "password": temp, "roles": roles})
        if r.status != 201:
            raise RuntimeError(f"创建 {login_name} 失败 {r!r}")
        return r.data, temp, "created"
    uid = u["id"]
    if u.get("status") != "ACTIVE":
        r = owner.patch(f"/mold/users/{uid}", {"status": "ACTIVE"}, u["version"])
        if r.status != 200:
            raise RuntimeError(f"启用 {login_name} 失败 {r!r}")
        u = r.data
    if sorted(u.get("roles") or []) != sorted(roles):
        r = owner.put(f"/mold/users/{uid}/roles", {"roles": roles}, if_match=u["version"])
        if r.status != 200:
            raise RuntimeError(f"设置 {login_name} 角色失败 {r!r}")
    r = owner.post(f"/mold/users/{uid}/password", {"password": temp}, idem_key=None)
    if r.status != 200:
        raise RuntimeError(f"重置 {login_name} 口令失败 {r!r}")
    return owner.get(f"/mold/users/{uid}").data, temp, "reused"


def main():
    with open(os.path.join(HERE, ".gitignore"), encoding="utf-8") as f:
        assert ".accounts.json" in f.read().split(), ".gitignore 未忽略 .accounts.json"
    lg, pw = owner_credentials()
    owner, _ = login_client("owner", lg, pw)
    accounts = {}
    for role in ROLES:
        login_name = f"e2e_{role.lower()}"
        user, temp, how = ensure(owner, login_name, f"E2E {role}", [role])
        c, d = login_client(login_name, login_name, temp)
        must = d.get("mustChangePassword")
        blocked = c.get("/mold/projects", query={"pageSize": 1})
        final = gen_password()
        r = c.post("/auth/password", {"oldPassword": temp, "newPassword": final}, idem_key=None)
        if r.status != 200:
            raise RuntimeError(f"{login_name} 首登改密失败 {r!r}")
        me = c.get("/auth/me")
        accounts[role] = {"loginName": login_name, "password": final, "userId": user["id"]}
        print(f"{role:10s} {login_name:16s} {how:7s} 首登mustChange={must} 改密前业务接口={blocked.status}"
              f"/{blocked.error_code} 改密={r.status} 改密后me={me.status} mustChange={(me.data or {}).get('mustChangePassword')}")
    save_accounts(accounts)
    mode = oct(os.stat(ACCOUNTS_FILE).st_mode & 0o777)
    ignored = subprocess.run(["git", "check-ignore", "-q", ACCOUNTS_FILE], cwd=HERE).returncode == 0
    print(f"已写入 deploy/e2e/.accounts.json 权限={mode} git忽略={ignored}")


if __name__ == "__main__":
    main()
