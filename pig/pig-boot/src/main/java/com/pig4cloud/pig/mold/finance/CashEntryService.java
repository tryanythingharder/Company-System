/*
 * 模具加工管理系统 M7-A / A2 财务台账（契约 6.9，8 个台账操作）
 *
 *   GET    /cash-entries                        mold:cash:list
 *   POST   /cash-entries                        mold:cash:create（幂等必填）
 *   GET    /cash-entries/{entryId}              mold:cash:list
 *   PATCH  /cash-entries/{entryId}              mold:cash:update（If-Match 必填）
 *   POST   /cash-entries/{entryId}/void         mold:cash:void（幂等必填）
 *   POST   /cash-entries/{entryId}/reverse      mold:cash:reverse（幂等必填）
 *   POST   /cash-entries/{entryId}/reconcile    mold:cash:reconcile（幂等必填）
 *   POST   /cash-entries/{entryId}/unreconcile  mold:cash:unreconcile（幂等必填）
 *
 * 口径：
 *  - 普通 POST 只允许 CUSTOMER_RECEIPT / DAILY_EXPENSE / OTHER_INCOME / OTHER_EXPENSE；
 *    采购付款/退款只走采购专用端点，带 purchaseId 一律 422。
 *  - 已作废只读；已对账不可改/作废/红冲；已红冲不可作废/再红冲；更正链与红冲链互斥。
 *  - 金额一律 BigDecimal；出参两位小数字符串；日期 YYYY-MM-DD；时间 UTC ISO。
 */
package com.pig4cloud.pig.mold.finance;

