/*
 * 模具加工管理系统 M7-A 采购三段式（契约 6.8，19 操作中的采购主体）
 *
 *   GET    /purchases                       mold:purchase:list
 *   POST   /purchases                       mold:purchase:create（幂等必填）
 *   GET    /purchases/{id}                  mold:purchase:list
 *   PATCH  /purchases/{id}                  mold:purchase:update（If-Match 必填）
 *   POST   /purchases/{id}/confirm          mold:purchase:confirm（幂等必填）
 *   POST   /purchases/{id}/void             mold:purchase:void（幂等必填）
 *   GET    /purchases/{id}/summary          mold:purchase:list
 *   GET/POST /purchases/{id}/receipts       mold:purchase:list / mold:receipt:create
 *   POST   /receipts/{id}/void              mold:receipt:void
 *   GET/POST /purchases/{id}/invoices       mold:purchase:list / mold:invoice:create
 *   POST   /invoices/{id}/void              mold:invoice:void
 *   GET/POST /purchases/{id}/payments       mold:cash:list / mold:cash:create
 *   POST   /purchases/{id}/refunds          mold:cash:create
 *
 * 所有写操作在同一事务内写业务表、audit_logs 与 event_outbox；
 * 审计或事件写入失败时业务整体回滚。金额一律 BigDecimal，出参为两位小数字符串。
 */
package com.pig4cloud.pig.mold.finance;

