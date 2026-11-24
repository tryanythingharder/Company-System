"""角色 × 接口 权限矩阵：期望 = 线上 sys_role_menu 授权（权限种子） ∘ @PreAuthorize（任一匹配即放行）。

判定：403 且 errorCode=FORBIDDEN 视为“被权限拦截”；其余 <500 视为“通过鉴权”（写接口用随机 id / 空体，
只会落到 404/422，不改业务数据）。另跑一组“与角色说明不一致”的专项检查。

运行：python3 deploy/e2e/perm_matrix.py
"""
import os
import re
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from purchase_lib import (ROLES, Checker, accounts, client, db, make_confirmed_purchase, make_supplier,  # noqa
                          pay_body, rows, save_report, tag, upload)

SRC = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "pig", "pig-boot",
                                   "src", "main", "java"))
PDF = b"%PDF-1.4\n%%EOF\n"
R = str(uuid.uuid4())  # 不存在的 id


def endpoints(P, S, PUR):
    """(模块, 方法, 路径, 所需权限(任一), 额外参数)。perms=None 表示仅需登录。"""
    E = []
    a = E.append
    a(("项目", "GET", "/mold/projects", ["project:list"], {}))
    a(("项目", "GET", "/mold/projects/export", ["project:export"], {}))
    a(("项目", "GET", f"/mold/projects/{P}", ["project:list"], {}))
    a(("项目", "POST", "/mold/projects", ["project:create"], {}))
    a(("项目", "PATCH", f"/mold/projects/{R}", ["project:update"], {}))
    a(("项目", "PUT", f"/mold/projects/{R}/owner", ["project:owner"], {}))
    a(("项目", "GET", f"/mold/projects/{P}/members", ["project:member", "project:list"], {}))
    a(("项目", "PUT", f"/mold/projects/{R}/members", ["project:member"], {}))
    a(("项目", "GET", f"/mold/projects/{P}/issues", ["project:list"], {}))
    a(("项目", "POST", f"/mold/projects/{R}/complete", ["project:complete"], {}))
    a(("项目", "POST", f"/mold/projects/{R}/reopen", ["project:reopen"], {}))
    a(("阶段", "GET", f"/mold/projects/{P}/stages", ["project:list"], {}))
    a(("阶段", "POST", f"/mold/projects/{R}/stages", ["stage:create"], {}))
    a(("阶段", "PATCH", f"/mold/stages/{R}", ["stage:update"], {}))
    a(("阶段", "POST", f"/mold/stages/{R}/transitions", ["stage:transition"], {}))
    a(("阶段", "GET", f"/mold/stages/{S}/events", ["project:list"], {}))
    a(("阶段", "GET", "/mold/me/stages", ["project:list"], {}))
    a(("异常", "GET", "/mold/issues", ["project:list"], {}))
    a(("异常", "POST", "/mold/issues", ["issue:create"], {}))
    a(("异常", "PATCH", f"/mold/issues/{R}", ["issue:update"], {}))
    a(("异常", "POST", f"/mold/issues/{R}/resolve", ["issue:handle"], {}))
    a(("异常", "POST", f"/mold/issues/{R}/cancel", ["issue:cancel"], {}))
    a(("收支", "GET", "/mold/cash-entries", ["cash:list"], {}))
    a(("收支", "POST", "/mold/cash-entries", ["cash:create"], {}))
    a(("收支", "GET", f"/mold/cash-entries/{R}", ["cash:list"], {}))
    a(("收支", "PATCH", f"/mold/cash-entries/{R}", ["cash:update"], {}))
    for act, perm in (("void", "cash:void"), ("reverse", "cash:reverse"), ("reconcile", "cash:reconcile"),
                      ("unreconcile", "cash:unreconcile"), ("corrections/preview", "correction:preview"),
                      ("corrections", "correction:apply")):
        a(("收支", "POST", f"/mold/cash-entries/{R}/{act}", [perm], {}))
    a(("采购", "GET", "/mold/purchases", ["purchase:list"], {}))
    a(("采购", "POST", "/mold/purchases", ["purchase:create"], {}))
    a(("采购", "GET", f"/mold/purchases/{PUR}", ["purchase:list"], {}))
    a(("采购", "PATCH", f"/mold/purchases/{R}", ["purchase:update"], {}))
    a(("采购", "POST", f"/mold/purchases/{R}/confirm", ["purchase:confirm"], {}))
    a(("采购", "POST", f"/mold/purchases/{R}/void", ["purchase:void"], {}))
    a(("采购", "GET", f"/mold/purchases/{PUR}/summary", ["purchase:list"], {}))
    a(("采购", "GET", f"/mold/purchases/{PUR}/receipts", ["purchase:list"], {}))
    a(("采购", "POST", f"/mold/purchases/{R}/receipts", ["receipt:create"], {}))
    a(("采购", "POST", f"/mold/receipts/{R}/void", ["receipt:void"], {}))
    a(("采购", "GET", f"/mold/purchases/{PUR}/invoices", ["purchase:list"], {}))
    a(("采购", "POST", f"/mold/purchases/{R}/invoices", ["invoice:create"], {}))
    a(("采购", "POST", f"/mold/invoices/{R}/void", ["invoice:void"], {}))
    a(("采购", "GET", f"/mold/purchases/{PUR}/payments", ["cash:list"], {}))
    a(("采购", "POST", f"/mold/purchases/{R}/payments", ["cash:create"], {}))
    a(("采购", "POST", f"/mold/purchases/{R}/refunds", ["cash:create"], {}))
    a(("往来单位", "GET", "/mold/counterparties", ["purchase:list", "cash:create"], {}))
    a(("往来单位", "POST", "/mold/counterparties", ["counterparty:create"], {}))
    a(("往来单位", "PATCH", f"/mold/counterparties/{R}", ["counterparty:update"], {}))
    a(("账号", "GET", "/mold/users", ["user:list"], {}))
    a(("账号", "GET", f"/mold/users/{R}", ["user:list"], {}))
    a(("账号", "POST", "/mold/users", ["user:create"], {}))
    a(("账号", "PATCH", f"/mold/users/{R}", ["user:update"], {}))
    a(("账号", "PUT", f"/mold/users/{R}/roles", ["user:role"], {}))
    a(("账号", "POST", f"/mold/users/{R}/password", ["user:password-reset"], {}))
    a(("账号", "GET", "/mold/directory/users", ["directory:list"], {}))
    a(("账号", "GET", "/mold/system/role-capabilities", None, {}))
    a(("审计", "GET", "/mold/audit-logs", ["audit:list"], {}))
    a(("报表", "GET", "/mold/reports/project-cash", ["cash:viewAll"], {}))
    a(("报表", "GET", "/mold/reports/cash-summary", ["cash:viewAll"], {}))
    a(("附件", "GET", "/mold/attachments", ["attachment:download"], {"query": {"ownerType": "PROJECT", "ownerId": P}}))
    a(("附件", "GET", f"/mold/attachments/{R}", ["attachment:download"], {}))
    a(("附件", "GET", f"/mold/attachments/{R}/download", ["attachment:download"], {}))
    a(("附件", "UPLOAD", "/mold/attachments", ["attachment:upload"], {}))
    a(("附件", "DELETE", f"/mold/attachments/{R}", ["attachment:delete"], {}))
    return E


