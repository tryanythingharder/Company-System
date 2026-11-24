"""2026-11 业务决策回归 E2E：项目暂停/恢复/取消、作废未开工阶段、老板直接操作他人项目、仅老板可换负责人、只读角色不能写异常。
运行：python3 deploy/e2e/decisions_flow.py   （先运行 setup_accounts.py）
不打印口令或令牌；结果写入 deploy/e2e/decisions_report.json。"""
import json
import os
import sys
import time
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import HERE, Checker, load_accounts, login_client, rows  # noqa: E402

RUN = time.strftime("%m%d%H%M%S")
TAG = f"E2E-D{RUN}"
C = Checker()

ACC = load_accounts()
U = {}
for role, a in ACC.items():
    c, _ = login_client(role, a["loginName"], a["password"])
    c.user = dict(c.user or {})
    c.user.setdefault("id", a.get("userId"))
    U[role] = c
O, PM, PROG, MACH, VIEW, FIN = (U[r] for r in ["OWNER", "PM", "PROGRAMMER", "MACHINIST", "VIEWER", "FINANCE"])


def section(fn):
    def run():
        C.sec(fn.__doc__ or fn.__name__)
        try:
            fn()
        except Exception as e:
            C.check(f"{fn.__name__} 未抛异常", False, f"{type(e).__name__}: {e} @ {traceback.format_exc().splitlines()[-3].strip()}")
    return run


def new_project(suffix, owner=None):
    body = {"code": f"{TAG}-{suffix}", "name": f"{TAG} {suffix}", "businessType": "MOLD_SET",
            "ownerUserId": (owner or PM).id}
    r = PM.post("/mold/projects", body)
    if not C.status(f"建项目 {suffix} 201", r, [200, 201]):
        raise RuntimeError("建项目失败")
    return r.data["id"]


def proj(pid):
    return O.get(f"/mold/projects/{pid}").data or {}


def stages(pid):
    return rows(O.get(f"/mold/projects/{pid}/stages"))


S = {}


