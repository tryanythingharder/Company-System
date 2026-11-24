"""采购付款并发：余额抢付、同键并发、付款×更正并发、退款×作废并发。最终状态以数据库为准。

运行：python3 deploy/e2e/purchase_concurrency.py
"""
import os
import re
import sys
import threading
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from purchase_lib import (Checker, client, db, db_one, idem, make_confirmed_purchase, make_supplier, pay_body,  # noqa
                          save_report, tag)


def one_item(amt):
    return [{"lineNo": 1, "name": "E2E 并发料", "qty": "1", "unit": "件", "unitPrice": amt}]


def burst(n, fn):
    """n 个线程在同一栅栏后同时调用 fn(i)。"""
    barrier = threading.Barrier(n)
    out = [None] * n

    def run(i):
        barrier.wait()
        try:
            out[i] = fn(i)
        except Exception as e:
            out[i] = e
    ts = [threading.Thread(target=run, args=(i,)) for i in range(n)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    return out


def label(r):
    return f"{r.status} {r.error_code or ''}".strip() if hasattr(r, "status") else type(r).__name__


def describe(results):
    return dict(Counter(label(r) for r in results))


def net_paid(pid):
    return float(db_one("SELECT COALESCE(SUM(CASE WHEN direction='OUT' THEN amount ELSE -amount END),0) "
                        f"FROM cash_entries WHERE purchase_id='{pid}' AND status='CONFIRMED'"))


def last_deadlock():
    text = " ".join(" ".join(r) for r in db("SHOW ENGINE INNODB STATUS"))
    m = re.search(r"LATEST DETECTED DEADLOCK\\n-+\\n(\d{4}-\d\d-\d\d [\d:]+)", text)
    return m.group(1) if m else None


def main():
    ck = Checker()
    fin = client("FINANCE")
    cp = make_supplier(fin)
    deadlock_before = last_deadlock()

    # 1. 余额 100，10×60 抢付
    ck.sec("余额 100，10 线程各付 60")
    pid, _ = make_confirmed_purchase(fin, cp, one_item("1000"))
    ck.status("先付 900", fin.post(f"/mold/purchases/{pid}/payments", pay_body("900")), 201)
    res = burst(10, lambda i: fin.post(f"/mold/purchases/{pid}/payments", pay_body("60", note=f"E2E-race-{i}")))
    dist = describe(res)
    print("  响应分布:", dist)
    ok = sum(1 for r in res if getattr(r, "status", 0) == 201)
    ck.check("恰好 1 笔成功", ok == 1, dist)
    ck.check("其余为 422 OVER_PAYMENT / 409 可重试，无 5xx",
             all(getattr(r, "status", 0) in (201, 409, 422) for r in res), dist)
    paid = net_paid(pid)
    ck.check("库中净付 = 960 ≤ 1000", abs(paid - 960) < 0.001, f"净付 {paid}")
    n = db_one(f"SELECT COUNT(*) FROM cash_entries WHERE purchase_id='{pid}' AND note LIKE 'E2E-race-%'")
    ck.check("库中抢付流水仅 1 条", n == "1", n)
    s = fin.get(f"/mold/purchases/{pid}/summary").data
    ck.check("summary.paidAmount 与库一致", s["paidAmount"] == f"{paid:.2f}", s["paidAmount"])

    # 2. 同一幂等键 10 线程并发
    ck.sec("同一幂等键 10 线程并发提交")
    pid2, _ = make_confirmed_purchase(fin, cp, one_item("1000"))
    key = idem()
    note = f"E2E-samekey-{tag()}"
    res = burst(10, lambda i: fin.post(f"/mold/purchases/{pid2}/payments", pay_body("100", note=note), idem_key=key))
    dist = describe(res)
    print("  响应分布:", dist)
    created = {r.data.get("cashEntryId") for r in res if getattr(r, "status", 0) == 201 and r.data}
    ck.check("所有 201 指向同一流水", len(created) == 1, created)
    ck.check("其余只为 201 重放或 409 处理中，无 5xx", all(getattr(r, "status", 0) in (201, 409) for r in res), dist)
    n = db_one(f"SELECT COUNT(*) FROM cash_entries WHERE purchase_id='{pid2}' AND note='{note}'")
    ck.check("库中只入账 1 次", n == "1", n)
    ck.check("净付 = 100", abs(net_paid(pid2) - 100) < 0.001, net_paid(pid2))
    nk = db_one(f"SELECT COUNT(*) FROM idempotency_keys WHERE `key`='{key}'")
    ck.check("idempotency_keys 只 1 行", nk == "1", nk)
    if created:
        eid = next(iter(created))
        na = db_one(f"SELECT COUNT(*) FROM audit_logs WHERE entity_id='{eid}'")
        ck.check("流水审计只 1 条", na == "1", na)

    # 3. 付款 × 更正 并发
    ck.sec("付款与更正并发（各自合法、合起来超付）")
    rounds, overpaid, retry, five_xx = 5, [], 0, 0
    for k in range(rounds):
        pid3, _ = make_confirmed_purchase(fin, cp, one_item("1000"))
        eid = fin.post(f"/mold/purchases/{pid3}/payments", pay_body("600")).data["cashEntryId"]
        ver = fin.get(f"/mold/cash-entries/{eid}").data["version"]

        def act(i, pid3=pid3, eid=eid, ver=ver):
            if i == 0:
                return fin.post(f"/mold/cash-entries/{eid}/corrections",
                                {"reason": "E2E 并发更正", "replacement": {"amount": "900"}}, if_match=ver)
            return fin.post(f"/mold/purchases/{pid3}/payments", pay_body("300", note="E2E-vs-correction"))
        res = burst(2, act)
        print(f"  第 {k + 1} 轮: 更正={label(res[0])} 付款={label(res[1])}")
        retry += sum(1 for r in res if getattr(r, "error_code", None) == "RETRYABLE_CONFLICT")
        five_xx += sum(1 for r in res if not hasattr(r, "status") or r.status >= 500)
        paid = net_paid(pid3)
        if paid > 1000.001:
            overpaid.append((pid3, paid))
        ck.check(f"第 {k + 1} 轮恰好一方成功", sum(1 for r in res if getattr(r, "status", 0) in (200, 201)) == 1,
                 describe(res))
    ck.check("无超付（净付 ≤ 采购额）", not overpaid, overpaid)
    ck.check("无 5xx", five_xx == 0, five_xx)
    ck.check("无死锁转出的 409 RETRYABLE_CONFLICT", retry == 0, retry)
    deadlock_after = last_deadlock()
    ck.check("InnoDB 无新死锁记录", deadlock_after == deadlock_before, f"before={deadlock_before} after={deadlock_after}")

    # 4. 退款 × 作废付款 并发
    ck.sec("退款与作废付款并发")
    pid4, _ = make_confirmed_purchase(fin, cp, one_item("1000"))
    a = fin.post(f"/mold/purchases/{pid4}/payments", pay_body("500")).data["cashEntryId"]
    fin.post(f"/mold/purchases/{pid4}/payments", pay_body("500"))

    def act4(i):
        if i == 0:
            return fin.post(f"/mold/cash-entries/{a}/void", {"reason": "E2E 并发作废"})
        return fin.post(f"/mold/purchases/{pid4}/refunds", pay_body("800"))
    res = burst(2, act4)
    print(f"  作废={label(res[0])} 退款={label(res[1])}")
    paid = net_paid(pid4)
    ck.check("净付不为负", paid >= -0.001, f"净付 {paid}")

    res = ck.summary()
    save_report("purchase_concurrency", res)
    return 0 if not res["failed"] else 1


if __name__ == "__main__":
    sys.exit(main())