def call(c, method, path, extra):
    if method == "GET":
        return c.get(path, **extra)
    if method == "UPLOAD":  # 不存在的归属对象：通过鉴权的角色得 422
        return upload(c, "PROJECT", R, "OTHER", "p.pdf", PDF, "application/pdf")
    if method == "POST":
        return c.post(path, {})
    if method == "PATCH":
        return c.patch(path, {}, 1)
    if method == "PUT":
        return c.put(path, {}, if_match=1)
    return c.delete(path)


def grants():
    out = {}
    for role, perm in db("SELECT r.role_code, m.permission FROM sys_role_menu rm JOIN sys_role r ON r.role_id=rm.role_id "
                         "JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE r.role_id BETWEEN 10 AND 15 "
                         "AND m.permission IS NOT NULL AND m.del_flag='0'"):
        out.setdefault(role, set()).add(perm.replace("mold:", "", 1))
    return out


def used_permissions():
    used = set()
    for root, _, files in os.walk(SRC):
        if "/mold" not in root.replace("\\", "/"):
            continue
        for f in files:
            if f.endswith(".java"):
                text = open(os.path.join(root, f), encoding="utf-8").read()
                used |= set(re.findall(r"['\"]mold:([a-z]+:[A-Za-z-]+)['\"]", text))
    return used