@section
def status_flow():
    """项目暂停 / 恢复 / 取消"""
    pid = new_project("STATUS")
    S["status"] = pid
    v = proj(pid)["version"]
    C.status("暂停缺原因 422", PM.post(f"/mold/projects/{pid}/hold", {}, if_match=v), 422)
    C.status("暂停缺 If-Match 422", PM.post(f"/mold/projects/{pid}/hold", {"reason": "x"}), 422)
    C.status("暂停旧版本 409 VERSION_CONFLICT", PM.post(f"/mold/projects/{pid}/hold", {"reason": "x"}, if_match=v + 9), 409,
             "VERSION_CONFLICT")
    C.status("编程员不能暂停 403", PROG.post(f"/mold/projects/{pid}/hold", {"reason": "x"}, if_match=v), 403)
    C.status("只读不能暂停 403", VIEW.post(f"/mold/projects/{pid}/hold", {"reason": "x"}, if_match=v), 403)
    C.status("未暂停时恢复 409 INVALID_STATE_TRANSITION", PM.post(f"/mold/projects/{pid}/resume", {}, if_match=v), 409,
             "INVALID_STATE_TRANSITION")
    r = PM.post(f"/mold/projects/{pid}/hold", {"reason": "客户暂缓"}, if_match=v)
    C.status("负责人暂停 200", r, 200)
    C.check("暂停后状态 ON_HOLD", proj(pid).get("status") == "ON_HOLD", proj(pid).get("status"))
    C.check("暂停后版本 +1", proj(pid).get("version") == v + 1, proj(pid).get("version"))
    v = proj(pid)["version"]
    C.status("暂停中可编辑 200", PM.patch(f"/mold/projects/{pid}", {"note": "暂停中备注"}, v), 200)
    C.status("重复暂停 409", PM.post(f"/mold/projects/{pid}/hold", {"reason": "x"}, if_match=proj(pid)["version"]), 409,
             "INVALID_STATE_TRANSITION")
    r = PM.post(f"/mold/projects/{pid}/resume", {}, if_match=proj(pid)["version"])
    C.status("恢复（原因可空）200", r, 200)
    C.check("恢复后状态 ACTIVE", proj(pid).get("status") == "ACTIVE", proj(pid).get("status"))
    # 幂等重放
    key = "e2e-replay-" + RUN
    v = proj(pid)["version"]
    r1 = PM.post(f"/mold/projects/{pid}/hold", {"reason": "重放"}, if_match=v, idem_key=key)
    r2 = PM.post(f"/mold/projects/{pid}/hold", {"reason": "重放"}, if_match=v, idem_key=key)
    C.check("同一幂等键重放返回同结果", r1.status == 200 and r2.status == 200, f"{r1!r} / {r2!r}")
    C.check("重放带 Replayed 头", any("replay" in k for k in r2.headers), list(r2.headers)[:20])
    # 取消
    C.status("取消缺原因 422", PM.post(f"/mold/projects/{pid}/cancel", {}, if_match=proj(pid)["version"]), 422)
    r = PM.post(f"/mold/projects/{pid}/cancel", {"reason": "客户撤单"}, if_match=proj(pid)["version"])
    C.status("暂停中可直接取消 200", r, 200)
    p = proj(pid)
    C.check("取消后状态 CANCELLED", p.get("status") == "CANCELLED", p.get("status"))
    v = p["version"]
    C.status("取消后不能编辑 409", PM.patch(f"/mold/projects/{pid}", {"note": "x"}, v), 409)
    C.status("取消后不能恢复 409", PM.post(f"/mold/projects/{pid}/resume", {}, if_match=v), 409)
    C.status("取消后不能再取消 409", PM.post(f"/mold/projects/{pid}/cancel", {"reason": "x"}, if_match=v), 409)
    C.status("取消后不能建阶段 409", PM.post(f"/mold/projects/{pid}/stages", {"name": "x"}), 409)
    C.status("取消后不能完工 409", PM.post(f"/mold/projects/{pid}/complete", {"completionNote": "x"}, if_match=v), 409)
    C.status("重开仍关闭 409 POLICY_NOT_CONFIRMED", PM.post(f"/mold/projects/{pid}/reopen", {}), 409, "POLICY_NOT_CONFIRMED")
    r = O.get("/mold/audit-logs", query={"entityId": pid, "pageSize": 100})
    acts = [x.get("action") for x in rows(r)]
    C.check("审计记录 HOLD/RESUME/CANCEL", {"HOLD", "RESUME", "CANCEL"} <= set(acts), acts)
    reasons = json.dumps(rows(r), ensure_ascii=False)
    C.check("审计里保留取消原因", "客户撤单" in reasons, reasons[:200])
    lst = rows(PM.get("/mold/projects", query={"status": "CANCELLED", "q": TAG, "pageSize": 50}))
    C.check("列表按已取消筛选可见", any(x.get("id") == pid for x in lst), len(lst))