import com.pig4cloud.pig.common.security.component.PermissionService;
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
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CashEntryService {

	public static final Set<String> SORTABLE = Set.of("code", "occurredOn", "amount", "status", "direction", "category",
			"currency");

	public static final Set<String> CURRENCIES = Set.of("CNY", "USD", "EUR", "HKD");

	public static final Set<String> METHODS = Set.of("BANK", "ALIPAY", "WECHAT", "CASH", "OTHER");

	public static final Set<String> ALLOCATIONS = Set.of("PROJECT", "PUBLIC", "UNASSIGNED");

	public static final Set<String> DIRECTIONS = Set.of("IN", "OUT");

	public static final Set<String> CATEGORIES = Set.of("CUSTOMER_RECEIPT", "CUSTOMER_REFUND", "PURCHASE_PAYMENT",
			"PURCHASE_REFUND", "DAILY_EXPENSE", "EXPENSE_REFUND", "OTHER_INCOME", "INCOME_REFUND", "OTHER_EXPENSE");

	public static final Set<String> STATUSES = Set.of("CONFIRMED", "VOID");

	/** 普通 POST 允许的类别；其余（采购、退款）必须走专用端点。 */
	public static final Set<String> CREATABLE_CATEGORIES = Set.of("CUSTOMER_RECEIPT", "DAILY_EXPENSE", "OTHER_INCOME",
			"OTHER_EXPENSE");

	public static final Map<String, String> CATEGORY_DIRECTION = Map.ofEntries(
			Map.entry("CUSTOMER_RECEIPT", "IN"), Map.entry("CUSTOMER_REFUND", "OUT"),
			Map.entry("PURCHASE_PAYMENT", "OUT"), Map.entry("PURCHASE_REFUND", "IN"),
			Map.entry("DAILY_EXPENSE", "OUT"), Map.entry("EXPENSE_REFUND", "IN"), Map.entry("OTHER_INCOME", "IN"),
			Map.entry("INCOME_REFUND", "OUT"), Map.entry("OTHER_EXPENSE", "OUT"));

	/** 红冲类别映射（data-model-v0.2 §4.16.1）；退款类不在表内，不可再红冲。 */
	public static final Map<String, String> REVERSE_CATEGORY = Map.of("PURCHASE_PAYMENT", "PURCHASE_REFUND",
			"CUSTOMER_RECEIPT", "CUSTOMER_REFUND", "DAILY_EXPENSE", "EXPENSE_REFUND", "OTHER_EXPENSE", "EXPENSE_REFUND",
			"OTHER_INCOME", "INCOME_REFUND");

	private static final Map<String, String> SORT_COLUMNS = Map.of("code", "c.code", "occurredOn", "c.occurred_on",
			"amount", "c.amount", "status", "c.status", "direction", "c.direction", "category", "c.category",
			"currency", "c.currency");

	private static final Set<String> PATCH_FIELDS = Set.of("note", "voucherNo");

	private final JdbcTemplate jdbcTemplate;

	private final MoldActorResolver actorResolver;

	private final AuditWriter auditWriter;

	private final EventWriter eventWriter;

	private final PermissionService permissionService;

	// ------------------------------------------------------------------ 查询

	public Map<String, Object> list(PageQuery page, String projectId, String purchaseId, String direction,
			String category, String currency, String reconciled, String allocationStatus, String status, String dateFrom,
			String dateTo, String q) {
		List<String> conditions = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		conditions.add("1 = 1");
		if (StringUtils.hasText(projectId)) {
			conditions.add("c.project_id = ?");
			args.add(projectId.trim());
		}
		if (StringUtils.hasText(purchaseId)) {
			conditions.add("c.purchase_id = ?");
			args.add(purchaseId.trim());
		}
		if (StringUtils.hasText(direction)) {
			conditions.add("c.direction = ?");
			args.add(enumQuery(direction, "direction", DIRECTIONS));
		}
		if (StringUtils.hasText(category)) {
			conditions.add("c.category = ?");
			args.add(enumQuery(category, "category", CATEGORIES));
		}
		if (StringUtils.hasText(currency)) {
			conditions.add("c.currency = ?");
			args.add(enumQuery(currency, "currency", CURRENCIES));
		}
		if (StringUtils.hasText(allocationStatus)) {
			conditions.add("c.allocation_status = ?");
			args.add(enumQuery(allocationStatus, "allocationStatus", ALLOCATIONS));
		}
		if (StringUtils.hasText(status)) {
			conditions.add("c.status = ?");
			args.add(enumQuery(status, "status", STATUSES));
		}
		if (StringUtils.hasText(reconciled)) {
			boolean value = parseBoolean(reconciled, "reconciled");
			conditions.add(value ? "c.reconciled_at IS NOT NULL" : "c.reconciled_at IS NULL");
		}
		if (StringUtils.hasText(dateFrom)) {
			conditions.add("c.occurred_on >= ?");
			args.add(queryDate(dateFrom, "dateFrom"));
		}
		if (StringUtils.hasText(dateTo)) {
			conditions.add("c.occurred_on <= ?");
			args.add(queryDate(dateTo, "dateTo"));
		}
		if (StringUtils.hasText(q)) {
			String like = "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
			conditions.add("(c.code LIKE ? ESCAPE '!' OR c.voucher_no LIKE ? ESCAPE '!' OR c.note LIKE ? ESCAPE '!')");
			args.add(like);
			args.add(like);
			args.add(like);
		}
		if (!canViewAll()) {
			conditions.add("c.created_by = ?");
			args.add(actorResolver.current().userId());
		}
		String where = " WHERE " + String.join(" AND ", conditions);

		Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cash_entries c" + where, Long.class,
				args.toArray());
		long totalValue = total == null ? 0L : total;

		String direction2 = "desc".equals(page.order()) ? "DESC" : "ASC";
		String sql = "SELECT c.* FROM cash_entries c" + where + " ORDER BY " + SORT_COLUMNS.get(page.sort()) + " "
				+ direction2 + ", c.id " + direction2 + " LIMIT ? OFFSET ?";
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(page.pageSize());
		pageArgs.add((long) (page.pageNum() - 1) * page.pageSize());

		List<Map<String, Object>> rows = enrich(
				jdbcTemplate.queryForList(sql, pageArgs.toArray()));
		return MoldResponses.page(rows, totalValue, page.pageNum(), page.pageSize(), dataVersion());
	}

	public Map<String, Object> get(String entryId) {
		Map<String, Object> row = loadEntry(entryId, false);
		if (row == null) {
			throw new MoldApiException(404, "NOT_FOUND", "流水不存在");
		}
		requireVisible(row);
		Map<String, Object> dto = enrich(List.of(row)).get(0);
		dto.put("correctionChain", correctionChain(row));
		return dto;
	}

	// ------------------------------------------------------------------ 写入

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> create(Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		MoldFields.rejectUnknownFields(body, Set.of("code", "direction", "category", "occurredOn", "amount", "currency",
				"counterpartyId", "projectId", "purchaseId", "allocationStatus", "method", "voucherNo", "note"));
		String code = MoldFields.text(body.get("code"), "code", 64);
		String category = MoldFields.enumValue(body.get("category"), "category", CATEGORIES);
		if (!CREATABLE_CATEGORIES.contains(category)) {
			throw new MoldApiException(409, "POLICY_NOT_CONFIRMED",
					"采购付款/退款与更正类流水只经专用端点创建，普通流水不接受该类别");
		}
		if (body.get("purchaseId") != null && StringUtils.hasText(body.get("purchaseId").toString())) {
			throw MoldApiException.validation("/body/purchaseId", "const", null, "普通流水不能关联采购单");
		}
		String direction = MoldFields.enumValue(body.get("direction"), "direction", DIRECTIONS);
		if (!direction.equals(CATEGORY_DIRECTION.get(category))) {
			throw MoldApiException.validation("/body/direction", "const", Map.of("expected",
					CATEGORY_DIRECTION.get(category)), "方向与类别不匹配");
		}
		LocalDate occurredOn = MoldFields.date(body.get("occurredOn"), "occurredOn");
		BigDecimal amount = MoldFields.decimal(body.get("amount"), "amount", 2, true);
		String currency = MoldFields.enumValue(body.get("currency"), "currency", CURRENCIES);
		String allocationStatus = MoldFields.enumValue(body.get("allocationStatus"), "allocationStatus", ALLOCATIONS);
		String projectId = resolveProject(allocationStatus, body.get("projectId"));
		String counterpartyId = resolveCounterparty(category, body.get("counterpartyId"));
		String method = MoldFields.enumValue(body.get("method"), "method", METHODS);
		String voucherNo = MoldFields.optionalText(body.get("voucherNo"), "voucherNo", 64);
		String note = MoldFields.optionalText(body.get("note"), "note", 2000);

		Long duplicate = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cash_entries WHERE code = ?", Long.class,
				code);
		if (duplicate != null && duplicate > 0) {
			throw duplicateCode(code);
		}

		String entryId = UUID.randomUUID().toString();
		try {
			jdbcTemplate.update("""
					INSERT INTO cash_entries
						(id, code, direction, category, occurred_on, amount, currency, counterparty_id, project_id,
						 allocation_status, method, voucher_no, note, status, version, created_by, created_at, updated_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', 1, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
					""", entryId, code, direction, category, occurredOn, amount, currency, counterpartyId, projectId,
					allocationStatus, method, voucherNo, note, actor.userId());
		}
		catch (DuplicateKeyException ex) {
			throw duplicateCode(code);
		}

		Map<String, Object> after = dto(loadEntry(entryId, false));
		auditWriter.record("CASH_ENTRY", entryId, "CREATE", null, after);
		eventWriter.emit("cash_entry.created", "CASH_ENTRY", entryId, projectId, null, actor.userId(),
				number(after.get("version")), "FINANCE", "CONFIRMED", null,
				List.of("code", "direction", "category", "occurredOn", "amount", "currency"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> update(String entryId, Integer expectedVersion, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> beforeRow = loadEntry(entryId, true);
		if (beforeRow == null) {
			throw new MoldApiException(404, "NOT_FOUND", "流水不存在");
		}
		requireVisible(beforeRow);
		requireVersion(beforeRow, expectedVersion);
		requireNotVoid(beforeRow);
		if (beforeRow.get("reconciled_at") != null) {
			throw new MoldApiException(409, "RECONCILED_ENTRY", "已对账流水不可修改");
		}
		MoldFields.rejectUnknownFields(body, PATCH_FIELDS);
		MoldFields.requireAtLeastOneField(body, PATCH_FIELDS);

		String note = body.containsKey("note") ? MoldFields.optionalText(body.get("note"), "note", 2000)
				: (String) beforeRow.get("note");
		String voucherNo = body.containsKey("voucherNo")
				? MoldFields.optionalText(body.get("voucherNo"), "voucherNo", 64)
				: (String) beforeRow.get("voucher_no");

		Map<String, Object> before = dto(beforeRow);
		jdbcTemplate.update("""
				UPDATE cash_entries
				   SET note = ?, voucher_no = ?, version = version + 1,
				       updated_at = UTC_TIMESTAMP(3), updated_by = ?
				 WHERE id = ?
				""", note, voucherNo, actor.userId(), entryId);

		Map<String, Object> after = dto(loadEntry(entryId, false));
		auditWriter.record("CASH_ENTRY", entryId, "UPDATE", before, after);
		eventWriter.emit("cash_entry.updated", "CASH_ENTRY", entryId, (String) after.get("projectId"), null,
				actor.userId(), number(after.get("version")), "FINANCE", "CONFIRMED", "CONFIRMED",
				List.of("note", "voucherNo"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> voidEntry(String entryId, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		MoldFields.rejectUnknownFields(body, Set.of("reason"));
		String reason = MoldFields.text(body.get("reason"), "reason", 2000);
		lockPurchaseOf(entryId);
		Map<String, Object> beforeRow = loadEntry(entryId, true);
		if (beforeRow == null) {
			throw new MoldApiException(404, "NOT_FOUND", "流水不存在");
		}
		requireVisible(beforeRow);
		requireNotVoid(beforeRow);
		if (beforeRow.get("reconciled_at") != null) {
			throw new MoldApiException(409, "RECONCILED_ENTRY", "已对账流水不可作废，请先解对账");
		}
		if (beforeRow.get("reversed_by_entry_id") != null) {
			throw new MoldApiException(409, "ALREADY_REVERSED", "已被红冲的流水不可作废");
		}
		if (beforeRow.get("replaces_entry_id") != null || beforeRow.get("replaced_by_entry_id") != null) {
			throw new MoldApiException(409, "ALREADY_CORRECTED", "已进入更正链的流水不可单独作废");
		}
		if (beforeRow.get("reverses_entry_id") != null) {
			throw new MoldApiException(409, "POLICY_NOT_CONFIRMED", "红冲流水不可单独作废，请走更正流程");
		}
		BigDecimal voidAmount = (BigDecimal) beforeRow.get("amount");
		requirePurchaseNetPaidWithin(beforeRow,
				"OUT".equals(beforeRow.get("direction")) ? voidAmount.negate() : voidAmount);

		Map<String, Object> before = dto(beforeRow);
		jdbcTemplate.update("""
				UPDATE cash_entries
				   SET status = 'VOID', voided_at = UTC_TIMESTAMP(3), voided_by = ?, void_reason = ?,
				       version = version + 1, updated_at = UTC_TIMESTAMP(3), updated_by = ?
				 WHERE id = ?
				""", actor.userId(), reason, actor.userId(), entryId);

		Map<String, Object> after = dto(loadEntry(entryId, false));
		auditWriter.record("CASH_ENTRY", entryId, "VOID", before, after);
		eventWriter.emit("cash_entry.voided", "CASH_ENTRY", entryId, (String) after.get("projectId"), null,
				actor.userId(), number(after.get("version")), "FINANCE", "VOID", "CONFIRMED",
				List.of("status", "voidedAt", "voidedBy", "voidReason"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> reverse(String entryId, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		MoldFields.rejectUnknownFields(body, Set.of("reason", "occurredOn"));
		String reason = MoldFields.text(body.get("reason"), "reason", 2000);
		lockPurchaseOf(entryId);
		Map<String, Object> beforeRow = loadEntry(entryId, true);
		if (beforeRow == null) {
			throw new MoldApiException(404, "NOT_FOUND", "流水不存在");
		}
		requireFullVisibility();
		requireNotVoid(beforeRow);
		if (beforeRow.get("reconciled_at") != null) {
			throw new MoldApiException(409, "RECONCILED_ENTRY", "已对账流水不可红冲");
		}
		if (beforeRow.get("reversed_by_entry_id") != null) {
			throw new MoldApiException(409, "ALREADY_REVERSED", "该流水已被红冲");
		}
		if (beforeRow.get("replaces_entry_id") != null || beforeRow.get("replaced_by_entry_id") != null) {
			throw new MoldApiException(409, "ALREADY_CORRECTED", "已进入更正链的流水不可红冲");
		}
		if (beforeRow.get("reverses_entry_id") != null) {
			throw new MoldApiException(409, "POLICY_NOT_CONFIRMED", "红冲流水不可再红冲，请走更正流程");
		}
		String category = (String) beforeRow.get("category");
		String reverseCategory = REVERSE_CATEGORY.get(category);
		if (reverseCategory == null) {
			throw new MoldApiException(409, "POLICY_NOT_CONFIRMED", "退款类流水不可再红冲，请走更正/调整流程");
		}
		String reverseDirection = CATEGORY_DIRECTION.get(reverseCategory);
		BigDecimal reverseAmount = (BigDecimal) beforeRow.get("amount");
		requirePurchaseNetPaidWithin(beforeRow,
				"IN".equals(reverseDirection) ? reverseAmount.negate() : reverseAmount);
		LocalDate occurredOn = body.containsKey("occurredOn")
				? MoldFields.date(body.get("occurredOn"), "occurredOn")
				: ((java.sql.Date) beforeRow.get("occurred_on")).toLocalDate();

		String newId = UUID.randomUUID().toString();
		String code = "REV-" + UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO cash_entries
					(id, code, direction, category, occurred_on, amount, currency, counterparty_id, project_id,
					 purchase_id, allocation_status, method, voucher_no, note, status, reverses_entry_id,
					 reversal_reason, version, created_by, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 1, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
				""", newId, code, reverseDirection, reverseCategory, occurredOn, beforeRow.get("amount"),
				beforeRow.get("currency"), beforeRow.get("counterparty_id"), beforeRow.get("project_id"),
				beforeRow.get("purchase_id"), beforeRow.get("allocation_status"), beforeRow.get("method"), null,
				null, entryId, reason, actor.userId());

		Map<String, Object> before = dto(beforeRow);
		jdbcTemplate.update("""
				UPDATE cash_entries
				   SET reversed_by_entry_id = ?, version = version + 1,
				       updated_at = UTC_TIMESTAMP(3), updated_by = ?
				 WHERE id = ?
				""", newId, actor.userId(), entryId);

		Map<String, Object> newRow = loadEntry(newId, false);
		Map<String, Object> after = dto(loadEntry(entryId, false));
		auditWriter.record("CASH_ENTRY", entryId, "REVERSE", before, after);
		auditWriter.record("CASH_ENTRY", newId, "CREATE", null, dto(newRow));
		eventWriter.emit("cash_entry.reversed", "CASH_ENTRY", entryId, (String) after.get("projectId"), null,
				actor.userId(), number(after.get("version")), "FINANCE", "CONFIRMED", "CONFIRMED",
				List.of("reversedByEntryId"));
		eventWriter.emit("cash_entry.created", "CASH_ENTRY", newId, (String) after.get("projectId"), null,
				actor.userId(), 1, "FINANCE", "CONFIRMED", null, List.of("code", "amount", "category"));
		return dto(newRow);
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> reconcile(String entryId, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		MoldFields.rejectUnknownFields(body, Set.of("reconciliationRef", "reconciledOn"));
		String reference = MoldFields.text(body.get("reconciliationRef"), "reconciliationRef", 128);
		LocalDate reconciledOn = MoldFields.optionalDate(body.get("reconciledOn"), "reconciledOn");
		Map<String, Object> beforeRow = loadEntry(entryId, true);
		if (beforeRow == null) {
			throw new MoldApiException(404, "NOT_FOUND", "流水不存在");
		}
		requireNotVoid(beforeRow);
		if (beforeRow.get("reconciled_at") != null) {
			// 重复对账幂等返回当前值，不写审计、不涨版本。
			return dto(beforeRow);
		}
		if (beforeRow.get("reversed_by_entry_id") != null) {
			throw new MoldApiException(409, "ALREADY_REVERSED", "已红冲流水不可对账");
		}

		Map<String, Object> before = dto(beforeRow);
		Timestamp reconciledAt = reconciledOn == null ? null
				: Timestamp.valueOf(reconciledOn.atStartOfDay());
		if (reconciledAt == null) {
			jdbcTemplate.update("""
					UPDATE cash_entries
					   SET reconciled_at = UTC_TIMESTAMP(3), reconciled_by = ?, reconciliation_ref = ?,
					       version = version + 1, updated_at = UTC_TIMESTAMP(3), updated_by = ?
					 WHERE id = ?
					""", actor.userId(), reference, actor.userId(), entryId);
		}
		else {
			jdbcTemplate.update("""
					UPDATE cash_entries
					   SET reconciled_at = ?, reconciled_by = ?, reconciliation_ref = ?,
					       version = version + 1, updated_at = UTC_TIMESTAMP(3), updated_by = ?
					 WHERE id = ?
					""", reconciledAt, actor.userId(), reference, actor.userId(), entryId);
		}

		Map<String, Object> after = dto(loadEntry(entryId, false));
		auditWriter.record("CASH_ENTRY", entryId, "RECONCILE", before, after);
		eventWriter.emit("cash_entry.reconciled", "CASH_ENTRY", entryId, (String) after.get("projectId"), null,
				actor.userId(), number(after.get("version")), "FINANCE", "CONFIRMED", "CONFIRMED",
				List.of("reconciledAt", "reconciledBy", "reconciliationRef"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> unreconcile(String entryId, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		MoldFields.rejectUnknownFields(body, Set.of("reason"));
		String reason = MoldFields.text(body.get("reason"), "reason", 2000);
		Map<String, Object> beforeRow = loadEntry(entryId, true);
		if (beforeRow == null) {
			throw new MoldApiException(404, "NOT_FOUND", "流水不存在");
		}
		if (beforeRow.get("reconciled_at") == null) {
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "该流水尚未对账");
		}

		Map<String, Object> before = dto(beforeRow);
		jdbcTemplate.update("""
				UPDATE cash_entries
				   SET reconciled_at = NULL, reconciled_by = NULL, reconciliation_ref = NULL,
				       version = version + 1, updated_at = UTC_TIMESTAMP(3), updated_by = ?
				 WHERE id = ?
				""", actor.userId(), entryId);

		Map<String, Object> after = dto(loadEntry(entryId, false));
		after.put("unreconcileReason", reason);
		auditWriter.record("CASH_ENTRY", entryId, "UNRECONCILE", before, after);
		after.remove("unreconcileReason");
		eventWriter.emit("cash_entry.unreconciled", "CASH_ENTRY", entryId, (String) after.get("projectId"), null,
				actor.userId(), number(after.get("version")), "FINANCE", "CONFIRMED", "CONFIRMED",
				List.of("reconciledAt", "reconciledBy", "reconciliationRef"));
		return after;
	}

	// ------------------------------------------------------------------ 供更正服务复用

	Map<String, Object> loadEntry(String entryId, boolean forUpdate) {
		List<Map<String, Object>> rows = jdbcTemplate
			.queryForList("SELECT * FROM cash_entries WHERE id = ?" + (forUpdate ? " FOR UPDATE" : ""), entryId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * 关联采购单的流水在作废/红冲前先锁采购单，加锁顺序与采购付款一致（先采购单、后流水），避免死锁。
	 */
	private void lockPurchaseOf(String entryId) {
		List<Map<String, Object>> peek = jdbcTemplate
			.queryForList("SELECT purchase_id FROM cash_entries WHERE id = ?", entryId);
		if (!peek.isEmpty() && peek.get(0).get("purchase_id") != null) {
			jdbcTemplate.queryForList("SELECT id FROM purchases WHERE id = ? FOR UPDATE", peek.get(0).get("purchase_id"));
		}
	}

	/**
	 * 采购净付 = 付款 - 退款；变动 delta 后必须落在 [0, 采购金额] 内，否则 422 OVER_PAYMENT。
	 * 调用方已通过 lockPurchaseOf 持有采购单行锁。
	 */
	private void requirePurchaseNetPaidWithin(Map<String, Object> row, BigDecimal delta) {
		Object purchaseId = row.get("purchase_id");
		if (purchaseId == null) {
			return;
		}
		BigDecimal total = jdbcTemplate.queryForObject("SELECT amount_total FROM purchases WHERE id = ?",
				BigDecimal.class, purchaseId);
		BigDecimal paid = jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(CASE WHEN direction = 'OUT' THEN amount ELSE -amount END), 0)
				FROM cash_entries WHERE purchase_id = ? AND status = 'CONFIRMED' FOR SHARE
				""", BigDecimal.class, purchaseId);
		BigDecimal current = paid == null ? BigDecimal.ZERO : paid;
		BigDecimal after = current.add(delta);
		if (after.signum() < 0 || (total != null && after.compareTo(total) > 0)) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("purchaseAmount", total == null ? null : MoldFields.money(total));
			details.put("paidAmount", MoldFields.money(current));
			details.put("paidAmountAfter", MoldFields.money(after));
			throw new MoldApiException(422, "OVER_PAYMENT",
					after.signum() < 0 ? "操作后采购净付将为负数，请先处理相关退款" : "操作后采购净付将超过采购金额", details);
		}
	}

	boolean canViewAll() {
		return permissionService.hasPermission("mold:cash:viewAll");
	}

	/**
	 * 全量能力（OWNER/FINANCE）之外一律按不存在处理，避免泄漏流水是否存在。
	 */
	void requireFullVisibility() {
		if (!canViewAll()) {
			throw new MoldApiException(404, "NOT_FOUND", "流水不存在");
		}
	}

	private void requireVisible(Map<String, Object> row) {
		if (canViewAll()) {
			return;
		}
		if (!actorResolver.current().userId().equals(row.get("created_by"))) {
			throw new MoldApiException(404, "NOT_FOUND", "流水不存在");
		}
	}

	Map<String, Object> dto(Map<String, Object> row) {
		if (row == null) {
			return null;
		}
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
		dto.put("reversesEntryId", row.get("reverses_entry_id"));
		dto.put("reversedByEntryId", row.get("reversed_by_entry_id"));
		dto.put("reversalReason", row.get("reversal_reason"));
		dto.put("reconciledAt", MoldTime.iso(row.get("reconciled_at")));
		dto.put("reconciledBy", row.get("reconciled_by"));
		dto.put("reconciliationRef", row.get("reconciliation_ref"));
		dto.put("version", number(row.get("version")));
		dto.put("voidedAt", MoldTime.iso(row.get("voided_at")));
		dto.put("voidedBy", row.get("voided_by"));
		dto.put("voidReason", row.get("void_reason"));
		dto.put("replacesEntryId", row.get("replaces_entry_id"));
		dto.put("replacedByEntryId", row.get("replaced_by_entry_id"));
		return dto;
	}

	String dataVersion() {
		Long version = jdbcTemplate.queryForObject("SELECT version FROM event_clock WHERE singleton = 1", Long.class);
		return String.valueOf(version == null ? 0L : version);
	}

	// ------------------------------------------------------------------ 内部

	private List<Map<String, Object>> enrich(List<Map<String, Object>> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		Set<String> userIds = new LinkedHashSet<>();
		Set<String> linkIds = new LinkedHashSet<>();
		for (Map<String, Object> row : rows) {
			if (row.get("voided_by") != null) {
				userIds.add((String) row.get("voided_by"));
			}
			if (row.get("replaces_entry_id") != null) {
				linkIds.add((String) row.get("replaces_entry_id"));
			}
			if (row.get("replaced_by_entry_id") != null) {
				linkIds.add((String) row.get("replaced_by_entry_id"));
			}
		}
		Map<String, String> names = new HashMap<>();
		if (!userIds.isEmpty()) {
			String placeholders = String.join(",", userIds.stream().map(id -> "?").toList());
			jdbcTemplate.queryForList("SELECT id, display_name FROM users WHERE id IN (" + placeholders + ")",
					userIds.toArray())
				.forEach(user -> names.put((String) user.get("id"), (String) user.get("display_name")));
		}
		Map<String, String> codes = new HashMap<>();
		if (!linkIds.isEmpty()) {
			String placeholders = String.join(",", linkIds.stream().map(id -> "?").toList());
			jdbcTemplate.queryForList("SELECT id, code FROM cash_entries WHERE id IN (" + placeholders + ")",
					linkIds.toArray())
				.forEach(entry -> codes.put((String) entry.get("id"), (String) entry.get("code")));
		}
		List<Map<String, Object>> result = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			Map<String, Object> dto = dto(row);
			dto.put("voidedByName", row.get("voided_by") == null ? null : names.get(row.get("voided_by")));
			dto.put("replacesEntryCode",
					row.get("replaces_entry_id") == null ? null : codes.get(row.get("replaces_entry_id")));
			dto.put("replacedByEntryCode",
					row.get("replaced_by_entry_id") == null ? null : codes.get(row.get("replaced_by_entry_id")));
			result.add(dto);
		}
		return result;
	}

	private Map<String, Object> correctionChain(Map<String, Object> start) {
		Set<String> visited = new LinkedHashSet<>();
		visited.add((String) start.get("id"));
		List<Map<String, Object>> nodes = new ArrayList<>();
		nodes.add(start);
		boolean truncated = false;

		Map<String, Object> cursor = start;
		while (cursor.get("replaces_entry_id") != null) {
			String previousId = (String) cursor.get("replaces_entry_id");
			if (visited.contains(previousId)) {
				truncated = true;
				break;
			}
			Map<String, Object> previous = loadEntry(previousId, false);
			if (previous == null) {
				truncated = true;
				break;
			}
			visited.add(previousId);
			nodes.add(0, previous);
			cursor = previous;
		}

		cursor = start;
		while (cursor.get("replaced_by_entry_id") != null) {
			String nextId = (String) cursor.get("replaced_by_entry_id");
			if (visited.contains(nextId)) {
				truncated = true;
				break;
			}
			Map<String, Object> next = loadEntry(nextId, false);
			if (next == null) {
				truncated = true;
				break;
			}
			visited.add(nextId);
			nodes.add(next);
			cursor = next;
		}

		if (!canViewAll()) {
			String actorId = actorResolver.current().userId();
			int before = nodes.size();
			nodes = nodes.stream().filter(node -> actorId.equals(node.get("created_by"))).toList();
			if (nodes.size() < before) {
				truncated = true;
			}
		}

		List<Map<String, Object>> enriched = enrich(nodes);
		List<Map<String, Object>> chainNodes = new ArrayList<>();
		for (Map<String, Object> node : enriched) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", node.get("id"));
			item.put("code", node.get("code"));
			item.put("status", node.get("status"));
			item.put("direction", node.get("direction"));
			item.put("occurredOn", node.get("occurredOn"));
			item.put("amount", node.get("amount"));
			item.put("currency", node.get("currency"));
			item.put("voidedAt", node.get("voidedAt"));
			item.put("voidedByName", node.get("voidedByName"));
			item.put("voidReason", node.get("voidReason"));
			item.put("replacesEntryId", node.get("replacesEntryId"));
			item.put("replacedByEntryId", node.get("replacedByEntryId"));
			item.put("version", node.get("version"));
			chainNodes.add(item);
		}
		Map<String, Object> chain = new LinkedHashMap<>();
		chain.put("nodes", chainNodes);
		chain.put("truncated", truncated);
		return chain;
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

	private String resolveCounterparty(String category, Object counterpartyIdValue) {
		String counterpartyId = MoldFields.optionalText(counterpartyIdValue, "counterpartyId", 36);
		if (counterpartyId == null) {
			if ("CUSTOMER_RECEIPT".equals(category)) {
				throw MoldApiException.validation("/body/counterpartyId", "required", null, "客户收款必须指定客户");
			}
			return null;
		}
		List<Map<String, Object>> rows = jdbcTemplate
			.queryForList("SELECT type, status FROM counterparties WHERE id = ?", counterpartyId);
		if (rows.isEmpty()) {
			throw MoldApiException.validation("/body/counterpartyId", "reference", null, "往来单位不存在");
		}
		Map<String, Object> counterparty = rows.get(0);
		if (!"ACTIVE".equals(counterparty.get("status"))) {
			throw MoldApiException.validation("/body/counterpartyId", "reference", null, "往来单位已停用");
		}
		if ("CUSTOMER_RECEIPT".equals(category) && !"CUSTOMER".equals(counterparty.get("type"))) {
			throw MoldApiException.validation("/body/counterpartyId", "reference", null, "客户收款必须选择客户类型");
		}
		return counterpartyId;
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

	private static void requireNotVoid(Map<String, Object> row) {
		if ("VOID".equals(row.get("status"))) {
			throw new MoldApiException(409, "ENTRY_ALREADY_VOID", "流水已作废");
		}
	}

	private static MoldApiException duplicateCode(String code) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("field", "code");
		details.put("value", code);
		return new MoldApiException(409, "CASH_ENTRY_CODE_DUPLICATE", "流水编号已存在", details);
	}

	private static String enumQuery(String value, String field, Set<String> allowed) {
		String text = value.trim();
		if (!allowed.contains(text)) {
			throw MoldApiException.validation("/query/" + field, "enum", Map.of("allowedValues", List.copyOf(allowed)),
					"参数无效 " + field);
		}
		return text;
	}

	private static boolean parseBoolean(String value, String field) {
		if ("true".equalsIgnoreCase(value.trim())) {
			return true;
		}
		if ("false".equalsIgnoreCase(value.trim())) {
			return false;
		}
		throw MoldApiException.validation("/query/" + field, "type", null, "参数无效 " + field);
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

	static int number(Object value) {
		return value == null ? 0 : ((Number) value).intValue();
	}

	static String money(BigDecimal value) {
		return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

	static Timestamp timestamp(LocalDate date) {
		return date == null ? null : Timestamp.valueOf(LocalDateTime.of(date, java.time.LocalTime.MIDNIGHT));
	}

	static LocalDate dateOf(Object value) {
		if (value instanceof java.sql.Date sqlDate) {
			return sqlDate.toLocalDate();
		}
		if (value instanceof LocalDate localDate) {
			return localDate;
		}
		return LocalDate.parse(value.toString());
	}

	static String periodOf(LocalDate date) {
		return date == null ? null : date.toString().substring(0, 7);
	}

	static String utcIso(Timestamp value) {
		return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC).toString();
	}

}