import com.pig4cloud.pig.mold.audit.AuditWriter;
import com.pig4cloud.pig.mold.event.EventWriter;
import com.pig4cloud.pig.mold.security.MoldActor;
import com.pig4cloud.pig.mold.security.MoldActorResolver;
import com.pig4cloud.pig.mold.web.MoldApiException;
import com.pig4cloud.pig.mold.web.MoldFields;
import com.pig4cloud.pig.mold.web.MoldResponses;
import com.pig4cloud.pig.mold.web.MoldTime;
import com.pig4cloud.pig.mold.web.PageQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PurchaseService {

	public static final Set<String> SORTABLE = Set.of("code", "purchaseDate", "amountTotal", "status", "currency");

	public static final Set<String> CURRENCIES = Set.of("CNY", "USD", "EUR", "HKD");

	public static final Set<String> ALLOCATIONS = Set.of("PROJECT", "PUBLIC", "UNASSIGNED");

	public static final Set<String> STATUSES = Set.of("DRAFT", "CONFIRMED", "VOID");

	public static final Set<String> METHODS = Set.of("BANK", "ALIPAY", "WECHAT", "CASH", "OTHER");

	private static final Set<String> PATCH_FIELDS = Set.of("counterpartyId", "purchaseDate", "projectId",
			"allocationStatus", "currency", "taxIncluded", "items", "note");

	private static final Map<String, String> SORT_COLUMNS = Map.of("code", "p.code", "purchaseDate",
			"p.purchase_date", "amountTotal", "p.amount_total", "status", "p.status", "currency", "p.currency");

	private final JdbcTemplate jdbcTemplate;

	private final MoldActorResolver actorResolver;

	private final AuditWriter auditWriter;

	private final EventWriter eventWriter;

	private final CashEntryService cashEntryService;

	// ------------------------------------------------------------------ 查询

	public Map<String, Object> list(PageQuery page, String projectId, String counterpartyId, String status,
			String allocationStatus, String currency, String dateFrom, String dateTo, String q) {
		List<String> conditions = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		conditions.add("1 = 1");
		if (StringUtils.hasText(projectId)) {
			conditions.add("p.project_id = ?");
			args.add(projectId.trim());
		}
		if (StringUtils.hasText(counterpartyId)) {
			conditions.add("p.counterparty_id = ?");
			args.add(counterpartyId.trim());
		}
		if (StringUtils.hasText(status)) {
			conditions.add("p.status = ?");
			args.add(enumQuery(status, "status", STATUSES));
		}
		if (StringUtils.hasText(allocationStatus)) {
			conditions.add("p.allocation_status = ?");
			args.add(enumQuery(allocationStatus, "allocationStatus", ALLOCATIONS));
		}
		if (StringUtils.hasText(currency)) {
			conditions.add("p.currency = ?");
			args.add(enumQuery(currency, "currency", CURRENCIES));
		}
		if (StringUtils.hasText(dateFrom)) {
			conditions.add("p.purchase_date >= ?");
			args.add(queryDate(dateFrom, "dateFrom"));
		}
		if (StringUtils.hasText(dateTo)) {
			conditions.add("p.purchase_date <= ?");
			args.add(queryDate(dateTo, "dateTo"));
		}
		if (StringUtils.hasText(q)) {
			String like = "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
			conditions.add("p.code LIKE ? ESCAPE '!'");
			args.add(like);
		}
		String where = " WHERE " + String.join(" AND ", conditions);

		Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchases p" + where, Long.class,
				args.toArray());
		long totalValue = total == null ? 0L : total;

		String direction = "desc".equals(page.order()) ? "DESC" : "ASC";
		String sql = "SELECT p.* FROM purchases p" + where + " ORDER BY " + SORT_COLUMNS.get(page.sort()) + " "
				+ direction + ", p.id " + direction + " LIMIT ? OFFSET ?";
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(page.pageSize());
		pageArgs.add((long) (page.pageNum() - 1) * page.pageSize());

		List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, pageArgs.toArray()).stream().map(this::dto)
			.toList();
		return MoldResponses.page(rows, totalValue, page.pageNum(), page.pageSize(), dataVersion());
	}

	public Map<String, Object> get(String purchaseId) {
		Map<String, Object> row = loadPurchase(purchaseId, false);
		if (row == null) {
			throw new MoldApiException(404, "NOT_FOUND", "采购单不存在");
		}
		return dto(row);
	}

	public Map<String, Object> summary(String purchaseId) {
		Map<String, Object> row = loadPurchase(purchaseId, false);
		if (row == null) {
			throw new MoldApiException(404, "NOT_FOUND", "采购单不存在");
		}
		return summaryOf(row);
	}

	public Map<String, Object> listReceipts(String purchaseId) {
		requirePurchase(purchaseId);
		List<Map<String, Object>> rows = jdbcTemplate
			.queryForList("SELECT * FROM purchase_receipts WHERE purchase_id = ? ORDER BY created_at, id", purchaseId)
			.stream()
			.map(this::receiptDto)
			.toList();
		return MoldResponses.collection(rows, dataVersion());
	}

	public Map<String, Object> listInvoices(String purchaseId) {
		requirePurchase(purchaseId);
		List<Map<String, Object>> rows = jdbcTemplate
			.queryForList("SELECT * FROM purchase_invoices WHERE purchase_id = ? ORDER BY created_at, id", purchaseId)
			.stream()
			.map(this::invoiceDto)
			.toList();
		return MoldResponses.collection(rows, dataVersion());
	}

	public Map<String, Object> listPayments(String purchaseId) {
		requirePurchase(purchaseId);
		// 与收支流水可见性一致：无 cash:viewAll 的用户只能看到自己录入的付款/退款
		List<Map<String, Object>> raw = cashEntryService.canViewAll()
				? jdbcTemplate.queryForList("SELECT * FROM cash_entries WHERE purchase_id = ? ORDER BY created_at, id",
						purchaseId)
				: jdbcTemplate.queryForList(
						"SELECT * FROM cash_entries WHERE purchase_id = ? AND created_by = ? ORDER BY created_at, id",
						purchaseId, actorResolver.current().userId());
		List<Map<String, Object>> rows = raw
			.stream()
			.map(this::cashRowDto)
			.toList();
		return MoldResponses.collection(rows, dataVersion());
	}

	// ------------------------------------------------------------------ 写入

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> create(Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		MoldFields.rejectUnknownFields(body, Set.of("code", "counterpartyId", "purchaseDate", "projectId",
				"allocationStatus", "currency", "taxIncluded", "items", "note"));

		String code = MoldFields.text(body.get("code"), "code", 64);
		String counterpartyId = MoldFields.text(body.get("counterpartyId"), "counterpartyId", 36);
		LocalDate purchaseDate = MoldFields.date(body.get("purchaseDate"), "purchaseDate");
		String allocationStatus = MoldFields.enumValue(body.get("allocationStatus"), "allocationStatus", ALLOCATIONS);
		String currency = MoldFields.enumValue(body.get("currency"), "currency", CURRENCIES);
		Boolean taxIncluded = MoldFields.bool(body.get("taxIncluded"), "taxIncluded");
		String note = MoldFields.optionalText(body.get("note"), "note", 2000);
		String projectId = resolveProject(allocationStatus, body.get("projectId"));
		requireSupplier(counterpartyId);

		List<ItemInput> items = parseItems(body.get("items"));
		BigDecimal total = items.stream().map(ItemInput::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

		Long duplicate = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchases WHERE code = ?", Long.class,
				code);
		if (duplicate != null && duplicate > 0) {
			throw duplicateCode(code);
		}

		String purchaseId = UUID.randomUUID().toString();
		try {
			jdbcTemplate.update("""
					INSERT INTO purchases
						(id, code, counterparty_id, purchase_date, project_id, allocation_status, currency,
						 amount_total, tax_included, status, note, version, created_by, created_at, updated_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, 1, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
					""", purchaseId, code, counterpartyId, purchaseDate, projectId, allocationStatus, currency, total,
					taxIncluded, note, actor.userId());
		}
		catch (DuplicateKeyException ex) {
			throw duplicateCode(code);
		}
		insertItems(purchaseId, items);

		Map<String, Object> after = dto(loadPurchase(purchaseId, false));
		auditWriter.record("PURCHASE", purchaseId, "CREATE", null, after);
		eventWriter.emit("purchase.created", "PURCHASE", purchaseId, projectId, null, actor.userId(),
				number(after.get("version")), "FINANCE", "DRAFT", null,
				List.of("code", "counterpartyId", "purchaseDate", "amountTotal", "items"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> update(String purchaseId, Integer expectedVersion, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> beforeRow = loadPurchase(purchaseId, true);
		if (beforeRow == null) {
			throw new MoldApiException(404, "NOT_FOUND", "采购单不存在");
		}
		if ("VOID".equals(beforeRow.get("status"))) {
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "已作废采购单不可修改");
		}
		requireVersion(beforeRow, expectedVersion);
		MoldFields.rejectUnknownFields(body, PATCH_FIELDS);
		MoldFields.requireAtLeastOneField(body, PATCH_FIELDS);

		if ("CONFIRMED".equals(beforeRow.get("status")) && body.keySet().stream().anyMatch(k -> !"note".equals(k))) {
			throw new MoldApiException(409, "POLICY_NOT_CONFIRMED", "确认后仅可修改备注，金额与归属修改尚未开放");
		}

		String allocationStatus = body.containsKey("allocationStatus")
				? MoldFields.enumValue(body.get("allocationStatus"), "allocationStatus", ALLOCATIONS)
				: (String) beforeRow.get("allocation_status");
		String projectId = body.containsKey("projectId") || body.containsKey("allocationStatus")
				? resolveProject(allocationStatus, body.get("projectId")) : (String) beforeRow.get("project_id");
		if (body.containsKey("projectId") && !body.containsKey("allocationStatus")) {
			projectId = resolveProject(allocationStatus, body.get("projectId"));
		}
		String counterpartyId = body.containsKey("counterpartyId")
				? MoldFields.text(body.get("counterpartyId"), "counterpartyId", 36)
				: (String) beforeRow.get("counterparty_id");
		requireSupplier(counterpartyId);
		LocalDate purchaseDate = body.containsKey("purchaseDate")
				? MoldFields.date(body.get("purchaseDate"), "purchaseDate")
				: ((java.sql.Date) beforeRow.get("purchase_date")).toLocalDate();
		String currency = body.containsKey("currency")
				? MoldFields.enumValue(body.get("currency"), "currency", CURRENCIES) : (String) beforeRow.get("currency");
		Boolean taxIncluded = body.containsKey("taxIncluded") ? MoldFields.bool(body.get("taxIncluded"), "taxIncluded")
				: toBoolean(beforeRow.get("tax_included"));
		String note = body.containsKey("note") ? MoldFields.optionalText(body.get("note"), "note", 2000)
				: (String) beforeRow.get("note");

		BigDecimal total = ((BigDecimal) beforeRow.get("amount_total")).setScale(2, RoundingMode.HALF_UP);
		if (body.containsKey("items")) {
			List<ItemInput> items = parseItems(body.get("items"));
			total = items.stream().map(ItemInput::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
			jdbcTemplate.update("DELETE FROM purchase_items WHERE purchase_id = ?", purchaseId);
			insertItems(purchaseId, items);
		}

		Map<String, Object> before = dto(beforeRow);
		jdbcTemplate.update("""
				UPDATE purchases
				   SET counterparty_id = ?, purchase_date = ?, project_id = ?, allocation_status = ?,
				       currency = ?, amount_total = ?, tax_included = ?, note = ?,
				       version = version + 1, updated_at = UTC_TIMESTAMP(3), updated_by = ?
				 WHERE id = ?
				""", counterpartyId, purchaseDate, projectId, allocationStatus, currency, total, taxIncluded, note,
				actor.userId(), purchaseId);

		Map<String, Object> after = dto(loadPurchase(purchaseId, false));
		auditWriter.record("PURCHASE", purchaseId, "UPDATE", before, after);
		eventWriter.emit("purchase.updated", "PURCHASE", purchaseId, projectId, null, actor.userId(),
				number(after.get("version")), "FINANCE", (String) after.get("status"), (String) before.get("status"),
				List.of("counterpartyId", "purchaseDate", "allocationStatus", "currency", "amountTotal", "items",
						"note"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> confirm(String purchaseId) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> beforeRow = loadPurchase(purchaseId, true);
		if (beforeRow == null) {
			throw new MoldApiException(404, "NOT_FOUND", "采购单不存在");
		}
		if (!"DRAFT".equals(beforeRow.get("status"))) {
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "仅草稿采购单可确认");
		}
		BigDecimal amount = (BigDecimal) beforeRow.get("amount_total");
		if (amount.signum() <= 0) {
			throw MoldApiException.validation("/body/amountTotal", "exclusiveMinimum", Map.of("limit", 0),
					"采购金额必须大于 0");
		}

		Map<String, Object> before = dto(beforeRow);
		jdbcTemplate.update("""
				UPDATE purchases
				   SET status = 'CONFIRMED', confirmed_at = UTC_TIMESTAMP(3), confirmed_by = ?,
				       version = version + 1, updated_at = UTC_TIMESTAMP(3), updated_by = ?
				 WHERE id = ?
				""", actor.userId(), actor.userId(), purchaseId);

		Map<String, Object> after = dto(loadPurchase(purchaseId, false));
		auditWriter.record("PURCHASE", purchaseId, "CONFIRM", before, after);
		eventWriter.emit("purchase.confirmed", "PURCHASE", purchaseId, (String) after.get("projectId"), null,
				actor.userId(), number(after.get("version")), "FINANCE", "CONFIRMED", "DRAFT",
				List.of("status", "confirmedAt", "confirmedBy"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> voidPurchase(String purchaseId, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		MoldFields.rejectUnknownFields(body, Set.of("reason"));
		String reason = MoldFields.text(body.get("reason"), "reason", 2000);

		Map<String, Object> beforeRow = loadPurchase(purchaseId, true);
		if (beforeRow == null) {
			throw new MoldApiException(404, "NOT_FOUND", "采购单不存在");
		}
		if ("VOID".equals(beforeRow.get("status"))) {
			throw new MoldApiException(409, "ENTRY_ALREADY_VOID", "采购单已作废");
		}

		List<Map<String, Object>> blockers = new ArrayList<>();
		jdbcTemplate.queryForList(
				"SELECT id, received_on, status FROM purchase_receipts WHERE purchase_id = ? AND status = 'ACTIVE'",
				purchaseId)
			.forEach(row -> blockers.add(blocker("RECEIPT", row.get("id"), null, "有效收货单")));
		jdbcTemplate.queryForList(
				"SELECT id, invoice_no, status FROM purchase_invoices WHERE purchase_id = ? AND status = 'ACTIVE'",
				purchaseId)
			.forEach(row -> blockers.add(blocker("INVOICE", row.get("id"), (String) row.get("invoice_no"), "有效发票")));
		jdbcTemplate.queryForList(
				"SELECT id, code FROM cash_entries WHERE purchase_id = ? AND status = 'CONFIRMED'", purchaseId)
			.forEach(row -> blockers.add(blocker("CASH_ENTRY", row.get("id"), (String) row.get("code"), "有效付款/退款")));
		if (!blockers.isEmpty()) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("blockers", blockers);
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "采购仍有有效收货、发票或付款，不能作废", details);
		}

		Map<String, Object> before = dto(beforeRow);
		jdbcTemplate.update("""
				UPDATE purchases
				   SET status = 'VOID', voided_at = UTC_TIMESTAMP(3), voided_by = ?, void_reason = ?,
				       version = version + 1, updated_at = UTC_TIMESTAMP(3), updated_by = ?
				 WHERE id = ?
				""", actor.userId(), reason, actor.userId(), purchaseId);

		Map<String, Object> after = dto(loadPurchase(purchaseId, false));
		auditWriter.record("PURCHASE", purchaseId, "VOID", before, after);
		eventWriter.emit("purchase.voided", "PURCHASE", purchaseId, (String) after.get("projectId"), null,
				actor.userId(), number(after.get("version")), "FINANCE", "VOID", (String) before.get("status"),
				List.of("status", "voidedAt", "voidedBy", "voidReason"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> createReceipt(String purchaseId, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> purchase = loadPurchase(purchaseId, true);
		if (purchase == null) {
			throw new MoldApiException(404, "NOT_FOUND", "采购单不存在");
		}
		requireConfirmed(purchase);
		MoldFields.rejectUnknownFields(body, Set.of("receivedOn", "items", "note"));
		LocalDate receivedOn = MoldFields.date(body.get("receivedOn"), "receivedOn");
		String note = MoldFields.optionalText(body.get("note"), "note", 2000);
		List<ReceiptItemInput> items = parseReceiptItems(body.get("items"));

		Set<String> seen = new LinkedHashSet<>();
		for (ReceiptItemInput item : items) {
			if (!seen.add(item.purchaseItemId())) {
				throw MoldApiException.validation("/body/items", "uniqueItems", null, "收货明细不能重复");
			}
			Map<String, Object> purchaseItem = jdbcTemplate
				.queryForList("SELECT id, purchase_id, qty FROM purchase_items WHERE id = ?", item.purchaseItemId())
				.stream()
				.findFirst()
				.orElseThrow(() -> MoldApiException.validation("/body/items/purchaseItemId", "reference", null,
						"采购明细不存在"));
			if (!purchaseId.equals(purchaseItem.get("purchase_id"))) {
				throw MoldApiException.validation("/body/items/purchaseItemId", "reference", null, "采购明细不属于该采购单");
			}
			BigDecimal received = jdbcTemplate.queryForObject("""
					SELECT COALESCE(SUM(ri.qty_received), 0)
					FROM purchase_receipt_items ri
					JOIN purchase_receipts r ON r.id = ri.receipt_id
					WHERE ri.purchase_item_id = ? AND r.status = 'ACTIVE'
					FOR SHARE
					""", BigDecimal.class, item.purchaseItemId());
			BigDecimal already = received == null ? BigDecimal.ZERO : received;
			BigDecimal qty = (BigDecimal) purchaseItem.get("qty");
			if (already.add(item.qtyReceived()).compareTo(qty) > 0) {
				Map<String, Object> details = new LinkedHashMap<>();
				details.put("purchaseItemId", item.purchaseItemId());
				details.put("orderedQty", MoldFields.quantity(qty));
				details.put("receivedQty", MoldFields.quantity(already));
				details.put("requestedQty", MoldFields.quantity(item.qtyReceived()));
				throw new MoldApiException(422, "OVER_RECEIPT", "收货累计超过采购量", details);
			}
		}

		String receiptId = UUID.randomUUID().toString();
		jdbcTemplate.update("""
				INSERT INTO purchase_receipts (id, purchase_id, received_on, received_by, note, status, created_at, version)
				VALUES (?, ?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(3), 1)
				""", receiptId, purchaseId, receivedOn, actor.userId(), note);
		for (ReceiptItemInput item : items) {
			jdbcTemplate.update("""
					INSERT INTO purchase_receipt_items (id, receipt_id, purchase_item_id, qty_received)
					VALUES (?, ?, ?, ?)
					""", UUID.randomUUID().toString(), receiptId, item.purchaseItemId(), item.qtyReceived());
		}

		Map<String, Object> after = receiptDto(loadReceipt(receiptId));
		auditWriter.record("PURCHASE_RECEIPT", receiptId, "CREATE", null, after);
		eventWriter.emit("receipt.created", "PURCHASE_RECEIPT", receiptId, (String) purchase.get("project_id"), null,
				actor.userId(), number(after.get("version")), "FINANCE", "ACTIVE", null,
				List.of("receivedOn", "items"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> voidReceipt(String receiptId, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		MoldFields.rejectUnknownFields(body, Set.of("reason"));
		String reason = MoldFields.text(body.get("reason"), "reason", 2000);
		Map<String, Object> beforeRow = loadReceipt(receiptId, true);
		if (beforeRow == null) {
			throw new MoldApiException(404, "NOT_FOUND", "收货单不存在");
		}
		if ("VOID".equals(beforeRow.get("status"))) {
			throw new MoldApiException(409, "ENTRY_ALREADY_VOID", "收货单已作废");
		}
		Map<String, Object> before = receiptDto(beforeRow);
		jdbcTemplate.update("""
				UPDATE purchase_receipts
				   SET status = 'VOID', voided_at = UTC_TIMESTAMP(3), voided_by = ?, void_reason = ?,
				       version = version + 1
				 WHERE id = ?
				""", actor.userId(), reason, receiptId);
		Map<String, Object> after = receiptDto(loadReceipt(receiptId));
		String projectId = jdbcTemplate.queryForObject("SELECT project_id FROM purchases WHERE id = ?", String.class,
				beforeRow.get("purchase_id"));
		auditWriter.record("PURCHASE_RECEIPT", receiptId, "VOID", before, after);
		eventWriter.emit("receipt.voided", "PURCHASE_RECEIPT", receiptId, projectId, null, actor.userId(),
				number(after.get("version")), "FINANCE", "VOID", "ACTIVE",
				List.of("status", "voidedAt", "voidedBy", "voidReason"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> createInvoice(String purchaseId, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> purchase = loadPurchase(purchaseId, true);
		if (purchase == null) {
			throw new MoldApiException(404, "NOT_FOUND", "采购单不存在");
		}
		requireConfirmed(purchase);
		MoldFields.rejectUnknownFields(body, Set.of("counterpartyId", "invoiceNo", "invoiceType", "issueDate",
				"amountTotal", "taxAmount", "currency"));
		String counterpartyId = MoldFields.text(body.get("counterpartyId"), "counterpartyId", 36);
		String invoiceNo = MoldFields.text(body.get("invoiceNo"), "invoiceNo", 64);
		String invoiceType = MoldFields.enumValue(body.get("invoiceType"), "invoiceType",
				Set.of("VAT_SPECIAL", "VAT_NORMAL", "OTHER"));
		LocalDate issueDate = MoldFields.date(body.get("issueDate"), "issueDate");
		BigDecimal amountTotal = MoldFields.decimal(body.get("amountTotal"), "amountTotal", 2, true);
		BigDecimal taxAmount = MoldFields.optionalDecimal(body.get("taxAmount"), "taxAmount", 2, false);
		String currency = MoldFields.enumValue(body.get("currency"), "currency", CURRENCIES);

		if (!counterpartyId.equals(purchase.get("counterparty_id"))) {
			throw MoldApiException.validation("/body/counterpartyId", "reference", null, "开票方必须匹配采购供应商");
		}
		if (!currency.equals(purchase.get("currency"))) {
			throw new MoldApiException(422, "CURRENCY_MISMATCH", "发票币种必须与采购一致");
		}
		if (taxAmount != null && taxAmount.compareTo(amountTotal) > 0) {
			throw MoldApiException.validation("/body/taxAmount", "maximum", Map.of("limit",
					MoldFields.money(amountTotal)), "税额不能超过价税合计");
		}
		BigDecimal invoiced = invoicedAmount(purchaseId, true);
		BigDecimal purchaseAmount = (BigDecimal) purchase.get("amount_total");
		if (invoiced.add(amountTotal).compareTo(purchaseAmount) > 0) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("purchaseAmount", MoldFields.money(purchaseAmount));
			details.put("invoicedAmount", MoldFields.money(invoiced));
			details.put("requestedAmount", MoldFields.money(amountTotal));
			throw new MoldApiException(422, "OVER_INVOICE", "发票累计超过采购额", details);
		}

		Long duplicate = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM purchase_invoices
				WHERE counterparty_id = ? AND status = 'ACTIVE' AND invoice_no = ?
				""", Long.class, counterpartyId, invoiceNo);
		if (duplicate != null && duplicate > 0) {
			throw duplicateInvoiceNo(invoiceNo);
		}

		String invoiceId = UUID.randomUUID().toString();
		try {
			jdbcTemplate.update("""
					INSERT INTO purchase_invoices
						(id, purchase_id, counterparty_id, invoice_no, invoice_type, issue_date, amount_total,
						 tax_amount, currency, status, created_by, created_at, version)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, UTC_TIMESTAMP(3), 1)
					""", invoiceId, purchaseId, counterpartyId, invoiceNo, invoiceType, issueDate, amountTotal,
					taxAmount, currency, actor.userId());
		}
		catch (DuplicateKeyException ex) {
			throw duplicateInvoiceNo(invoiceNo);
		}

		Map<String, Object> after = invoiceDto(loadInvoice(invoiceId));
		auditWriter.record("PURCHASE_INVOICE", invoiceId, "CREATE", null, after);
		eventWriter.emit("invoice.created", "PURCHASE_INVOICE", invoiceId, (String) purchase.get("project_id"), null,
				actor.userId(), number(after.get("version")), "FINANCE", "ACTIVE", null,
				List.of("invoiceNo", "amountTotal", "issueDate"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> voidInvoice(String invoiceId, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		MoldFields.rejectUnknownFields(body, Set.of("reason"));
		String reason = MoldFields.text(body.get("reason"), "reason", 2000);
		Map<String, Object> beforeRow = loadInvoice(invoiceId, true);
		if (beforeRow == null) {
			throw new MoldApiException(404, "NOT_FOUND", "发票不存在");
		}
		if ("VOID".equals(beforeRow.get("status"))) {
			throw new MoldApiException(409, "ENTRY_ALREADY_VOID", "发票已作废");
		}
		Map<String, Object> before = invoiceDto(beforeRow);
		jdbcTemplate.update("""
				UPDATE purchase_invoices
				   SET status = 'VOID', voided_at = UTC_TIMESTAMP(3), voided_by = ?, void_reason = ?,
				       version = version + 1
				 WHERE id = ?
				""", actor.userId(), reason, invoiceId);
		Map<String, Object> after = invoiceDto(loadInvoice(invoiceId));
		String projectId = jdbcTemplate.queryForObject("SELECT project_id FROM purchases WHERE id = ?", String.class,
				beforeRow.get("purchase_id"));
		auditWriter.record("PURCHASE_INVOICE", invoiceId, "VOID", before, after);
		eventWriter.emit("invoice.voided", "PURCHASE_INVOICE", invoiceId, projectId, null, actor.userId(),
				number(after.get("version")), "FINANCE", "VOID", "ACTIVE",
				List.of("status", "voidedAt", "voidedBy", "voidReason"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> createPayment(String purchaseId, Map<String, Object> body) {
		return createCashForPurchase(purchaseId, body, "PURCHASE_PAYMENT", "OUT", "PAY-");
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> createRefund(String purchaseId, Map<String, Object> body) {
		return createCashForPurchase(purchaseId, body, "PURCHASE_REFUND", "IN", "REF-");
	}

	private Map<String, Object> createCashForPurchase(String purchaseId, Map<String, Object> body, String category,
			String direction, String codePrefix) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> purchase = loadPurchase(purchaseId, true);
		if (purchase == null) {
			throw new MoldApiException(404, "NOT_FOUND", "采购单不存在");
		}
		requireConfirmed(purchase);
		MoldFields.rejectUnknownFields(body, Set.of("amount", "currency", "occurredOn", "method", "voucherNo", "note"));
		BigDecimal amount = MoldFields.decimal(body.get("amount"), "amount", 2, true);
		String currency = MoldFields.enumValue(body.get("currency"), "currency", CURRENCIES);
		LocalDate occurredOn = MoldFields.date(body.get("occurredOn"), "occurredOn");
		String method = MoldFields.enumValue(body.get("method"), "method", METHODS);
		String voucherNo = MoldFields.optionalText(body.get("voucherNo"), "voucherNo", 64);
		String note = MoldFields.optionalText(body.get("note"), "note", 2000);

		if (!currency.equals(purchase.get("currency"))) {
			throw new MoldApiException(422, "CURRENCY_MISMATCH", "付款/退款币种必须与采购一致");
		}
		BigDecimal paid = paidAmount(purchaseId, true);
		BigDecimal purchaseAmount = (BigDecimal) purchase.get("amount_total");
		if ("OUT".equals(direction)) {
			if (paid.add(amount).compareTo(purchaseAmount) > 0) {
				Map<String, Object> details = new LinkedHashMap<>();
				details.put("purchaseAmount", MoldFields.money(purchaseAmount));
				details.put("paidAmount", MoldFields.money(paid));
				details.put("requestedAmount", MoldFields.money(amount));
				throw new MoldApiException(422, "OVER_PAYMENT", "付款累计超过采购金额", details);
			}
		}
		else if (paid.subtract(amount).signum() < 0) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("paidAmount", MoldFields.money(paid));
			details.put("requestedAmount", MoldFields.money(amount));
			throw new MoldApiException(422, "OVER_PAYMENT", "退款后采购净付不能为负", details);
		}

		String entryId = UUID.randomUUID().toString();
		String code = codePrefix + UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO cash_entries
					(id, code, direction, category, occurred_on, amount, currency, counterparty_id, project_id,
					 purchase_id, allocation_status, method, voucher_no, note, status, version, created_by,
					 created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', 1, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
				""", entryId, code, direction, category, occurredOn, amount, currency, purchase.get("counterparty_id"),
				purchase.get("project_id"), purchaseId, purchase.get("allocation_status"), method, voucherNo, note,
				actor.userId());

		Map<String, Object> entry = cashRowDto(loadCashEntry(entryId));
		auditWriter.record("CASH_ENTRY", entryId, "CREATE", null, entry);
		eventWriter.emit("cash_entry.created", "CASH_ENTRY", entryId, (String) purchase.get("project_id"), null,
				actor.userId(), number(entry.get("version")), "FINANCE", "CONFIRMED", null,
				List.of("amount", "direction", "category", "occurredOn"));

		BigDecimal newPaid = paidAmount(purchaseId);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("cashEntryId", entryId);
		data.put("cashImpact", direction);
		data.put("paymentStatus", paymentStatus(newPaid, purchaseAmount));
		data.put("paidAmount", MoldFields.money(newPaid));
		return data;
	}

	// ------------------------------------------------------------------ 派生值

	private Map<String, Object> summaryOf(Map<String, Object> purchase) {
		String purchaseId = (String) purchase.get("id");
		BigDecimal total = ((BigDecimal) purchase.get("amount_total")).setScale(2, RoundingMode.HALF_UP);
		BigDecimal paid = paidAmount(purchaseId);
		BigDecimal invoiced = invoicedAmount(purchaseId);

		List<Map<String, Object>> lines = jdbcTemplate.queryForList("""
				SELECT i.qty,
				       COALESCE(SUM(CASE WHEN r.status = 'ACTIVE' THEN ri.qty_received ELSE 0 END), 0) AS got
				FROM purchase_items i
				LEFT JOIN purchase_receipt_items ri ON ri.purchase_item_id = i.id
				LEFT JOIN purchase_receipts r ON r.id = ri.receipt_id
				WHERE i.purchase_id = ?
				GROUP BY i.id, i.qty
				""", purchaseId);
		String receiptStatus = receiptStatus(lines);
		String invoiceStatus = invoiced.signum() == 0 ? "NONE"
				: invoiced.compareTo(total) >= 0 ? "FULL" : "PARTIAL";

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("purchaseId", purchaseId);
		summary.put("currency", purchase.get("currency"));
		summary.put("amountTotal", MoldFields.money(total));
		summary.put("invoicedAmount", MoldFields.money(invoiced));
		summary.put("paidAmount", MoldFields.money(paid));
		summary.put("receiptStatus", receiptStatus);
		summary.put("invoiceStatus", invoiceStatus);
		summary.put("paymentStatus", paymentStatus(paid, total));
		summary.put("overpaid", paid.compareTo(total) > 0);
		summary.put("calculatedAt", MoldTime.nowIso());
		return summary;
	}

	private static String receiptStatus(List<Map<String, Object>> lines) {
		if (lines.isEmpty()) {
			return "NONE";
		}
		boolean any = false;
		boolean full = true;
		for (Map<String, Object> line : lines) {
			BigDecimal got = line.get("got") == null ? BigDecimal.ZERO : (BigDecimal) line.get("got");
			BigDecimal qty = (BigDecimal) line.get("qty");
			if (got.signum() > 0) {
				any = true;
			}
			if (got.compareTo(qty) < 0) {
				full = false;
			}
		}
		if (!any) {
			return "NONE";
		}
		return full ? "FULL" : "PARTIAL";
	}

	private static String paymentStatus(BigDecimal paid, BigDecimal total) {
		if (paid.signum() == 0) {
			return "UNPAID";
		}
		if (paid.compareTo(total) > 0) {
			return "OVERPAID";
		}
		if (paid.compareTo(total) == 0) {
			return "PAID";
		}
		return "PARTIAL";
	}

	private BigDecimal paidAmount(String purchaseId) {
		return paidAmount(purchaseId, false);
	}

	/**
	 * locking=true 用于写路径的额度校验：调用方已持有采购单行锁（FOR UPDATE），
	 * 这里用锁定读（FOR SHARE）读取最新已提交数据，避免 RR 快照读到旧值导致并发超付。
	 */
	private BigDecimal paidAmount(String purchaseId, boolean locking) {
		BigDecimal value = jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(CASE WHEN direction = 'OUT' THEN amount ELSE -amount END), 0)
				FROM cash_entries WHERE purchase_id = ? AND status = 'CONFIRMED'
				""" + (locking ? " FOR SHARE" : ""), BigDecimal.class, purchaseId);
		return value == null ? BigDecimal.ZERO : value;
	}

	private BigDecimal invoicedAmount(String purchaseId) {
		return invoicedAmount(purchaseId, false);
	}

	private BigDecimal invoicedAmount(String purchaseId, boolean locking) {
		BigDecimal value = jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(amount_total), 0)
				FROM purchase_invoices WHERE purchase_id = ? AND status = 'ACTIVE'
				""" + (locking ? " FOR SHARE" : ""), BigDecimal.class, purchaseId);
		return value == null ? BigDecimal.ZERO : value;
	}

	// ------------------------------------------------------------------ DTO

	private Map<String, Object> dto(Map<String, Object> row) {
		if (row == null) {
			return null;
		}
		String purchaseId = (String) row.get("id");
		Map<String, Object> summary = summaryOf(row);

		List<Map<String, Object>> items = jdbcTemplate
			.queryForList("SELECT * FROM purchase_items WHERE purchase_id = ? ORDER BY line_no, id", purchaseId)
			.stream()
			.map(this::itemDto)
			.toList();

		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", purchaseId);
		dto.put("code", row.get("code"));
		dto.put("counterpartyId", row.get("counterparty_id"));
		dto.put("purchaseDate", MoldTime.iso(row.get("purchase_date")));
		dto.put("projectId", row.get("project_id"));
		dto.put("allocationStatus", row.get("allocation_status"));
		dto.put("currency", row.get("currency"));
		dto.put("amountTotal", MoldFields.money((BigDecimal) row.get("amount_total")));
		dto.put("taxIncluded", toBoolean(row.get("tax_included")));
		dto.put("status", row.get("status"));
		dto.put("cashImpact", "NONE");
		dto.put("items", items);
		dto.put("receiptStatus", summary.get("receiptStatus"));
		dto.put("invoiceStatus", summary.get("invoiceStatus"));
		dto.put("paymentStatus", summary.get("paymentStatus"));
		dto.put("version", number(row.get("version")));
		dto.put("note", row.get("note"));
		dto.put("createdAt", MoldTime.iso(row.get("created_at")));
		dto.put("confirmedAt", MoldTime.iso(row.get("confirmed_at")));
		dto.put("voidedAt", MoldTime.iso(row.get("voided_at")));
		dto.put("voidReason", row.get("void_reason"));
		return dto;
	}

	private Map<String, Object> itemDto(Map<String, Object> row) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", row.get("id"));
		dto.put("lineNo", number(row.get("line_no")));
		dto.put("name", row.get("name"));
		dto.put("spec", row.get("spec"));
		dto.put("qty", MoldFields.quantity((BigDecimal) row.get("qty")));
		dto.put("unit", row.get("unit"));
		dto.put("unitPrice", MoldFields.unitPrice((BigDecimal) row.get("unit_price")));
		dto.put("amount", MoldFields.money((BigDecimal) row.get("amount")));
		dto.put("note", row.get("note"));
		return dto;
	}

	private Map<String, Object> receiptDto(Map<String, Object> row) {
		if (row == null) {
			return null;
		}
		List<Map<String, Object>> items = jdbcTemplate
			.queryForList("SELECT purchase_item_id, qty_received FROM purchase_receipt_items WHERE receipt_id = ?",
					row.get("id"))
			.stream()
			.map(item -> {
				Map<String, Object> dto = new LinkedHashMap<>();
				dto.put("purchaseItemId", item.get("purchase_item_id"));
				dto.put("qtyReceived", MoldFields.quantity((BigDecimal) item.get("qty_received")));
				return dto;
			})
			.toList();
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", row.get("id"));
		dto.put("purchaseId", row.get("purchase_id"));
		dto.put("receivedOn", MoldTime.iso(row.get("received_on")));
		dto.put("receivedBy", row.get("received_by"));
		dto.put("status", row.get("status"));
		dto.put("items", items);
		dto.put("note", row.get("note"));
		dto.put("voidedAt", MoldTime.iso(row.get("voided_at")));
		dto.put("voidReason", row.get("void_reason"));
		dto.put("version", number(row.get("version")));
		return dto;
	}

	private Map<String, Object> invoiceDto(Map<String, Object> row) {
		if (row == null) {
			return null;
		}
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", row.get("id"));
		dto.put("purchaseId", row.get("purchase_id"));
		dto.put("counterpartyId", row.get("counterparty_id"));
		dto.put("invoiceNo", row.get("invoice_no"));
		dto.put("invoiceType", row.get("invoice_type"));
		dto.put("issueDate", MoldTime.iso(row.get("issue_date")));
		dto.put("amountTotal", MoldFields.money((BigDecimal) row.get("amount_total")));
		dto.put("taxAmount", MoldFields.money((BigDecimal) row.get("tax_amount")));
		dto.put("currency", row.get("currency"));
		dto.put("status", row.get("status"));
		dto.put("voidedAt", MoldTime.iso(row.get("voided_at")));
		dto.put("voidReason", row.get("void_reason"));
		dto.put("version", number(row.get("version")));
		return dto;
	}

	/**
	 * 采购付款/退款列表与 cash-entries 共用同一 DTO 形状（此处只给最小字段，A2 的列表更完整）。
	 */
	private Map<String, Object> cashRowDto(Map<String, Object> row) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", row.get("id"));
		dto.put("code", row.get("code"));
		dto.put("direction", row.get("direction"));
		dto.put("category", row.get("category"));
		dto.put("occurredOn", MoldTime.iso(row.get("occurred_on")));
		dto.put("amount", MoldFields.money((BigDecimal) row.get("amount")));
		dto.put("currency", row.get("currency"));
		dto.put("counterpartyId", row.get("counterparty_id"));
		dto.put("projectId", row.get("project_id"));
		dto.put("purchaseId", row.get("purchase_id"));
		dto.put("allocationStatus", row.get("allocation_status"));
		dto.put("method", row.get("method"));
		dto.put("voucherNo", row.get("voucher_no"));
		dto.put("note", row.get("note"));
		dto.put("status", row.get("status"));
		dto.put("version", number(row.get("version")));
		return dto;
	}

	// ------------------------------------------------------------------ 载入与校验

	private Map<String, Object> loadPurchase(String purchaseId, boolean forUpdate) {
		List<Map<String, Object>> rows = jdbcTemplate
			.queryForList("SELECT * FROM purchases WHERE id = ?" + (forUpdate ? " FOR UPDATE" : ""), purchaseId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private Map<String, Object> loadReceipt(String receiptId) {
		return loadReceipt(receiptId, false);
	}

	private Map<String, Object> loadReceipt(String receiptId, boolean forUpdate) {
		List<Map<String, Object>> rows = jdbcTemplate
			.queryForList("SELECT * FROM purchase_receipts WHERE id = ?" + (forUpdate ? " FOR UPDATE" : ""), receiptId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private Map<String, Object> loadInvoice(String invoiceId) {
		return loadInvoice(invoiceId, false);
	}

	private Map<String, Object> loadInvoice(String invoiceId, boolean forUpdate) {
		List<Map<String, Object>> rows = jdbcTemplate
			.queryForList("SELECT * FROM purchase_invoices WHERE id = ?" + (forUpdate ? " FOR UPDATE" : ""), invoiceId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private Map<String, Object> loadCashEntry(String entryId) {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM cash_entries WHERE id = ?", entryId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private void requirePurchase(String purchaseId) {
		Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchases WHERE id = ?", Long.class,
				purchaseId);
		if (count == null || count == 0) {
			throw new MoldApiException(404, "NOT_FOUND", "采购单不存在");
		}
	}

	private void requireConfirmed(Map<String, Object> purchase) {
		String status = (String) purchase.get("status");
		if ("DRAFT".equals(status)) {
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "请先确认采购单");
		}
		if ("VOID".equals(status)) {
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "已作废采购单不可操作");
		}
	}

	private void requireVersion(Map<String, Object> row, Integer expectedVersion) {
		int currentVersion = ((Number) row.get("version")).intValue();
		if (expectedVersion == null) {
			throw MoldApiException.validation("/header/If-Match", "required", null, "缺少 If-Match 版本号");
		}
		if (expectedVersion != currentVersion) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("current", currentVersion);
			throw new MoldApiException(409, "VERSION_CONFLICT", "版本冲突，请刷新后重试", details);
		}
	}

	private void requireSupplier(String counterpartyId) {
		Long count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM counterparties
				WHERE id = ? AND type = 'SUPPLIER' AND status = 'ACTIVE'
				""", Long.class, counterpartyId);
		if (count == null || count == 0) {
			throw MoldApiException.validation("/body/counterpartyId", "reference", null, "供应商不存在或已停用");
		}
	}

	private String resolveProject(String allocationStatus, Object projectIdValue) {
		String projectId = MoldFields.optionalText(projectIdValue, "projectId", 36);
		if ("PROJECT".equals(allocationStatus)) {
			if (projectId == null) {
				throw MoldApiException.validation("/body/projectId", "required", null,
						"allocationStatus=PROJECT 时必须提供 projectId");
			}
			Long count = jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM projects WHERE id = ? AND deleted_at IS NULL", Long.class, projectId);
			if (count == null || count == 0) {
				throw MoldApiException.validation("/body/projectId", "reference", null, "项目不存在或已删除");
			}
			return projectId;
		}
		if (projectId != null) {
			throw MoldApiException.validation("/body/projectId", "const", null,
					"allocationStatus 非 PROJECT 时 projectId 必须为空");
		}
		return null;
	}

	private List<ItemInput> parseItems(Object raw) {
		if (!(raw instanceof List<?> list) || list.isEmpty()) {
			throw MoldApiException.validation("/body/items", "minItems", Map.of("limit", 1), "采购明细需 1 至 100 行");
		}
		if (list.size() > 100) {
			throw MoldApiException.validation("/body/items", "maxItems", Map.of("limit", 100), "采购明细最多 100 行");
		}
		List<ItemInput> items = new ArrayList<>();
		Set<Integer> lineNos = new LinkedHashSet<>();
		for (Object element : list) {
			if (!(element instanceof Map<?, ?> rawItem)) {
				throw MoldApiException.validation("/body/items", "type", null, "采购明细项必须是对象");
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> item = (Map<String, Object>) rawItem;
			MoldFields.rejectUnknownFields(item, Set.of("lineNo", "name", "spec", "qty", "unit", "unitPrice", "note"));
			int lineNo = MoldFields.integer(item.get("lineNo"), "items.lineNo");
			if (lineNo < 1) {
				throw MoldApiException.validation("/body/items/lineNo", "minimum", Map.of("limit", 1),
						"items.lineNo 必须大于 0");
			}
			if (!lineNos.add(lineNo)) {
				throw MoldApiException.validation("/body/items/lineNo", "uniqueItems", null, "明细行号不能重复");
			}
			String name = MoldFields.text(item.get("name"), "items.name", 200);
			String spec = MoldFields.optionalText(item.get("spec"), "items.spec", 255);
			BigDecimal qty = MoldFields.decimal(item.get("qty"), "items.qty", 4, true);
			String unit = MoldFields.optionalText(item.get("unit"), "items.unit", 32);
			BigDecimal unitPrice = MoldFields.decimal(item.get("unitPrice"), "items.unitPrice", 6, false);
			String note = MoldFields.optionalText(item.get("note"), "items.note", 2000);
			BigDecimal amount = qty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
			if (amount.compareTo(MoldFields.MAX_AMOUNT) >= 0) {
				throw MoldApiException.validation("/body/items/amount", "maximum",
						Map.of("limit", MoldFields.MAX_AMOUNT.toPlainString()), "明细金额超出支持范围");
			}
			items.add(new ItemInput(lineNo, name, spec, qty, unit, unitPrice, amount, note));
		}
		return items;
	}

	private void insertItems(String purchaseId, List<ItemInput> items) {
		for (ItemInput item : items) {
			jdbcTemplate.update("""
					INSERT INTO purchase_items (id, purchase_id, line_no, name, spec, qty, unit, unit_price, amount, note)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""", UUID.randomUUID().toString(), purchaseId, item.lineNo(), item.name(), item.spec(), item.qty(),
					item.unit(), item.unitPrice(), item.amount(), item.note());
		}
	}

	private List<ReceiptItemInput> parseReceiptItems(Object raw) {
		if (!(raw instanceof List<?> list) || list.isEmpty()) {
			throw MoldApiException.validation("/body/items", "minItems", Map.of("limit", 1), "收货明细需至少 1 行");
		}
		if (list.size() > 100) {
			throw MoldApiException.validation("/body/items", "maxItems", Map.of("limit", 100), "收货明细最多 100 行");
		}
		List<ReceiptItemInput> items = new ArrayList<>();
		for (Object element : list) {
			if (!(element instanceof Map<?, ?> rawItem)) {
				throw MoldApiException.validation("/body/items", "type", null, "收货明细项必须是对象");
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> item = (Map<String, Object>) rawItem;
			MoldFields.rejectUnknownFields(item, Set.of("purchaseItemId", "qtyReceived"));
			String purchaseItemId = MoldFields.text(item.get("purchaseItemId"), "items.purchaseItemId", 36);
			BigDecimal qty = MoldFields.decimal(item.get("qtyReceived"), "items.qtyReceived", 4, true);
			items.add(new ReceiptItemInput(purchaseItemId, qty));
		}
		return items;
	}

	private static Map<String, Object> blocker(String type, Object id, String code, String summary) {
		Map<String, Object> blocker = new LinkedHashMap<>();
		blocker.put("type", type);
		blocker.put("id", id);
		blocker.put("code", code);
		blocker.put("summary", summary);
		return blocker;
	}

	private static MoldApiException duplicateCode(String code) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("field", "code");
		details.put("value", code);
		return new MoldApiException(409, "PURCHASE_CODE_DUPLICATE", "采购编号已存在", details);
	}

	private static MoldApiException duplicateInvoiceNo(String invoiceNo) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("field", "invoiceNo");
		details.put("value", invoiceNo);
		return new MoldApiException(409, "DUPLICATE_INVOICE_NO", "同一供应商下有效发票号已存在", details);
	}

	private static String enumQuery(String value, String field, Set<String> allowed) {
		String text = value.trim();
		if (!allowed.contains(text)) {
			throw MoldApiException.validation("/query/" + field, "enum", Map.of("allowedValues", List.copyOf(allowed)),
					"参数无效 " + field);
		}
		return text;
	}

	private static LocalDate queryDate(String value, String field) {
		try {
			return LocalDate.parse(value.trim());
		}
		catch (RuntimeException ex) {
			throw MoldApiException.validation("/query/" + field, "format", Map.of("format", "YYYY-MM-DD"),
					"参数无效 " + field);
		}
	}

	private static Boolean toBoolean(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Boolean bool) {
			return bool;
		}
		return ((Number) value).intValue() != 0;
	}

	private String dataVersion() {
		Long version = jdbcTemplate.queryForObject("SELECT version FROM event_clock WHERE singleton = 1", Long.class);
		return String.valueOf(version == null ? 0L : version);
	}

	private static int number(Object value) {
		return value == null ? 0 : ((Number) value).intValue();
	}

	private record ItemInput(int lineNo, String name, String spec, BigDecimal qty, String unit, BigDecimal unitPrice,
			BigDecimal amount, String note) {
	}

	private record ReceiptItemInput(String purchaseItemId, BigDecimal qtyReceived) {
	}

}