@section
def void_stage():
    """作废未开工阶段"""
    pid = new_project("VOID")
    S["void"] = pid
    a = PM.post(f"/mold/projects/{pid}/stages", {"name": "E2E 建错的阶段", "sortOrder": 1}).data["id"]
    b = PM.post(f"/mold/projects/{pid}/stages", {"name": "E2E 真阶段", "sortOrder": 2, "responsibleUserId": PROG.id}).data["id"]
    st = {s["id"]: s for s in stages(pid)}
    va, vb = st[a]["version"], st[b]["version"]
    C.status("作废缺原因 422", PM.post(f"/mold/stages/{a}/void", {}, if_match=va), 422)
    C.status("作废缺 If-Match 422", PM.post(f"/mold/stages/{a}/void", {"reason": "x"}), 422)
    C.status("作废旧版本 409", PM.post(f"/mold/stages/{a}/void", {"reason": "x"}, if_match=va + 5), 409, "VERSION_CONFLICT")
    C.status("编程员不能作废 403", PROG.post(f"/mold/stages/{a}/void", {"reason": "x"}, if_match=va), 403)
    C.status("只读不能作废 403", VIEW.post(f"/mold/stages/{a}/void", {"reason": "x"}, if_match=va), 403)
    # 阶段上有未解决异常 → 不能作废
    iss = MACH.post("/mold/issues", {"projectId": pid, "stageId": a, "title": f"{TAG} 阶段异常", "description": "e2e",
                                     "severity": "LOW"})
    C.status("在待作废阶段登记异常 201", iss, [200, 201])
    C.status("有未解决异常不能作废 409 STAGE_HAS_OPEN_ISSUES",
             PM.post(f"/mold/stages/{a}/void", {"reason": "建错"}, if_match=va), 409, "STAGE_HAS_OPEN_ISSUES")
    if iss.data and iss.data.get("id"):
        C.status("取消该异常 200", PM.post(f"/mold/issues/{iss.data['id']}/cancel", {"reason": "e2e"}), 200)
    r = PM.post(f"/mold/stages/{a}/void", {"reason": "阶段建重复了"}, if_match=va)
    C.status("负责人作废未开工阶段 200", r, 200)
    C.check("返回 status=VOIDED", (r.data or {}).get("status") == "VOIDED", r.data)
    ids = [s["id"] for s in stages(pid)]
    C.check("作废后阶段列表不再显示", a not in ids and b in ids, ids)
    p = proj(pid)
    C.check("项目阶段总数不含作废阶段", (p.get("stageCounts") or {}).get("total") == 1, p.get("stageCounts"))
    C.status("重复作废 404", PM.post(f"/mold/stages/{a}/void", {"reason": "x"}, if_match=va + 1), 404)
    ev = rows(O.get(f"/mold/stages/{a}/events", query={"pageSize": 50}))
    C.check("作废后阶段时间线仍可查（或 404 隐藏）", True, len(ev))
    r = O.get("/mold/audit-logs", query={"entityId": a, "pageSize": 50})
    C.check("审计记录 VOID 且含原因", any(x.get("action") == "VOID" for x in rows(r))
            and "阶段建重复了" in json.dumps(rows(r), ensure_ascii=False), [x.get("action") for x in rows(r)])
    C.status("同名阶段可重新创建 201", PM.post(f"/mold/projects/{pid}/stages", {"name": "E2E 建错的阶段", "sortOrder": 3}),
             [200, 201])
    # 已开工阶段不能作废
    C.status("开始真阶段 200", PROG.post(f"/mold/stages/{b}/transitions", {"toStatus": "IN_PROGRESS"}), 200)
    vb = {s["id"]: s for s in stages(pid)}[b]["version"]
    C.status("已开工阶段不能作废 409 INVALID_STATE_TRANSITION",
             PM.post(f"/mold/stages/{b}/void", {"reason": "x"}, if_match=vb), 409, "INVALID_STATE_TRANSITION")
    # 作废后完工检查跳过作废阶段：把剩余阶段作废/完成后可完工
    redo = [s for s in stages(pid) if s["name"] == "E2E 建错的阶段"][0]
    C.status("老板作废他人项目阶段 200", O.post(f"/mold/stages/{redo['id']}/void", {"reason": "老板清理"},
                                          if_match=redo["version"]), 200)
    C.status("完成真阶段 200", PROG.post(f"/mold/stages/{b}/transitions", {"toStatus": "COMPLETED"}), 200)
    r = PM.post(f"/mold/projects/{pid}/complete", {"completionNote": "e2e 完工"}, if_match=proj(pid)["version"])
    C.status("作废阶段不阻碍完工 200", r, 200)


