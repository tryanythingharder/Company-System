"""模具加工管理系统业务流程 E2E（可重复运行，测试数据统一 E2E-<RUN> 前缀）。
运行：python3 deploy/e2e/business_flow.py   （先运行 setup_accounts.py 生成 .accounts.json）
不打印任何口令或令牌；结果写入 deploy/e2e/last_report.json（已被 .gitignore 忽略）。"""
import datetime as dt
import json
import os
import subprocess
import sys
import time
import traceback
from decimal import Decimal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import (HERE, REPO, Checker, gen_password, load_accounts, login, login_client, request, rows)  # noqa: E402

RUN = time.strftime("%m%d%H%M%S")
TAG = f"E2E-{RUN}"
C = Checker()
INFO = []
S = {}  # 跨段共享的测试对象 id


def info(msg):
    INFO.append(f"[{C.section}] {msg}")
    print(f"  INFO {msg}")


def dget(resp, *keys, default=None):
    d = resp.data if hasattr(resp, "data") else resp
    for k in keys:
        if not isinstance(d, dict):
            return default
        d = d.get(k)
    return default if d is None else d


def dec(v):
    return Decimal(str(v)) if v is not None else Decimal("0")


def bj_today():
    return (dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=8)).date()


def db_scalar(sql):
    """只读查库，返回首行首列。"""
    cmd = ["docker", "compose", "-f", os.path.join(REPO, "deploy", "docker-compose.prod.yml"), "exec", "-T", "mysql",
           "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --default-character-set=utf8mb4 -uroot -N mold_dev']
    out = subprocess.run(cmd, input=sql, capture_output=True, text=True, cwd=REPO, timeout=60)
    if out.returncode != 0:
        raise RuntimeError("查库失败: " + out.stderr.strip()[:200])
    return out.stdout.strip().split("\n")[0].split("\t")[0]


def section(fn):
    def run():
        C.sec(fn.__doc__ or fn.__name__)
        try:
            fn()
        except Exception as e:  # 一段崩溃不影响后续段
            C.check(f"{fn.__name__} 未抛异常", False, f"{type(e).__name__}: {e} @ {traceback.format_exc().splitlines()[-3].strip()}")
    return run


# ------------------------------------------------------------------ 登录
ACC = load_accounts()
U = {}
for role, a in ACC.items():
    c, _ = login_client(role, a["loginName"], a["password"])
    c.user = dict(c.user or {})
    c.user.setdefault("id", a["userId"])
    U[role] = c
O, FIN, PM, PROG, MACH, VIEW = (U[r] for r in ["OWNER", "FINANCE", "PM", "PROGRAMMER", "MACHINIST", "VIEWER"])


def user_get(uid):
    return O.get(f"/mold/users/{uid}").data or {}


@section
def accounts():
    """账号：创建 / 改角色 / 停用 / 自我保护"""
    login_name = "e2e_tmp"
    r = O.get("/mold/users", query={"keyword": login_name, "pageSize": 50})
    C.status("OWNER 查询账号列表 200", r, 200)
    u = next((x for x in rows(r) if x.get("loginName") == login_name), None)
    temp = gen_password()
    if u is None:
        r = O.post("/mold/users", {"loginName": login_name, "displayName": "E2E 临时", "password": temp, "roles": ["VIEWER"]})
        C.status("创建 e2e_tmp 201", r, 201)
        u = r.data
    else:
        if u.get("status") != "ACTIVE":
            r = O.patch(f"/mold/users/{u['id']}", {"status": "ACTIVE"}, u["version"])
            C.status("复用 e2e_tmp：重新启用 200", r, 200)
        u = user_get(u["id"])
        if sorted(u.get("roles") or []) != ["VIEWER"]:
            r = O.put(f"/mold/users/{u['id']}/roles", {"roles": ["VIEWER"]}, if_match=u["version"])
            C.status("复用 e2e_tmp：角色复位 VIEWER 200", r, 200)
        r = O.post(f"/mold/users/{u['id']}/password", {"password": temp}, idem_key=None)
        C.status("复用 e2e_tmp：重置口令 200", r, 200)
    uid = u["id"]
    S["tmp"] = uid
    r = O.post("/mold/users", {"loginName": login_name, "displayName": "dup", "password": gen_password(), "roles": ["VIEWER"]})
    C.status("重复登录名 409 LOGIN_NAME_DUPLICATE", r, 409, "LOGIN_NAME_DUPLICATE")
    C.status("PM 无权查账号列表 403", PM.get("/mold/users"), 403)

    u = user_get(uid)
    v = u["version"]
    r = O.put(f"/mold/users/{uid}/roles", {"roles": ["PROGRAMMER"]}, if_match=v)
    C.status("改角色为 PROGRAMMER 200", r, 200)
    C.check("改角色后 roles=[PROGRAMMER]", (user_get(uid).get("roles") or []) == ["PROGRAMMER"], user_get(uid).get("roles"))
    r = O.put(f"/mold/users/{uid}/roles", {"roles": ["VIEWER"]}, if_match=v)
    C.status("改角色用旧 If-Match 409 VERSION_CONFLICT", r, 409, "VERSION_CONFLICT")

    lr = login(login_name, temp)
    C.status("e2e_tmp 首登 200", lr, 200)
    C.check("首登 mustChangePassword 为 True", dget(lr, "mustChangePassword") is True, dget(lr, "mustChangePassword"))
    tok = dget(lr, "accessToken")
    blocked = request("GET", "/mold/projects", token=tok, query={"pageSize": 1})
    C.check("改密前业务接口被拦截(非200)", blocked.status != 200, repr(blocked))
    newpw = gen_password()
    r = request("POST", "/auth/password", token=tok, body={"oldPassword": temp, "newPassword": newpw})
    C.status("e2e_tmp 自助改密 200", r, 200)
    C.status("改密后旧令牌可访问业务接口 200", request("GET", "/mold/projects", token=tok, query={"pageSize": 1}), 200)
    r = request("POST", "/mold/cash-entries", token=tok, body={}, idem_key="e2e-perm-" + RUN + "a")
    C.check("PROGRAMMER 角色具备 cash:create（空体应为校验错误而非403）", r.status != 403, repr(r))
    u = user_get(uid)
    r = O.put(f"/mold/users/{uid}/roles", {"roles": ["VIEWER"]}, if_match=u["version"])
    C.status("改回 VIEWER 200", r, 200)
    r = request("POST", "/mold/cash-entries", token=tok, body={}, idem_key="e2e-perm-" + RUN + "b")
    C.status("改角色立即生效：已登录会话失去 cash:create 403", r, 403)

    u = user_get(uid)
    r = O.patch(f"/mold/users/{uid}", {"status": "DISABLED"}, u["version"])
    C.status("停用 e2e_tmp 200", r, 200)
    C.check("停用后 status=DISABLED", user_get(uid).get("status") == "DISABLED", user_get(uid).get("status"))
    C.status("停用后旧令牌 /auth/me 401", request("GET", "/auth/me", token=tok), 401)
    C.status("停用后旧令牌业务接口 401", request("GET", "/mold/projects", token=tok, query={"pageSize": 1}), 401)
    lr = login(login_name, newpw)
    C.check("停用后不能登录", lr.status != 200 and not dget(lr, "accessToken"), repr(lr))
    info(f"停用账号登录返回 {lr!r}")
    p3 = gen_password()
    r = O.post(f"/mold/users/{uid}/password", {"password": p3}, idem_key=None)
    C.status("停用账号可重置口令 200", r, 200)
    C.check("重置口令后仍为 DISABLED", user_get(uid).get("status") == "DISABLED", user_get(uid).get("status"))
    lr = login(login_name, p3)
    C.check("重置口令后仍不能登录", lr.status != 200 and not dget(lr, "accessToken"), repr(lr))

    me = user_get(O.id)
    r = O.patch(f"/mold/users/{O.id}", {"status": "DISABLED"}, me["version"])
    C.status("不能停用自己 409 SELF_DISABLE_DENIED", r, 409, "SELF_DISABLE_DENIED")
    r = O.put(f"/mold/users/{O.id}/roles", {"roles": ["VIEWER"]}, if_match=me["version"])
    C.status("不能移除自己的 OWNER 409 SELF_OWNER_REMOVAL_DENIED", r, 409, "SELF_OWNER_REMOVAL_DENIED")
    r = O.post(f"/mold/users/{O.id}/password", {"password": gen_password()}, idem_key=None)
    C.status("不能重置自己口令 409 SELF_PASSWORD_RESET_DENIED", r, 409, "SELF_PASSWORD_RESET_DENIED")
    owners = db_scalar("SELECT COUNT(DISTINCT u.id) FROM users u JOIN user_roles r ON r.user_id=u.id "
                       "WHERE u.status='ACTIVE' AND r.role_code='OWNER';")
    info(f"当前有效 OWNER 数={owners}；最后一个 OWNER 只能由其本人操作，接口层被自我保护先拦截，LAST_OWNER_PROTECTED 无法经 API 触发")


def new_project(client, suffix, owner_id):
    body = {"code": f"{TAG}-{suffix}", "name": f"{TAG} 项目{suffix}", "businessType": "MOLD_SET", "ownerUserId": owner_id,
            "dueDate": (bj_today() + dt.timedelta(days=30)).isoformat(), "note": "e2e"}
    return client.post("/mold/projects", body), body


def project(pid):
    return O.get(f"/mold/projects/{pid}").data or {}


@section
def projects():
    """项目：创建 / 编辑 / 负责人 / 成员"""
    r, body = new_project(PM, "P1", PM.id)
    C.status("PM 创建项目 201", r, 201)
    p1 = r.data["id"]
    S["p1"], S["p1code"] = p1, body["code"]
    C.check("新项目 ACTIVE/version=1", r.data.get("status") == "ACTIVE" and r.data.get("version") == 1, r.data)
    C.status("重复项目编号 409 PROJECT_CODE_DUPLICATE", PM.post("/mold/projects", body), 409, "PROJECT_CODE_DUPLICATE")
    C.status("VIEWER 不能建项目 403", new_project(VIEW, "PV", VIEW.id)[0], 403)
    v = r.data["version"]
    r = PM.patch(f"/mold/projects/{p1}", {"name": f"{TAG} 项目P1-改"}, v)
    C.status("负责人编辑项目 200", r, 200)
    C.check("编辑后版本+1", dget(r, "version") == v + 1, dget(r, "version"))
    C.status("旧 If-Match 编辑 409 VERSION_CONFLICT", PM.patch(f"/mold/projects/{p1}", {"note": "x"}, v), 409, "VERSION_CONFLICT")
    r = PM.call("PATCH", f"/mold/projects/{p1}", body={"note": "x"})
    C.check("缺 If-Match 返回 4xx", 400 <= r.status < 500 and r.status != 409, repr(r))
    r = O.patch(f"/mold/projects/{p1}", {"note": "owner"}, project(p1)["version"])
    info(f"OWNER(非项目负责人)编辑项目 -> {r!r}")
    r = PM.put(f"/mold/projects/{p1}/owner", {"ownerUserId": O.id, "reason": "e2e"})
    C.status("PM 不能转负责人 403", r, 403)
    r = O.put(f"/mold/projects/{p1}/owner", {"ownerUserId": O.id, "reason": "e2e 转出"})
    C.status("OWNER 转负责人 200", r, 200)
    C.check("负责人已变更", project(p1).get("ownerUserId") == O.id, project(p1).get("ownerUserId"))
    C.status("原负责人失去编辑权 403 NOT_PROJECT_OWNER",
             PM.patch(f"/mold/projects/{p1}", {"note": "y"}, project(p1)["version"]), 403, "NOT_PROJECT_OWNER")
    r = O.put(f"/mold/projects/{p1}/owner", {"ownerUserId": PM.id, "reason": "e2e 转回"})
    C.status("OWNER 转回 PM 200", r, 200)
    members = [{"userId": PROG.id, "accessLevel": "EDIT"}, {"userId": MACH.id, "accessLevel": "EDIT"},
               {"userId": FIN.id, "accessLevel": "VIEW"}, {"userId": VIEW.id, "accessLevel": "VIEW"}]
    C.status("负责人设置成员 200", PM.put(f"/mold/projects/{p1}/members", {"members": members}), 200)
    C.status("VIEWER 不能设置成员 403", VIEW.put(f"/mold/projects/{p1}/members", {"members": []}), 403)
    for role in ["PROGRAMMER", "MACHINIST", "FINANCE", "VIEWER", "PM", "OWNER"]:
        r = U[role].get(f"/mold/projects/{p1}/members")
        C.status(f"{role} 读取成员 200", r, 200)
        if r.status == 200:
            ids = {m.get("userId") for m in rows(r)}
            C.check(f"{role} 读到 4 名成员", {PROG.id, MACH.id, FIN.id, VIEW.id} <= ids, ids)


def transition(client, sid, **body):
    return client.post(f"/mold/stages/{sid}/transitions", body)


def stage(sid, client=None):
    for s in rows((client or O).get(f"/mold/projects/{S['p1']}/stages")):
        if s.get("id") == sid:
            return s
    return {}


@section
def stages():
    """阶段：分配 / 状态机 / 我的工作 / 历史"""
    p1 = S["p1"]
    r = PM.post(f"/mold/projects/{p1}/stages", {"name": "E2E 编程", "sortOrder": 1, "responsibleUserId": PROG.id})
    C.status("新建阶段A(分配 PROGRAMMER) 201", r, 201)
    sa = r.data["id"]
    C.check("新阶段 NOT_STARTED", r.data.get("status") == "NOT_STARTED", r.data.get("status"))
    r = PM.post(f"/mold/projects/{p1}/stages", {"name": "E2E 操机", "sortOrder": 2, "responsibleUserId": MACH.id})
    C.status("新建阶段B(分配 MACHINIST) 201", r, 201)
    sb = r.data["id"]
    S["sa"], S["sb"] = sa, sb
    C.status("PROGRAMMER 不能建阶段 403", PROG.post(f"/mold/projects/{p1}/stages", {"name": "x"}), 403)
    r = VIEW.get(f"/mold/projects/{p1}/stages")
    C.check("VIEWER 可读阶段列表(2条)", r.status == 200 and len(rows(r)) == 2, repr(r))

    for c, mine, other in [(PROG, sa, sb), (MACH, sb, sa)]:
        r = c.get("/mold/me/stages", query={"pageSize": 100})
        ids = {s.get("id") for s in rows(r)}
        C.check(f"{c.name} 我的工作含本人阶段、不含他人阶段", r.status == 200 and mine in ids and other not in ids, repr(r))
        resp = {s.get("responsibleUserId") for s in rows(r)}
        C.check(f"{c.name} 我的工作全部是分配给自己的", resp <= {c.id}, resp)
    r = PM.get("/mold/me/stages", query={"pageSize": 100})
    C.check("PM 我的工作不含未分配给他的阶段", not ({sa, sb} & {s.get("id") for s in rows(r)}), repr(r))

    C.status("非法 NOT_STARTED->COMPLETED 409", transition(PROG, sa, toStatus="COMPLETED"), 409, "INVALID_STATE_TRANSITION")
    C.status("非法 NOT_STARTED->BLOCKED 409", transition(PROG, sa, toStatus="BLOCKED", note="x"), 409, "INVALID_STATE_TRANSITION")
    C.status("重开/回退 409 POLICY_NOT_CONFIRMED", transition(PROG, sa, toStatus="NOT_STARTED"), 409, "POLICY_NOT_CONFIRMED")
    C.status("action=REOPEN 409 POLICY_NOT_CONFIRMED", transition(PROG, sa, action="REOPEN"), 409, "POLICY_NOT_CONFIRMED")
    r = transition(PROG, sa, toStatus="FOO")
    C.check("未知状态 4xx 校验错误", 400 <= r.status < 500 and r.status not in (401, 403, 409), repr(r))
    C.status("MACHINIST 不能流转他人阶段 403 STAGE_NOT_ASSIGNED", transition(MACH, sa, action="START"), 403, "STAGE_NOT_ASSIGNED")
    C.status("VIEWER 无流转权限 403", transition(VIEW, sa, action="START"), 403)
    r = transition(PROG, sa, action="START")
    C.status("合法 START 200", r, 200)
    C.check("START 后 IN_PROGRESS", dget(r, "status") == "IN_PROGRESS", dget(r, "status"))
    st = stage(sa)
    C.status("责任人更新本人阶段说明 200", PROG.patch(f"/mold/stages/{sa}", {"note": "e2e 说明"}, st.get("version")), 200)
    st = stage(sa)
    C.status("责任人不能改阶段名 403 FORBIDDEN", PROG.patch(f"/mold/stages/{sa}", {"name": "x"}, st.get("version")), 403, "FORBIDDEN")
    C.status("已开工阶段不能转派 409", PM.patch(f"/mold/stages/{sa}", {"responsibleUserId": MACH.id}, st.get("version")),
             409, "INVALID_STATE_TRANSITION")
    r = transition(PROG, sa, action="BLOCK")
    C.check("BLOCK 无原因 4xx 校验错误", 400 <= r.status < 500 and r.status not in (401, 403, 409), repr(r))
    C.status("BLOCK(带原因) 200", transition(PROG, sa, action="BLOCK", note="缺料"), 200)
    C.status("非法 BLOCKED->COMPLETED 409", transition(PROG, sa, toStatus="COMPLETED"), 409, "INVALID_STATE_TRANSITION")
    C.status("RESUME 200", transition(PROG, sa, action="RESUME"), 200)
    r = transition(PROG, sa, action="COMPLETE")
    C.status("COMPLETE 200", r, 200)
    C.check("COMPLETE 后 COMPLETED", dget(r, "status") == "COMPLETED", dget(r, "status"))
    r = transition(PROG, sa, toStatus="IN_PROGRESS")
    C.check("终态再流转 409 terminal", r.status == 409 and r.error_code == "INVALID_STATE_TRANSITION"
            and (r.details or {}).get("terminal") is True, repr(r))
    r = VIEW.get(f"/mold/stages/{sa}/events", query={"pageSize": 100})
    acts = [e.get("action") for e in rows(r)]
    C.check("阶段历史含 CREATE/START/BLOCK/RESUME/COMPLETE", r.status == 200 and
            all(a in acts for a in ["CREATE", "START", "BLOCK", "RESUME", "COMPLETE"]), acts)
    blk = next((e for e in rows(r) if e.get("action") == "BLOCK"), {})
    C.check("受阻历史记录原因", "缺料" in json.dumps(blk, ensure_ascii=False), blk)


@section
def issues_and_completion():
    """异常 + 完工规则"""
    p1, sb = S["p1"], S["sb"]
    r, _ = new_project(PM, "P2", PM.id)
    p2 = r.data["id"]
    C.status("无阶段项目完工 409 PROJECT_HAS_NO_STAGES",
             PM.post(f"/mold/projects/{p2}/complete", {"completionNote": "x"}, if_match=r.data["version"]), 409, "PROJECT_HAS_NO_STAGES")
    r = MACH.post("/mold/issues", {"projectId": p1, "stageId": sb, "title": f"{TAG} 断刀", "description": "e2e", "severity": "HIGH"})
    C.status("MACHINIST 报异常 201", r, 201)
    iss = r.data["id"]
    S["issue"] = iss
    C.check("异常 OPEN", r.data.get("status") == "OPEN", r.data.get("status"))
    r = VIEW.post("/mold/issues", {"projectId": p1, "title": f"{TAG} 只读报异常", "description": "e2e", "severity": "LOW"})
    info(f"VIEWER(只读) 创建异常 -> {r.status} {r.error_code or ''}")
    viss = r.data.get("id") if r.status == 201 else None
    r = VIEW.get(f"/mold/projects/{p1}/issues")
    C.check("项目异常列表含新异常", r.status == 200 and iss in {i.get("id") for i in rows(r)}, repr(r))
    C.status("无关人员(FINANCE)不能处理异常 403", FIN.post(f"/mold/issues/{iss}/resolve", {"resolution": "x"}), 403)
    # 2026-11 拍板：老板可直接操作他人项目，完工请求会走到业务门槛（此时仍有未完成阶段）
    C.status("非负责人 OWNER 完工进入门槛校验 409 PROJECT_HAS_OPEN_STAGES",
             O.post(f"/mold/projects/{p1}/complete", {"completionNote": "x"}, if_match=project(p1)["version"]), 409, "PROJECT_HAS_OPEN_STAGES")
    C.status("非负责人 FINANCE 不能完工 403",
             FIN.post(f"/mold/projects/{p1}/complete", {"completionNote": "x"}, if_match=project(p1)["version"]), 403)
    C.status("有未完成阶段 409 PROJECT_HAS_OPEN_STAGES",
             PM.post(f"/mold/projects/{p1}/complete", {"completionNote": "x"}, if_match=project(p1)["version"]), 409, "PROJECT_HAS_OPEN_STAGES")
    C.status("阶段B START", transition(MACH, sb, action="START"), 200)
    C.status("阶段B COMPLETE", transition(MACH, sb, action="COMPLETE"), 200)
    C.status("有未解决异常 409 PROJECT_HAS_OPEN_ISSUES",
             PM.post(f"/mold/projects/{p1}/complete", {"completionNote": "x"}, if_match=project(p1)["version"]), 409, "PROJECT_HAS_OPEN_ISSUES")
    r = MACH.post(f"/mold/issues/{iss}/resolve", {"resolution": "换刀"})
    C.status("报告人解决异常 200", r, 200)
    C.check("异常 RESOLVED", dget(r, "status") == "RESOLVED", dget(r, "status"))
    C.status("重复解决 409 INVALID_STATE_TRANSITION", MACH.post(f"/mold/issues/{iss}/resolve", {"resolution": "x"}), 409, "INVALID_STATE_TRANSITION")
    if viss:
        C.status("报告人取消异常 200", VIEW.post(f"/mold/issues/{viss}/cancel", {"reason": "误报"}), 200)
    v = project(p1)["version"]
    C.status("完工旧 If-Match 409 VERSION_CONFLICT",
             PM.post(f"/mold/projects/{p1}/complete", {"completionNote": "x"}, if_match=v - 1), 409, "VERSION_CONFLICT")
    r = PM.post(f"/mold/projects/{p1}/complete", {"completionNote": "e2e 完工"}, if_match=v)
    C.status("满足条件后完工 200", r, 200)
    C.check("项目 COMPLETED", dget(r, "status") == "COMPLETED", dget(r, "status"))
    C.status("重复完工 409", PM.post(f"/mold/projects/{p1}/complete", {"completionNote": "x"}, if_match=project(p1)["version"]),
             409, "INVALID_STATE_TRANSITION")
    C.status("完工后不能编辑 409", PM.patch(f"/mold/projects/{p1}", {"note": "z"}, project(p1)["version"]), 409, "INVALID_STATE_TRANSITION")
    C.status("完工后不能建阶段 409", PM.post(f"/mold/projects/{p1}/stages", {"name": "x"}), 409, "INVALID_STATE_TRANSITION")
    C.status("完工后不能报异常 409", MACH.post("/mold/issues", {"projectId": p1, "title": "x", "description": "x", "severity": "LOW"}),
             409, "INVALID_STATE_TRANSITION")
    C.status("重开 409 POLICY_NOT_CONFIRMED", PM.post(f"/mold/projects/{p1}/reopen", {}), 409, "POLICY_NOT_CONFIRMED")


@section
def csv_export():
    """CSV 导出"""
    r = O.get("/mold/projects/export", query={"q": TAG}, headers={"Accept": "text/csv, */*"})
    C.status("导出 200", r, 200)
    ct = r.headers.get("content-type", "")
    C.check("Content-Type 为 csv", "csv" in ct, ct)
    C.check("带 UTF-8 BOM", r.raw.startswith(b"\xef\xbb\xbf"), r.raw[:8])
    text = r.raw.decode("utf-8-sig", "replace")
    lines = [x for x in text.splitlines() if x.strip()]
    C.check("表头含 编号", bool(lines) and "编号" in lines[0], lines[:1])
    C.check("含本次项目 P1/P2", f"{TAG}-P1" in text and f"{TAG}-P2" in text, len(lines))
    C.check("q 过滤生效(仅本次项目)", all(TAG in x for x in lines[1:]), lines[1:4])
    C.check("附件名为 attachment", "attachment" in r.headers.get("content-disposition", ""), r.headers.get("content-disposition"))
    rv = VIEW.get("/mold/projects/export", query={"q": TAG}, headers={"Accept": "text/csv, */*"})
    info(f"VIEWER(无 project:export 权限) 导出 -> {rv.status}")


CODES = {}  # code -> (role, id)
SEQ = [0]


def cash(client, category, direction, amount, project_id=None, counterparty=None, alloc=None, key=True, **extra):
    SEQ[0] += 1
    code = f"{TAG}-C{SEQ[0]:02d}"
    body = {"code": code, "direction": direction, "category": category, "occurredOn": bj_today().isoformat(),
            "amount": amount, "currency": "CNY", "allocationStatus": alloc or ("PROJECT" if project_id else "PUBLIC"),
            "method": "BANK", "note": "e2e"}
    if project_id:
        body["projectId"] = project_id
    if counterparty:
        body["counterpartyId"] = counterparty
    body.update(extra)
    r = client.post("/mold/cash-entries", body, idem_key=key)
    if r.status == 201:
        CODES[code] = (client.name, r.data["id"])
    return r, body


def entry(eid, client=None):
    return (client or FIN).get(f"/mold/cash-entries/{eid}")


@section
def finance_entries():
    """收支流水：录入 / 往来单位 / 幂等 / 可见性"""
    r = FIN.get("/mold/counterparties", query={"type": "CUSTOMER", "pageSize": 100})
    cp = next((x for x in rows(r) if x.get("name") == "E2E 客户" and x.get("status") == "ACTIVE"), None)
    if cp is None:
        r = FIN.post("/mold/counterparties", {"name": "E2E 客户", "type": "CUSTOMER"})
        C.status("FINANCE 新建客户 201", r, [200, 201])
        cp = r.data
    S["cp"] = cp["id"]
    C.status("PM 读往来单位列表 200", PM.get("/mold/counterparties", query={"type": "CUSTOMER"}), 200)
    C.status("PROGRAMMER(有 cash:create) 读往来单位 200", PROG.get("/mold/counterparties"), 200)
    C.status("VIEWER 读往来单位 403", VIEW.get("/mold/counterparties"), 403)
    r, _ = new_project(PM, "P3", PM.id)
    p3 = S["p3"] = r.data["id"]

    r, _ = cash(PM, "CUSTOMER_RECEIPT", "IN", "1000.00", p3, S["cp"])
    C.status("PM 录客户收款 201", r, 201)
    S["pm_entry"] = r.data.get("id") if r.data else None
    r, _ = cash(PROG, "DAILY_EXPENSE", "OUT", "50.00", p3)
    C.status("PROGRAMMER 录日常支出 201", r, 201)
    S["prog_entry"] = r.data.get("id") if r.data else None
    C.status("MACHINIST 录其他支出 201", cash(MACH, "OTHER_EXPENSE", "OUT", "30.00", p3)[0], 201)
    C.status("FINANCE 录其他收入 201", cash(FIN, "OTHER_INCOME", "IN", "200.00", p3)[0], 201)
    C.status("OWNER 录公共日常支出 201", cash(O, "DAILY_EXPENSE", "OUT", "20.00")[0], 201)
    C.status("VIEWER 不能录流水 403", cash(VIEW, "DAILY_EXPENSE", "OUT", "1.00")[0], 403)
    r = cash(PM, "CUSTOMER_RECEIPT", "IN", "1.00", p3)[0]
    C.check("客户收款缺客户 4xx 校验", 400 <= r.status < 500 and r.status not in (401, 403, 409), repr(r))
    r = cash(FIN, "DAILY_EXPENSE", "IN", "1.00")[0]
    C.check("方向与类别不符 4xx 校验", 400 <= r.status < 500 and r.status not in (401, 403, 409), repr(r))
    C.status("普通接口录采购付款 409 POLICY_NOT_CONFIRMED", cash(FIN, "PURCHASE_PAYMENT", "OUT", "1.00")[0], 409, "POLICY_NOT_CONFIRMED")
    r = cash(FIN, "OTHER_INCOME", "IN", "1.00", alloc="PROJECT")[0]
    C.check("PROJECT 归属缺 projectId 4xx 校验", 400 <= r.status < 500 and r.status not in (401, 403, 409), repr(r))

    key = "e2e-idem-" + RUN
    r1, body = cash(FIN, "OTHER_INCOME", "IN", "5.00", p3, key=key)
    r2 = FIN.post("/mold/cash-entries", body, idem_key=key)
    C.check("同键同体重放：返回同一流水 + Idempotent-Replayed", r2.status == 201 and dget(r2, "id") == dget(r1, "id")
            and r2.headers.get("idempotent-replayed") == "true", repr(r2))
    C.status("同键异体 409 IDEMPOTENCY_KEY_REUSED", FIN.post("/mold/cash-entries", dict(body, amount="6.00"), idem_key=key),
             409, "IDEMPOTENCY_KEY_REUSED")
    r = FIN.post("/mold/cash-entries", dict(body, code=body["code"] + "N"), idem_key=None)
    C.check("缺 Idempotency-Key 被拒(4xx)", 400 <= r.status < 500, repr(r))

    all_codes = set(CODES)
    for role in ROLES_ALL:
        c = U[role]
        r = c.get("/mold/cash-entries", query={"q": TAG, "pageSize": 100})
        if role == "VIEWER":
            C.status("VIEWER 无流水列表权限 403", r, 403)
            continue
        got = {x.get("code") for x in rows(r)}
        exp = all_codes if role in ("OWNER", "FINANCE") else {k for k, v in CODES.items() if v[0] == role}
        C.check(f"{role} 列表可见范围正确({len(exp)}条)", r.status == 200 and got == exp, f"多出 {sorted(got - exp)} 缺少 {sorted(exp - got)}")
        C.check(f"{role} 列表 total 与行数一致", dget(r, "total") == len(got), dget(r, "total"))
    C.status("PM 看他人流水详情 404", entry(S["prog_entry"], PM), 404, "NOT_FOUND")
    C.status("PM 看自己流水详情 200", entry(S["pm_entry"], PM), 200)
    C.status("FINANCE 看 PM 流水详情 200", entry(S["pm_entry"], FIN), 200)
    C.status("OWNER 看 PROGRAMMER 流水详情 200", entry(S["prog_entry"], O), 200)
    for role, exp in [("OWNER", 200), ("FINANCE", 200), ("PM", 403), ("PROGRAMMER", 403)]:
        C.status(f"{role} 项目收支报表 {exp}", U[role].get("/mold/reports/project-cash", query={"projectId": p3}), exp)


ROLES_ALL = ["OWNER", "FINANCE", "PM", "PROGRAMMER", "MACHINIST", "VIEWER"]


def ver(eid):
    return dget(entry(eid), "version")


@section
def reversal_void_correction():
    """冲正 / 作废 / 更正"""
    p3 = S["p3"]
    # 已对账
    ea = cash(FIN, "OTHER_INCOME", "IN", "300.00", p3)[0].data["id"]
    r = FIN.post(f"/mold/cash-entries/{ea}/reconcile", {"reconciliationRef": "E2E-REC"})
    C.status("对账 200", r, 200)
    v = ver(ea)
    C.status("重复对账幂等 200", FIN.post(f"/mold/cash-entries/{ea}/reconcile", {"reconciliationRef": "E2E-REC"}), 200)
    C.check("重复对账不涨版本", ver(ea) == v, (v, ver(ea)))
    C.status("已对账作废 409 RECONCILED_ENTRY", FIN.post(f"/mold/cash-entries/{ea}/void", {"reason": "x"}), 409, "RECONCILED_ENTRY")
    C.status("已对账冲正 409 RECONCILED_ENTRY", FIN.post(f"/mold/cash-entries/{ea}/reverse", {"reason": "x"}), 409, "RECONCILED_ENTRY")
    C.status("已对账更正预览 409 RECONCILED_ENTRY", FIN.post(f"/mold/cash-entries/{ea}/corrections/preview",
             {"reason": "x", "replacement": {"amount": "1.00"}}, idem_key=None, if_match=v), 409, "RECONCILED_ENTRY")
    C.status("已对账修改 409 RECONCILED_ENTRY", FIN.patch(f"/mold/cash-entries/{ea}", {"note": "x"}, v), 409, "RECONCILED_ENTRY")
    C.status("解对账 200", FIN.post(f"/mold/cash-entries/{ea}/unreconcile", {"reason": "e2e"}), 200)
    C.status("未对账再解对账 409", FIN.post(f"/mold/cash-entries/{ea}/unreconcile", {"reason": "e2e"}), 409, "INVALID_STATE_TRANSITION")

    # 冲正
    eb = cash(FIN, "DAILY_EXPENSE", "OUT", "80.00", p3)[0].data["id"]
    r = FIN.post(f"/mold/cash-entries/{eb}/reverse", {"reason": "e2e 冲正"})
    C.status("冲正 201", r, 201)
    rb = r.data["id"]
    C.check("冲正单：EXPENSE_REFUND/IN/同金额/指向原单", r.data.get("category") == "EXPENSE_REFUND" and r.data.get("direction") == "IN"
            and dec(r.data.get("amount")) == Decimal("80") and r.data.get("reversesEntryId") == eb, r.data)
    C.check("原单标记 reversedByEntryId", dget(entry(eb), "reversedByEntryId") == rb, dget(entry(eb), "reversedByEntryId"))
    C.status("已冲正再冲正 409 ALREADY_REVERSED", FIN.post(f"/mold/cash-entries/{eb}/reverse", {"reason": "x"}), 409, "ALREADY_REVERSED")
    C.status("已冲正作废 409 ALREADY_REVERSED", FIN.post(f"/mold/cash-entries/{eb}/void", {"reason": "x"}), 409, "ALREADY_REVERSED")
    C.status("已冲正对账 409 ALREADY_REVERSED", FIN.post(f"/mold/cash-entries/{eb}/reconcile", {"reconciliationRef": "x"}), 409, "ALREADY_REVERSED")
    C.status("已冲正更正 409 ALREADY_REVERSED", FIN.post(f"/mold/cash-entries/{eb}/corrections/preview",
             {"reason": "x", "replacement": {"amount": "1.00"}}, idem_key=None, if_match=ver(eb)), 409, "ALREADY_REVERSED")
    C.status("冲正单作废 409 POLICY_NOT_CONFIRMED", FIN.post(f"/mold/cash-entries/{rb}/void", {"reason": "x"}), 409, "POLICY_NOT_CONFIRMED")
    C.status("冲正单(退款类)再冲正 409 POLICY_NOT_CONFIRMED", FIN.post(f"/mold/cash-entries/{rb}/reverse", {"reason": "x"}), 409, "POLICY_NOT_CONFIRMED")
    C.status("冲正单更正 409 ALREADY_REVERSED", FIN.post(f"/mold/cash-entries/{rb}/corrections/preview",
             {"reason": "x", "replacement": {"amount": "1.00"}}, idem_key=None, if_match=ver(rb)), 409, "ALREADY_REVERSED")
    C.status("PM 无冲正权限 403", PM.post(f"/mold/cash-entries/{S['pm_entry']}/reverse", {"reason": "x"}), 403)
    r = FIN.post(f"/mold/cash-entries/{S['pm_entry']}/reverse", {"reason": "客户退款"})
    C.status("FINANCE 冲正 PM 的客户收款 201", r, 201)
    rc = r.data["id"] if r.status == 201 else None
    S["rc"] = rc
    if rc:
        C.check("客户收款冲正单为 CUSTOMER_REFUND", r.data.get("category") == "CUSTOMER_REFUND", r.data.get("category"))
        r2 = FIN.post(f"/mold/cash-entries/{rc}/reverse", {"reason": "冲正冲正单"})
        C.check("冲正单(CUSTOMER_REFUND)不能再被冲正 409", r2.status == 409, repr(r2))
        if r2.status == 201:
            S["rrc"] = r2.data["id"]
            info(f"冲正单被再次冲正，生成 {r2.data.get('category')} {r2.data.get('amount')}，reversesEntryId 指向冲正单")
        C.status("冲正单作废 409 POLICY_NOT_CONFIRMED(CUSTOMER_REFUND)", FIN.post(f"/mold/cash-entries/{rc}/void", {"reason": "x"}),
                 409, "POLICY_NOT_CONFIRMED")

    # 更正（替换）
    ed = cash(FIN, "OTHER_EXPENSE", "OUT", "100.00", p3)[0].data["id"]
    v = ver(ed)
    body = {"reason": "金额录错", "replacement": {"amount": "88.00", "note": "e2e 更正"}}
    C.status("更正预览旧 If-Match 409 VERSION_CONFLICT", FIN.post(f"/mold/cash-entries/{ed}/corrections/preview", body,
             idem_key=None, if_match=v + 1), 409, "VERSION_CONFLICT")
    r = FIN.post(f"/mold/cash-entries/{ed}/corrections/preview", {"reason": "x", "replacement": {"amount": "100.00", "note": "e2e"}},
                 idem_key=None, if_match=v)
    C.check("无差异替代 4xx 校验", 400 <= r.status < 500 and r.status not in (401, 403, 409), repr(r))
    C.status("PM 无更正预览权限 403", PM.post(f"/mold/cash-entries/{S['pm_entry']}/corrections/preview", body, idem_key=None, if_match=1), 403)
    pv = FIN.post(f"/mold/cash-entries/{ed}/corrections/preview", body, idem_key=None, if_match=v)
    C.status("更正预览 200", pv, 200)
    C.check("预览不改版本", ver(ed) == v, (v, ver(ed)))
    ap = FIN.post(f"/mold/cash-entries/{ed}/corrections", body, if_match=v)
    C.status("更正提交 200", ap, 200)
    C.check("预览与提交 impact 一致", dget(pv, "impact") == dget(ap, "impact"),
            f"preview={json.dumps(dget(pv, 'impact'), ensure_ascii=False)[:200]} apply={json.dumps(dget(ap, 'impact'), ensure_ascii=False)[:200]}")
    rep = dget(ap, "replacement") or {}
    orig = dget(ap, "original") or {}
    C.check("原单 VOID、替代单 88.00 指向原单", orig.get("status") == "VOID" and dec(rep.get("amount")) == Decimal("88")
            and rep.get("replacesEntryId") == ed, {"orig": orig.get("status"), "rep": rep.get("amount")})
    nodes = dget(entry(ed), "correctionChain", "nodes", default=[])
    C.check("详情更正链 2 个节点", len(nodes) == 2, len(nodes))
    if rep.get("id"):
        C.status("更正链(替代单)作废 409 ALREADY_CORRECTED", FIN.post(f"/mold/cash-entries/{rep['id']}/void", {"reason": "x"}), 409, "ALREADY_CORRECTED")
        C.status("更正链(替代单)冲正 409 ALREADY_CORRECTED", FIN.post(f"/mold/cash-entries/{rep['id']}/reverse", {"reason": "x"}), 409, "ALREADY_CORRECTED")
    C.status("被更正原单再更正 409 ALREADY_CORRECTED", FIN.post(f"/mold/cash-entries/{ed}/corrections/preview", body,
             idem_key=None, if_match=ver(ed)), 409, "ALREADY_CORRECTED")
    C.status("被更正原单作废 409 ENTRY_ALREADY_VOID", FIN.post(f"/mold/cash-entries/{ed}/void", {"reason": "x"}), 409, "ENTRY_ALREADY_VOID")

    # 更正（仅作废）与普通作废
    ee = cash(FIN, "OTHER_INCOME", "IN", "60.00", p3)[0].data["id"]
    pv = FIN.post(f"/mold/cash-entries/{ee}/corrections/preview", {"reason": "重复录入"}, idem_key=None, if_match=ver(ee))
    ap = FIN.post(f"/mold/cash-entries/{ee}/corrections", {"reason": "重复录入"}, if_match=ver(ee))
    C.status("仅作废式更正 200", ap, 200)
    C.check("仅作废式更正：预览与提交 impact 一致、无替代单", dget(pv, "impact") == dget(ap, "impact") and dget(ap, "replacement") is None,
            (dget(pv, "impact"), dget(ap, "impact")))
    ef = cash(FIN, "OTHER_INCOME", "IN", "10.00", p3)[0].data["id"]
    C.status("普通作废 200", FIN.post(f"/mold/cash-entries/{ef}/void", {"reason": "e2e"}), 200)
    C.status("再次作废 409 ENTRY_ALREADY_VOID", FIN.post(f"/mold/cash-entries/{ef}/void", {"reason": "e2e"}), 409, "ENTRY_ALREADY_VOID")
    S["cash_ids"] = {"ea": ea, "eb": eb, "rb": rb, "ed": ed, "ee": ee, "ef": ef, "rep": rep.get("id")}


@section
def report_consistency():
    """报表与列表口径"""
    p3 = S["p3"]
    lst = rows(O.get("/mold/cash-entries", query={"projectId": p3, "status": "CONFIRMED", "pageSize": 100}))
    by_cat = {}
    for x in lst:
        by_cat[x["category"]] = by_cat.get(x["category"], Decimal(0)) + dec(x["amount"])
    net = sum((dec(x["amount"]) if x["direction"] == "IN" else -dec(x["amount"])) for x in lst)
    r = O.get("/mold/reports/project-cash", query={"projectId": p3})
    blk = next((b for b in dget(r, "currencies", default=[]) if b.get("currency") == "CNY"), {})
    rep = {c["category"]: dec(c["amount"]) for c in blk.get("categories", []) if dec(c.get("amount")) != 0}
    C.check("项目报表分类金额 = 列表(CONFIRMED)汇总", rep == {k: v for k, v in by_cat.items() if v != 0},
            f"report={ {k: str(v) for k, v in rep.items()} } list={ {k: str(v) for k, v in by_cat.items()} }")
    C.check("项目报表 netCash = 列表净额", dec(blk.get("netCash")) == net, (blk.get("netCash"), str(net)))
    r2 = FIN.get("/mold/reports/cash-summary", query={"projectId": p3})
    blk2 = next((b for b in dget(r2, "currencies", default=[]) if b.get("currency") == "CNY"), {})
    C.check("汇总报表 netCash 与项目报表一致", r2.status == 200 and dec(blk2.get("netCash")) == net, (r2.status, blk2.get("netCash")))
    pm_list = rows(PM.get("/mold/cash-entries", query={"projectId": p3, "pageSize": 100}))
    C.check("PM 列表只含本人录入", {x.get("code") for x in pm_list} <= {k for k, v in CODES.items() if v[0] == "PM"},
            [x.get("code") for x in pm_list])
    if S.get("rc"):
        C.status("他人对 PM 流水做的冲正单 PM 不可见(404)", entry(S["rc"], PM), 404)


def audit_rows(eid):
    r = O.get("/mold/audit-logs", query={"entityId": eid, "pageSize": 100})
    return r, [x.get("action") for x in rows(r)]


@section
def audit():
    """审计日志"""
    C.status("FINANCE 无审计权限 403", FIN.get("/mold/audit-logs"), 403)
    ids = S.get("cash_ids", {})
    expect = [("USER", S.get("tmp"), ["SET_ROLES", "UPDATE", "RESET_PASSWORD"]),
              ("PROJECT", S.get("p1"), ["CREATE", "UPDATE", "COMPLETE"]),
              ("STAGE", S.get("sa"), ["CREATE", "UPDATE", "TRANSITION"]),
              ("ISSUE", S.get("issue"), ["CREATE", "RESOLVE"]),
              ("CASH_ENTRY", S.get("pm_entry"), ["CREATE", "REVERSE"]),
              ("CASH_ENTRY", ids.get("eb"), ["CREATE", "REVERSE"]),
              ("CASH_ENTRY", ids.get("rb"), ["CREATE"]),
              ("CASH_ENTRY", ids.get("ef"), ["CREATE"]),
              ("CASH_ENTRY", ids.get("rep"), ["CREATE"])]
    for et, eid, acts in expect:
        if not eid:
            continue
        r, got = audit_rows(eid)
        C.check(f"审计 {et} 含 {acts}", r.status == 200 and all(a in got for a in acts), got)
    for name, key, min_n in [("对账/解对账", "ea", 3), ("作废", "ef", 2), ("更正原单", "ed", 2), ("仅作废式更正", "ee", 2)]:
        if ids.get(key):
            r, got = audit_rows(ids[key])
            C.check(f"审计 {name} 记录写操作(≥{min_n}条)", len(got) >= min_n, got)
    _, got = audit_rows(S.get("p1"))
    info(f"项目P1审计动作: {sorted(set(got))}")
    _, got = audit_rows(S.get("cp"))
    info(f"往来单位审计动作: {sorted(set(got))}")

    today = bj_today()
    yday = today - dt.timedelta(days=1)
    start_utc = dt.datetime.combine(today, dt.time()) - dt.timedelta(hours=8)
    q = lambda a, b: db_scalar(f"SELECT COUNT(*) FROM audit_logs WHERE created_at >= '{a:%Y-%m-%d %H:%M:%S}' AND created_at < '{b:%Y-%m-%d %H:%M:%S}';")
    early = int(q(start_utc, start_utc + dt.timedelta(hours=8)))
    db_today = int(q(start_utc, start_utc + dt.timedelta(days=1)))
    db_yday = int(q(start_utc - dt.timedelta(days=1), start_utc))
    r = O.get("/mold/audit-logs", query={"dateFrom": today.isoformat(), "dateTo": today.isoformat(), "pageSize": 1})
    C.check(f"dateFrom=dateTo={today}(北京) 总数 = 库内 UTC[{start_utc:%m-%d %H:%M}, +24h) 计数", dget(r, "total") == db_today,
            (dget(r, "total"), db_today))
    r = O.get("/mold/audit-logs", query={"dateFrom": yday.isoformat(), "dateTo": yday.isoformat(), "pageSize": 1})
    C.check(f"dateFrom=dateTo={yday} 总数 = 库内计数", dget(r, "total") == db_yday, (dget(r, "total"), db_yday))
    if early > 0:
        r = O.get("/mold/audit-logs", query={"dateFrom": today.isoformat(), "dateTo": today.isoformat(), "pageSize": 100,
                                             "sort": "createdAt", "order": "asc"})
        first = rows(r)[0].get("createdAt") if rows(r) else None
        C.check("北京 0–8 点记录出现在今天的结果中", first is not None, first)
    else:
        info(f"库内北京今天 0–8 点(UTC {start_utc:%m-%d %H:%M}–{start_utc + dt.timedelta(hours=8):%H:%M})无审计记录，"
             "以“今天总数=UTC 区间计数”验证边界换算")
    r = O.get("/mold/audit-logs", query={"entityId": S.get("p1"), "dateFrom": today.isoformat(), "dateTo": today.isoformat(), "pageSize": 100})
    _, allp = audit_rows(S.get("p1"))
    C.check("本次写入的审计记录都落在北京今天", len(rows(r)) == len(allp), (len(rows(r)), len(allp)))
    sample = rows(O.get("/mold/audit-logs", query={"pageSize": 1, "sort": "createdAt", "order": "desc"}))
    info(f"审计 createdAt 示例格式: {sample[0].get('createdAt') if sample else None}")
    r = O.get("/mold/audit-logs", query={"dateFrom": "2026/11/23"})
    C.check("日期格式非法 4xx", 400 <= r.status < 500, repr(r))


if __name__ == "__main__":
    print(f"RUN={RUN} BASE 前缀 {TAG}")
    for fn in [accounts, projects, stages, issues_and_completion, csv_export, finance_entries, reversal_void_correction,
               report_consistency, audit]:
        fn()
    res = C.summary()
    if INFO:
        print("\n信息/业务观察：")
        for x in INFO:
            print("  - " + x)
    with open(os.path.join(HERE, "last_report.json"), "w", encoding="utf-8") as f:
        json.dump({"run": RUN, **res, "info": INFO}, f, ensure_ascii=False, indent=2)
    sys.exit(1 if res["failed"] else 0)