def fixtures():
    acc = accounts()
    owner, fin, pm = client("OWNER"), client("FINANCE"), client("PM")
    r = owner.post("/mold/projects", {"code": f"E2E-PRJ-{tag()}", "name": "E2E 权限矩阵项目", "businessType": "MOLD_SET",
                                      "ownerUserId": acc["PM"]["userId"], "dueDate": "2026-12-31"})
    if r.status != 201:
        raise RuntimeError(f"建项目失败 {r!r}")
    P = r.data["id"]
    r = pm.post(f"/mold/projects/{P}/stages", {"name": "E2E 编程", "sortOrder": 1,
                                               "responsibleUserId": acc["PROGRAMMER"]["userId"]})
    S = r.data["id"] if r.status == 201 else R
    if r.status != 201:
        print("  [fixture] 建阶段失败，阶段事件用随机 id:", r)
    cp = make_supplier(fin)
    PUR, _ = make_confirmed_purchase(fin, cp)
    fin.post(f"/mold/purchases/{PUR}/payments", pay_body("100", note="E2E-perm-finance-payment"))
    return P, S, PUR


def main():
    ck = Checker()
    G = grants()
    for role in ROLES:
        client(role)  # 每个账号只登录一次
    P, S, PUR = fixtures()
    E = endpoints(P, S, PUR)

    matrix = {}
    ck.sec(f"权限矩阵（{len(E)} 个接口 × {len(ROLES)} 角色）")
    for mod, method, path, perms, extra in E:
        key = f"{method} " + path.replace(R, "{rand}").replace(P, "{P}").replace(S, "{S}").replace(PUR, "{PUR}")
        for role in ROLES:
            expect = perms is None or any(p in G.get(role, set()) for p in perms)
            resp = call(client(role), method, path, extra)
            denied = resp.status == 403 and resp.error_code == "FORBIDDEN"
            ok = (not denied and resp.status < 500 and resp.status != 401) if expect else denied
            matrix.setdefault(key, {})[role] = f"{'允许' if expect else '拒绝'}:{resp.status}"
            ck.check(f"[{mod}] {role:10s} {key} 期望{'放行' if expect else '403'}", ok, repr(resp))

    ck.sec("专项确认")
    for role in ("PM", "PROGRAMMER", "MACHINIST"):
        r = client(role).get("/mold/counterparties", query={"pageSize": 5})
        ck.check(f"{role} 可读 GET /counterparties", r.status == 200, repr(r))
    for role in ROLES:
        r = client(role).get(f"/mold/projects/{P}/members")
        ck.check(f"{role} 可读 GET /projects/{{id}}/members", r.status == 200, repr(r))

    viewer_writes = [k for k, v in matrix.items() if not k.startswith("GET ") and v["VIEWER"].startswith("允许")]
    print("  VIEWER 被种子授予的写接口:", viewer_writes)
    ck.check("VIEWER 不能写任何业务数据（种子层）", not viewer_writes, viewer_writes)
    acc = accounts()
    viewer = client("VIEWER")
    r = viewer.post("/mold/issues", {"projectId": P, "title": "E2E VIEWER 写入探测", "description": "E2E",
                                     "severity": "LOW"})
    ck.check("VIEWER 实际不能登记异常", r.status in (403, 404), repr(r))
    if r.status == 201:
        iid = r.data["id"]
        rr = viewer.patch(f"/mold/issues/{iid}", {"title": "E2E VIEWER 改标题"}, r.data.get("version", 1))
        ck.check("VIEWER 实际不能编辑异常", rr.status in (403, 404), repr(rr))
        rc = viewer.post(f"/mold/issues/{iid}/cancel", {"reason": "E2E 清理"})
        ck.check("VIEWER 实际不能作废异常", rc.status in (403, 404), repr(rc))

    r = viewer.get("/mold/projects/export")
    ck.check("VIEWER 不应能导出项目（种子仅 OWNER/PM 有 project:export）", r.status == 403, f"{r.status}")
    # 采购付款 DTO 不含 createdBy，改为与“按本人过滤”的 /cash-entries 列表对照
    pm = client("PM")
    via_purchase = len(rows(pm.get(f"/mold/purchases/{PUR}/payments")))
    via_cash = len(rows(pm.get("/mold/cash-entries", query={"purchaseId": PUR})))
    ck.check("PM（无 viewAll）不应经 /purchases/{id}/payments 看到 FINANCE 录入的付款（流水可见性决策 B）",
             via_purchase == via_cash, f"/purchases/{{id}}/payments={via_purchase} 条，/cash-entries={via_cash} 条")

    unused = sorted(set().union(*G.values()) - used_permissions())
    print("  种子已授但后端未校验的权限串:", unused)

    res = ck.summary()
    res["matrix"] = matrix
    res["viewerWrites"] = viewer_writes
    res["unusedPermissions"] = unused
    save_report("perm_matrix", res)
    print("\n矩阵（期望:实际状态码）已写入 deploy/e2e/perm_matrix_report.json")
    return 0 if not res["failed"] else 1


if __name__ == "__main__":
    sys.exit(main())