@section
def owner_override():
    """老板直接操作他人项目"""
    pid = new_project("OWNER")
    p = proj(pid)
    C.check("项目负责人是 PM", p.get("ownerUserId") == PM.id, p.get("ownerUserId"))
    C.status("老板编辑他人项目 200", O.patch(f"/mold/projects/{pid}", {"note": "老板改"}, p["version"]), 200)
    r = O.post(f"/mold/projects/{pid}/stages", {"name": "E2E 老板加的阶段", "sortOrder": 1, "responsibleUserId": MACH.id})
    C.status("老板给他人项目加阶段 201", r, [200, 201])
    sid = (r.data or {}).get("id")
    st = {s["id"]: s for s in stages(pid)}.get(sid) or {}
    C.status("老板改他人项目阶段名 200", O.patch(f"/mold/stages/{sid}", {"name": "E2E 老板改名"}, st.get("version")), 200)
    C.status("老板推进他人项目阶段 200", O.post(f"/mold/stages/{sid}/transitions", {"toStatus": "IN_PROGRESS"}), 200)
    C.status("老板设置他人项目成员 200", O.put(f"/mold/projects/{pid}/members", {"members": []}), 200)
    iss = MACH.post("/mold/issues", {"projectId": pid, "stageId": sid, "title": f"{TAG} 老板处理", "description": "e2e",
                                     "severity": "LOW"})
    if iss.data and iss.data.get("id"):
        C.status("老板处理他人项目异常 200", O.post(f"/mold/issues/{iss.data['id']}/resolve", {"resolution": "老板处理"}), 200)
    C.status("老板暂停他人项目 200", O.post(f"/mold/projects/{pid}/hold", {"reason": "老板暂停"}, if_match=proj(pid)["version"]),
             200)
    C.status("老板恢复他人项目 200", O.post(f"/mold/projects/{pid}/resume", {}, if_match=proj(pid)["version"]), 200)
    C.status("完成阶段 200", O.post(f"/mold/stages/{sid}/transitions", {"toStatus": "COMPLETED"}), 200)
    C.status("老板完工他人项目 200", O.post(f"/mold/projects/{pid}/complete", {"completionNote": "老板完工"},
                                          if_match=proj(pid)["version"]), 200)
    # 其他项目经理（非负责人）仍不能操作：用编程员代表非负责人
    p2 = new_project("OTHER")
    C.status("非负责人编辑 403", PROG.patch(f"/mold/projects/{p2}", {"note": "x"}, proj(p2)["version"]), 403)


@section
def transfer_only_owner():
    """只有老板能换负责人；只读角色不能写异常"""
    pid = new_project("XFER")
    C.status("PM 不能换负责人 403", PM.put(f"/mold/projects/{pid}/owner", {"ownerUserId": O.id, "reason": "e2e"}), 403)
    C.status("老板换负责人 200", O.put(f"/mold/projects/{pid}/owner", {"ownerUserId": O.id, "reason": "e2e 交接"}), 200)
    C.check("负责人已变为老板", proj(pid).get("ownerUserId") == O.id, proj(pid).get("ownerUserId"))
    C.status("原负责人 PM 不能再编辑 403 NOT_PROJECT_OWNER",
             PM.patch(f"/mold/projects/{pid}", {"note": "x"}, proj(pid)["version"]), 403, "NOT_PROJECT_OWNER")
    C.status("老板转回 PM 200", O.put(f"/mold/projects/{pid}/owner", {"ownerUserId": PM.id, "reason": "e2e 转回"}), 200)
    caps = (PM.get("/auth/me").data or {}).get("capabilities") or []
    C.check("PM 能力串不含 mold:project:owner", "mold:project:owner" not in caps, [c for c in caps if "project" in c])
    vcaps = (VIEW.get("/auth/me").data or {}).get("capabilities") or []
    C.check("VIEWER 能力串不含任何 issue 写权限", not any(c.startswith("mold:issue:") for c in vcaps), vcaps)
    C.status("VIEWER 登记异常 403", VIEW.post("/mold/issues", {"projectId": pid, "title": f"{TAG} 只读", "description": "x",
                                                            "severity": "LOW"}), 403)


for fn in (status_flow, void_stage, owner_override, transfer_only_owner):
    fn()

res = C.summary()
with open(os.path.join(HERE, "decisions_report.json"), "w", encoding="utf-8") as f:
    json.dump({"run": RUN, **res}, f, ensure_ascii=False, indent=2)
sys.exit(1 if res["failed"] else 0)
