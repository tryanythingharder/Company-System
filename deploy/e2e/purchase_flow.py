"""采购三段式端到端：往来单位 → 采购 → 确认 → 收货 → 开票 → 付款/退款 → 作废链 → summary 核对 → 附件。

运行：python3 deploy/e2e/purchase_flow.py（可重复运行，测试数据以 E2E 为前缀）
"""
import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from purchase_lib import (TODAY, Checker, client, db, db_one, idem, make_supplier, pay_body, purchase_body,  # noqa
                          rows, save_report, tag, upload)

PDF = b"%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n"
PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 32


def main():
    ck = Checker()
    fin = client("FINANCE")
    owner = client("OWNER")

    # ------------------------------------------------------------ 往来单位
    ck.sec("往来单位")
    cp = make_supplier(fin)
    ck.check("创建供应商", bool(cp))
    r = fin.post("/mold/counterparties", {"name": f"E2E客户-{tag()}", "type": "CUSTOMER"})
    ck.status("创建客户", r, [200, 201])
    customer = r.data.get("id") if r.data else None
    r = fin.post("/mold/purchases", purchase_body(customer))
    ck.status("客户类往来单位不能作为采购供应商", r, 422)
    r = fin.get("/mold/counterparties", query={"type": "SUPPLIER", "pageSize": 100})
    ck.status("FINANCE 列表往来单位", r, 200)

    # ------------------------------------------------------------ 创建 + 幂等
    ck.sec("采购创建与幂等")
    items = [{"lineNo": 1, "name": "E2E 模架钢", "qty": "10", "unit": "块", "unitPrice": "100"},
             {"lineNo": 2, "name": "E2E 顶针", "qty": "5", "unit": "支", "unitPrice": "200"}]
    body = purchase_body(cp, items)
    key = idem()
    r1 = fin.post("/mold/purchases", body, idem_key=key)
    ck.status("创建采购 201", r1, 201)
    pid = r1.data["id"]
    ck.check("金额=明细合计 2000.00", r1.data.get("amountTotal") == "2000.00", r1.data.get("amountTotal"))
    ck.check("初始为 DRAFT", r1.data.get("status") == "DRAFT", r1.data.get("status"))
    r2 = fin.post("/mold/purchases", body, idem_key=key)
    ck.check("同键同体重放：状态相同且标记 Idempotent-Replayed",
             r2.status == 201 and r2.headers.get("idempotent-replayed") == "true", repr(r2))
    ck.check("同键同体重放：返回同一采购 id 与同一内容", (r2.data or {}) == r1.data, "内容不一致")
    ck.check("同键同体重放：库中只有 1 条",
             db_one(f"SELECT COUNT(*) FROM purchases WHERE code='{body['code']}'") == "1")
    other = dict(body, note="E2E-另一请求体")
    ck.status("同键不同体被拒", fin.post("/mold/purchases", other, idem_key=key), 409, "IDEMPOTENCY_KEY_REUSED")
    ck.status("缺少 Idempotency-Key 422", fin.post("/mold/purchases", purchase_body(cp), idem_key=None), 422)
    ck.status("采购编号重复 409", fin.post("/mold/purchases", dict(body, note="dup")), 409, "PURCHASE_CODE_DUPLICATE")

    # ------------------------------------------------------------ 草稿编辑与确认
    ck.sec("草稿编辑与确认")
    ck.status("草稿不能收货", fin.post(f"/mold/purchases/{pid}/receipts", {
        "receivedOn": TODAY, "items": [{"purchaseItemId": "x" * 36, "qtyReceived": "1"}]}), 409)
    ck.status("草稿不能付款", fin.post(f"/mold/purchases/{pid}/payments", pay_body("1")), 409)
    v = r1.data["version"]
    ck.status("PATCH 缺 If-Match 422", fin.call("PATCH", f"/mold/purchases/{pid}", body={"note": "x"}), 422)
    ck.status("PATCH 版本不符 409", fin.patch(f"/mold/purchases/{pid}", {"note": "x"}, v + 5), 409, "VERSION_CONFLICT")
    r = fin.patch(f"/mold/purchases/{pid}", {"note": "E2E 草稿备注"}, v)
    ck.status("PATCH 草稿备注", r, 200)
    r = fin.post(f"/mold/purchases/{pid}/confirm")
    ck.status("确认采购", r, 200)
    ck.check("确认后状态 CONFIRMED", (r.data or {}).get("status") == "CONFIRMED", repr(r))
    ck.status("重复确认 409", fin.post(f"/mold/purchases/{pid}/confirm"), 409, "INVALID_STATE_TRANSITION")
    ver = (r.data or {}).get("version")
    ck.status("确认后改明细被拒", fin.patch(f"/mold/purchases/{pid}", {"items": items[:1]}, ver), 409,
              "POLICY_NOT_CONFIRMED")
    detail = fin.get(f"/mold/purchases/{pid}").data
    line = {i["lineNo"]: i["id"] for i in detail["items"]}

    # ------------------------------------------------------------ 收货
    ck.sec("收货")
    r = fin.post(f"/mold/purchases/{pid}/receipts", {"receivedOn": TODAY, "items": [
        {"purchaseItemId": line[1], "qtyReceived": "4"}]})
    ck.status("部分收货 4/10", r, 201)
    receipt1 = r.data["id"]
    s = fin.get(f"/mold/purchases/{pid}/summary").data
    ck.check("receiptStatus=PARTIAL", s.get("receiptStatus") == "PARTIAL", s.get("receiptStatus"))
    ck.status("超量收货 4+7>10 被拒", fin.post(f"/mold/purchases/{pid}/receipts", {"receivedOn": TODAY, "items": [
        {"purchaseItemId": line[1], "qtyReceived": "7"}]}), 422, "OVER_RECEIPT")
    ck.status("同一收货单重复明细被拒", fin.post(f"/mold/purchases/{pid}/receipts", {"receivedOn": TODAY, "items": [
        {"purchaseItemId": line[2], "qtyReceived": "1"}, {"purchaseItemId": line[2], "qtyReceived": "1"}]}), 422)
    r = fin.post(f"/mold/purchases/{pid}/receipts", {"receivedOn": TODAY, "items": [
        {"purchaseItemId": line[1], "qtyReceived": "6"}, {"purchaseItemId": line[2], "qtyReceived": "5"}]})
    ck.status("补足收货", r, 201)
    receipt2 = r.data["id"] if r.data else None
    s = fin.get(f"/mold/purchases/{pid}/summary").data
    ck.check("receiptStatus=FULL", s.get("receiptStatus") == "FULL", s.get("receiptStatus"))

    # ------------------------------------------------------------ 发票
    ck.sec("发票")
    inv_no = f"E2E-INV-{tag()}"
    inv = {"counterpartyId": cp, "invoiceNo": inv_no, "invoiceType": "VAT_SPECIAL", "issueDate": TODAY,
           "amountTotal": "1200", "taxAmount": "138.05", "currency": "CNY"}
    r = fin.post(f"/mold/purchases/{pid}/invoices", inv)
    ck.status("开票 1200", r, 201)
    invoice1 = r.data["id"]
    ck.status("超额开票 1200+900>2000 被拒", fin.post(f"/mold/purchases/{pid}/invoices",
              dict(inv, invoiceNo=f"E2E-INV-{tag()}", amountTotal="900", taxAmount=None)), 422, "OVER_INVOICE")
    ck.status("发票号重复被拒", fin.post(f"/mold/purchases/{pid}/invoices", dict(inv, amountTotal="100",
              taxAmount=None)), 409, "DUPLICATE_INVOICE_NO")
    ck.status("发票币种不一致被拒", fin.post(f"/mold/purchases/{pid}/invoices",
              dict(inv, invoiceNo=f"E2E-INV-{tag()}", amountTotal="10", taxAmount=None, currency="USD")), 422,
              "CURRENCY_MISMATCH")
    ck.status("税额大于价税合计被拒", fin.post(f"/mold/purchases/{pid}/invoices",
              dict(inv, invoiceNo=f"E2E-INV-{tag()}", amountTotal="10", taxAmount="11")), 422)

    # ------------------------------------------------------------ 付款 / 退款
    ck.sec("付款与退款")
    pkey = idem()
    r = fin.post(f"/mold/purchases/{pid}/payments", pay_body("1500"), idem_key=pkey)
    ck.status("付款 1500", r, 201)
    pay1 = r.data["cashEntryId"]
    ck.check("付款后 PARTIAL / 1500.00", r.data.get("paymentStatus") == "PARTIAL" and
             r.data.get("paidAmount") == "1500.00", r.data)
    rr = fin.post(f"/mold/purchases/{pid}/payments", pay_body("1500"), idem_key=pkey)
    ck.check("付款同键重放返回同一流水", rr.status == 201 and (rr.data or {}).get("cashEntryId") == pay1, repr(rr))
    ck.status("超付 1500+600>2000 被拒", fin.post(f"/mold/purchases/{pid}/payments", pay_body("600")), 422,
              "OVER_PAYMENT")
    ck.status("付款币种不一致被拒", fin.post(f"/mold/purchases/{pid}/payments", pay_body("10", "USD")), 422,
              "CURRENCY_MISMATCH")
    ck.status("付款金额 0 被拒", fin.post(f"/mold/purchases/{pid}/payments", pay_body("0")), 422)
    ck.status("付款金额 3 位小数被拒", fin.post(f"/mold/purchases/{pid}/payments", pay_body("1.001")), 422)
    r = fin.post(f"/mold/purchases/{pid}/payments", pay_body("500"))
    ck.status("付款 500 付清", r, 201)
    pay2 = r.data["cashEntryId"]
    ck.check("付清后 PAID / 2000.00", r.data.get("paymentStatus") == "PAID" and r.data.get("paidAmount") == "2000.00",
             r.data)
    ck.status("付清后再付 0.01 被拒", fin.post(f"/mold/purchases/{pid}/payments", pay_body("0.01")), 422,
              "OVER_PAYMENT")
    r = fin.post(f"/mold/purchases/{pid}/refunds", pay_body("300"))
    ck.status("退款 300", r, 201)
    refund1 = r.data["cashEntryId"]
    ck.check("退款后 PARTIAL / 1700.00", r.data.get("paymentStatus") == "PARTIAL" and
             r.data.get("paidAmount") == "1700.00", r.data)
    ck.status("退成负数 1700-1800 被拒", fin.post(f"/mold/purchases/{pid}/refunds", pay_body("1800")), 422,
              "OVER_PAYMENT")
    ck.status("退款币种不一致被拒", fin.post(f"/mold/purchases/{pid}/refunds", pay_body("1", "EUR")), 422,
              "CURRENCY_MISMATCH")

    # ------------------------------------------------------------ summary 核对
    ck.sec("summary 与明细一致")
    s = fin.get(f"/mold/purchases/{pid}/summary").data
    pays = rows(fin.get(f"/mold/purchases/{pid}/payments"))
    invs = rows(fin.get(f"/mold/purchases/{pid}/invoices"))
    net = sum(float(p["amount"]) * (1 if p["direction"] == "OUT" else -1) for p in pays if p["status"] == "CONFIRMED")
    invoiced = sum(float(i["amountTotal"]) for i in invs if i["status"] == "ACTIVE")
    db_net = db_one("SELECT COALESCE(SUM(CASE WHEN direction='OUT' THEN amount ELSE -amount END),0) FROM cash_entries "
                    f"WHERE purchase_id='{pid}' AND status='CONFIRMED'")
    ck.check("paidAmount = 付款明细净额 = 库", s["paidAmount"] == f"{net:.2f}" == f"{float(db_net):.2f}",
             f"summary={s['paidAmount']} 明细={net:.2f} 库={db_net}")
    ck.check("invoicedAmount = 有效发票合计", s["invoicedAmount"] == f"{invoiced:.2f}", f"{s['invoicedAmount']} vs {invoiced}")
    ck.check("paymentStatus=PARTIAL", s["paymentStatus"] == "PARTIAL", s["paymentStatus"])
    ck.check("invoiceStatus=PARTIAL", s["invoiceStatus"] == "PARTIAL", s["invoiceStatus"])
    det = fin.get(f"/mold/purchases/{pid}").data
    ck.check("详情 paymentStatus 与 summary 一致", det["paymentStatus"] == s["paymentStatus"], det["paymentStatus"])

    # ------------------------------------------------------------ 附件
    ck.sec("附件")
    akey = idem()
    r = upload(fin, "PURCHASE", pid, "INVOICE", "E2E发票.pdf", PDF, "application/pdf", key=akey)
    ck.status("上传 PDF", r, 201)
    att = (r.data or {}).get("id")
    rr = upload(fin, "PURCHASE", pid, "INVOICE", "E2E发票.pdf", PDF, "application/pdf", key=akey)
    ck.check("上传同键重放返回同一附件", rr.status == 201 and (rr.data or {}).get("id") == att, repr(rr))
    ck.check("财务附件自动标 FINANCIAL", (r.data or {}).get("sensitivity") == "FINANCIAL", r.data)
    d = fin.call("GET", f"/mold/attachments/{att}/download")
    ck.check("下载内容一致", d.status == 200 and hashlib.sha256(d.raw).hexdigest() == hashlib.sha256(PDF).hexdigest(),
             f"{d.status}")
    ck.check("下载 Content-Type=application/pdf", d.headers.get("content-type", "").startswith("application/pdf"),
             d.headers.get("content-type"))
    ck.check("下载带 Content-Disposition attachment", "attachment" in d.headers.get("content-disposition", ""))
    lst = fin.get("/mold/attachments", query={"ownerType": "PURCHASE", "ownerId": pid})
    ck.check("按归属列出附件", lst.status == 200 and any(a.get("id") == att for a in rows(lst)), repr(lst))
    ck.status("文本文件被拒", upload(fin, "PURCHASE", pid, "OTHER", "a.txt", b"hello world", "text/plain"), 422,
              "FILE_TYPE_NOT_ALLOWED")
    ck.status("声明类型与内容不符被拒", upload(fin, "PURCHASE", pid, "OTHER", "a.pdf", PNG, "application/pdf"), 422,
              "FILE_TYPE_NOT_ALLOWED")
    ck.status("伪装 PDF 扩展名的可执行内容被拒", upload(fin, "PURCHASE", pid, "OTHER", "x.pdf", b"MZ\x90\x00" * 8,
              "application/pdf"), 422, "FILE_TYPE_NOT_ALLOWED")
    big = PDF + b"0" * (10 * 1024 * 1024 + 1 - len(PDF))
    ck.status("10 MiB+1B 被拒 413", upload(fin, "PURCHASE", pid, "OTHER", "big.pdf", big, "application/pdf"), 413)
    huge = PDF + b"0" * (13 * 1024 * 1024)
    ck.status("13 MiB 被网关/容器拒 413", upload(fin, "PURCHASE", pid, "OTHER", "huge.pdf", huge, "application/pdf"), 413)
    ck.status("不存在的归属对象被拒", upload(fin, "PURCHASE", "00000000-0000-0000-0000-000000000000", "OTHER",
              "a.pdf", PDF, "application/pdf"), 422, "ATTACHMENT_OWNER_INVALID")
    for role in ("VIEWER", "PM"):
        ck.status(f"{role} 不能下载财务附件", client(role).call("GET", f"/mold/attachments/{att}/download"), 403)
    # 种子未给 FINANCE 授 mold:attachment:delete，@PreAuthorize 先拦；服务层“上传者本人可删”对其不可达（单列为不一致项）
    ck.status("FINANCE 删除自己上传的附件 → 按种子 403",
              fin.call("DELETE", f"/mold/attachments/{att}"), 403, "FORBIDDEN")
    ck.status("OWNER 删除附件", owner.call("DELETE", f"/mold/attachments/{att}"), [204, 404])
    ck.status("删除后下载 404", fin.call("GET", f"/mold/attachments/{att}/download"), 404)

    # ------------------------------------------------------------ 作废链
    ck.sec("作废链")
    r = fin.post(f"/mold/purchases/{pid}/void", {"reason": "E2E 作废"})
    ck.status("有有效子单时作废采购被拒 409", r, 409, "INVALID_STATE_TRANSITION")
    blockers = (r.details or {}).get("blockers") or []
    got = {(b.get("type"), b.get("id")) for b in blockers}
    want = {("RECEIPT", receipt1), ("RECEIPT", receipt2), ("INVOICE", invoice1), ("CASH_ENTRY", pay1),
            ("CASH_ENTRY", pay2), ("CASH_ENTRY", refund1)}
    ck.check("details.blockers 列全 6 个阻塞项", got == want, f"缺 {want - got} 多 {got - want}")

    for rid in (receipt1, receipt2):
        ck.status("作废收货", fin.post(f"/mold/receipts/{rid}/void", {"reason": "E2E"}), 200)
    ck.status("重复作废收货 409", fin.post(f"/mold/receipts/{receipt1}/void", {"reason": "E2E"}), 409,
              "ENTRY_ALREADY_VOID")
    ck.status("作废发票", fin.post(f"/mold/invoices/{invoice1}/void", {"reason": "E2E"}), 200)
    r = fin.post(f"/mold/purchases/{pid}/invoices", dict(inv, amountTotal="100", taxAmount=None))
    ck.status("作废后同发票号可重新登记", r, 201)
    if r.status == 201:
        fin.post(f"/mold/invoices/{r.data['id']}/void", {"reason": "E2E"})

    ck.status("作废付款 500（净付 1200）", fin.post(f"/mold/cash-entries/{pay2}/void", {"reason": "E2E"}), 200)
    r = fin.post(f"/mold/cash-entries/{pay1}/void", {"reason": "E2E"})
    s = fin.get(f"/mold/purchases/{pid}/summary").data
    ck.check("作废付款不得使采购净付为负（1200-1500=-300 应被拒）",
             r.status in (409, 422) or float(s["paidAmount"]) >= 0,
             f"作废返回 {r.status}，summary paidAmount={s['paidAmount']} paymentStatus={s['paymentStatus']}")
    if r.status == 200:
        ck.check("净付为负时 paymentStatus 不应为 PARTIAL", s["paymentStatus"] != "PARTIAL", s["paymentStatus"])
    ck.status("作废退款", fin.post(f"/mold/cash-entries/{refund1}/void", {"reason": "E2E"}), 200)
    if r.status != 200:
        ck.status("作废付款 1500", fin.post(f"/mold/cash-entries/{pay1}/void", {"reason": "E2E"}), 200)

    r = fin.post(f"/mold/purchases/{pid}/void", {"reason": "E2E 作废"})
    ck.status("子单全部作废后作废采购", r, 200)
    ck.check("采购状态 VOID", (r.data or {}).get("status") == "VOID", repr(r))
    ck.status("重复作废采购 409", fin.post(f"/mold/purchases/{pid}/void", {"reason": "E2E"}), 409, "ENTRY_ALREADY_VOID")
    ck.status("作废后不能付款", fin.post(f"/mold/purchases/{pid}/payments", pay_body("1")), 409)
    s = fin.get(f"/mold/purchases/{pid}/summary").data
    ck.check("作废后 summary 归零", s["paidAmount"] == "0.00" and s["invoicedAmount"] == "0.00" and
             s["receiptStatus"] == "NONE", s)
    n_audit = db_one(f"SELECT COUNT(*) FROM audit_logs WHERE entity_id='{pid}'")
    ck.check("采购审计条数 = 创建/改/确认/作废 4 条（重放不重复记）", n_audit == "4", f"audit={n_audit}")

    res = ck.summary()
    save_report("purchase_flow", res)
    return 0 if not res["failed"] else 1


if __name__ == "__main__":
    sys.exit(main())
