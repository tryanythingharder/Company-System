/*
 * 模具加工管理系统 M7-A / A2 更正（契约 6.9 最后 2 个操作）
 *
 *   POST /cash-entries/{entryId}/corrections/preview  mold:correction:preview（只读，If-Match 必填）
 *   POST /cash-entries/{entryId}/corrections          mold:correction:apply（If-Match + 幂等必填）
 *
 * 单事务完成 B1（纯误录作废）或 B2（作废 + 替代 + 双向关联）；4xx 整体回滚。
 * 预览与正式提交共用同一套校验与 impact 计算，保证两处口径一致。
 */
package com.pig4cloud.pig.mold.finance;

import com.pig4cloud.pig.mold.audit.AuditWriter;
import com.pig4cloud.pig.mold.event.EventWriter;
import com.pig4cloud.pig.mold.security.MoldActor;
import com.pig4cloud.pig.mold.security.MoldActorResolver;
import com.pig4cloud.pig.mold.web.MoldApiException;
import com.pig4cloud.pig.mold.web.MoldFields;
import com.pig4cloud.pig.mold.web.MoldTime;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CorrectionService {

	private static final Set<String> REFUND_CATEGORIES = Set.of("CUSTOMER_REFUND", "PURCHASE_REFUND",
			"EXPENSE_REFUND", "INCOME_REFUND");

	private static final Set<String> REPLACEMENT_FIELDS = Set.of("amount", "occurredOn", "method", "voucherNo", "note",
			"direction", "category", "currency", "counterpartyId", "projectId", "purchaseId", "allocationStatus");

	private final JdbcTemplate jdbcTemplate;

	private final CashEntryService cashEntryService;

	private final MoldActorResolver actorResolver;

	private final AuditWriter auditWriter;

	private final EventWriter eventWriter;

	// ------------------------------------------------------------------ 预览

	public Map<String, Object> preview(String entryId, Integer expectedVersion, Map<String, Object> body) {
		Map<String, Object> row = cashEntryService.loadEntry(entryId, false);
		if (row == null) {
			throw new MoldApiException(404, "NOT_FOUND", "流水不存在");
		}
		cashEntryService.requireFullVisibility();
		requireVersion(row, expectedVersion);
		Correction correction = compute(row, body, false);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("entryId", row.get("id"));
		result.put("version", CashEntryService.number(row.get("version")));
		result.put("impact", correction.impact());
		result.put("purchase", correction.purchase());
		return result;
	}

	// ------------------------------------------------------------------ 正式提交

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> apply(String entryId, Integer expectedVersion, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		// 锁顺序与付款/退款一致：先采购单、后流水，避免两条路径交叉加锁死锁；
		// 持有采购单锁后，其余付款无法并发写入，下面的净付校验才可靠。
		Map<String, Object> peek = cashEntryService.loadEntry(entryId, false);
		if (peek != null && peek.get("purchase_id") != null) {
			jdbcTemplate.queryForList("SELECT id FROM purchases WHERE id = ? FOR UPDATE", peek.get("purchase_id"));
		}
		Map<String, Object> row = cashEntryService.loadEntry(entryId, true);
		if (row == null) {
			throw new MoldApiException(404, "NOT_FOUND", "流水不存在");
		}
		cashEntryService.requireFullVisibility();
		requireVersion(row, expectedVersion);
		Correction correction = compute(row, body, true);

		if (correction.purchase() != null && Boolean.TRUE.equals(correction.purchase().get("overPayment"))) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("purchaseId", correction.purchase().get("purchaseId"));
			details.put("amountTotal", correction.purchase().get("amountTotal"));
			details.put("paidOther", correction.purchase().get("paidOther"));
			details.put("amountAfter", correction.purchase().get("amountAfter"));
			details.put("newPaidNet", correction.purchase().get("newPaidNet"));
			throw new MoldApiException(422, "OVER_PAYMENT", "更正后采购净付超过采购金额", details);
		}

		Map<String, Object> before = cashEntryService.dto(row);
		String replacementId = null;
		Map<String, Object> replacementDto = null;

		if (correction.mode().equals("REPLACE")) {
			Map<String, Object> replacement = correction.replacement();
			replacementId = UUID.randomUUID().toString();
			try {
				jdbcTemplate.update("""
						INSERT INTO cash_entries
							(id, code, direction, category, occurred_on, amount, currency, counterparty_id,
							 project_id, purchase_id, allocation_status, method, voucher_no, note, status,
							 replaces_entry_id, version, created_by, created_at, updated_at)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, 1, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
						""", replacementId, "COR-" + UUID.randomUUID(), row.get("direction"), row.get("category"),
						replacement.get("occurredOn"), replacement.get("amount"), row.get("currency"),
						row.get("counterparty_id"), row.get("project_id"), row.get("purchase_id"),
						row.get("allocation_status"), replacement.get("method"), replacement.get("voucherNo"),
						replacement.get("note"), entryId, actor.userId());
			}
			catch (DuplicateKeyException ex) {
				throw new MoldApiException(409, "ALREADY_CORRECTED", "该流水已有替代记录");
			}
			replacementDto = cashEntryService.dto(cashEntryService.loadEntry(replacementId, false));
		}

		jdbcTemplate.update("""
				UPDATE cash_entries
				   SET status = 'VOID', voided_at = UTC_TIMESTAMP(3), voided_by = ?, void_reason = ?,
				       replaced_by_entry_id = ?, version = version + 1,
				       updated_at = UTC_TIMESTAMP(3), updated_by = ?
				 WHERE id = ?
				""", actor.userId(), correction.reason(), replacementId, actor.userId(), entryId);

		Map<String, Object> after = cashEntryService.dto(cashEntryService.loadEntry(entryId, false));
		auditWriter.record("CASH_ENTRY", entryId, "VOID", before, after);
		if (replacementId != null) {
			auditWriter.record("CASH_ENTRY", replacementId, "CREATE", null, replacementDto);
		}
		Map<String, Object> correctionAudit = new LinkedHashMap<>();
		correctionAudit.put("reason", correction.reason());
		correctionAudit.put("originalId", entryId);
		correctionAudit.put("replacementId", replacementId);
		correctionAudit.put("mode", correction.mode());
		correctionAudit.put("impact", correction.impact());
		auditWriter.record("CASH_ENTRY", entryId, "CORRECT", null, correctionAudit);
		eventWriter.emit("cash_entry.corrected", "CASH_ENTRY", entryId, (String) after.get("projectId"), null,
				actor.userId(), CashEntryService.number(after.get("version")), "FINANCE", "VOID", "CONFIRMED",
				List.of("status", "voidedAt", "voidedBy", "voidReason", "replacedByEntryId"));
		if (replacementId != null) {
			eventWriter.emit("cash_entry.created", "CASH_ENTRY", replacementId, (String) after.get("projectId"), null,
					actor.userId(), 1, "FINANCE", "CONFIRMED", null,
					List.of("code", "amount", "occurredOn", "replacesEntryId"));
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", entryId);
		result.put("version", CashEntryService.number(after.get("version")));
		result.put("original", after);
		result.put("replacement", replacementDto);
		result.put("impact", correction.impact());
		return result;
	}

	// ------------------------------------------------------------------ 共用校验与影响计算

	private Correction compute(Map<String, Object> row, Map<String, Object> body, boolean locking) {
		if (row.get("reconciled_at") != null) {
			throw new MoldApiException(409, "RECONCILED_ENTRY", "已对账流水不能更正");
		}
		if (row.get("reverses_entry_id") != null || row.get("reversed_by_entry_id") != null
				|| row.get("reversal_reason") != null) {
			throw new MoldApiException(409, "ALREADY_REVERSED", "已红冲流水不能更正");
		}
		if (row.get("replaced_by_entry_id") != null) {
			throw new MoldApiException(409, "ALREADY_CORRECTED", "该流水已有替代记录");
		}
		if ("VOID".equals(row.get("status"))) {
			throw new MoldApiException(409, "ENTRY_ALREADY_VOID", "已作废流水不能再次更正");
		}
		String category = (String) row.get("category");
		if (REFUND_CATEGORIES.contains(category)) {
			throw new MoldApiException(409, "POLICY_NOT_CONFIRMED", "退款类流水尚未开放更正");
		}

		Map<String, Object> purchaseRow = null;
		if ("PURCHASE_PAYMENT".equals(category) && row.get("purchase_id") != null) {
			List<Map<String, Object>> rows = jdbcTemplate
				.queryForList("SELECT * FROM purchases WHERE id = ?" + (locking ? " FOR SHARE" : ""),
						row.get("purchase_id"));
			if (rows.isEmpty()) {
				throw new MoldApiException(404, "NOT_FOUND", "采购单不存在");
			}
			purchaseRow = rows.get(0);
			if (!"CONFIRMED".equals(purchaseRow.get("status"))) {
				throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "采购单未确认，不能更正其付款");
			}
		}

		MoldFields.rejectUnknownFields(body, Set.of("reason", "replacement"));
		String reason = MoldFields.text(body.get("reason"), "reason", 500);

		String mode = "VOID";
		Map<String, Object> replacement = null;
		Object rawReplacement = body.get("replacement");
		if (rawReplacement != null) {
			if (!(rawReplacement instanceof Map<?, ?> rawMap)) {
				throw MoldApiException.validation("/body/replacement", "type", null, "replacement 必须是对象");
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> input = (Map<String, Object>) rawMap;
			mode = "REPLACE";
			replacement = buildReplacement(row, input);
		}

		Map<String, Object> impact = impactOf(row, replacement);
		Map<String, Object> purchase = purchasePreview(row, purchaseRow, replacement, locking);
		return new Correction(mode, reason, replacement, impact, purchase);
	}

	private Map<String, Object> buildReplacement(Map<String, Object> row, Map<String, Object> input) {
		MoldFields.rejectUnknownFields(input, REPLACEMENT_FIELDS);
		compareImmutable(row, input, "direction", "direction");
		compareImmutable(row, input, "category", "category");
		compareImmutable(row, input, "currency", "currency");
		compareImmutable(row, input, "counterpartyId", "counterparty_id");
		compareImmutable(row, input, "projectId", "project_id");
		compareImmutable(row, input, "purchaseId", "purchase_id");
		compareImmutable(row, input, "allocationStatus", "allocation_status");

		BigDecimal originalAmount = (BigDecimal) row.get("amount");
		BigDecimal amount = input.containsKey("amount")
				? MoldFields.decimal(input.get("amount"), "replacement.amount", 2, true) : originalAmount;
		LocalDate occurredOn = input.containsKey("occurredOn")
				? MoldFields.date(input.get("occurredOn"), "replacement.occurredOn")
				: CashEntryService.dateOf(row.get("occurred_on"));
		String method = input.containsKey("method")
				? MoldFields.enumValue(input.get("method"), "replacement.method", CashEntryService.METHODS)
				: (String) row.get("method");
		String voucherNo = input.containsKey("voucherNo")
				? MoldFields.optionalText(input.get("voucherNo"), "replacement.voucherNo", 64)
				: (String) row.get("voucher_no");
		String note = input.containsKey("note")
				? MoldFields.optionalText(input.get("note"), "replacement.note", 2000) : (String) row.get("note");

		boolean same = amount.compareTo(originalAmount) == 0
				&& occurredOn.equals(CashEntryService.dateOf(row.get("occurred_on")))
				&& method.equals(row.get("method")) && java.util.Objects.equals(voucherNo, row.get("voucher_no"))
				&& java.util.Objects.equals(note, row.get("note"));
		if (same) {
			throw MoldApiException.validation("/body/replacement", "const", null, "替代记录至少需要一处实际差异");
		}

		Map<String, Object> replacement = new LinkedHashMap<>();
		replacement.put("amount", amount.setScale(2, RoundingMode.HALF_UP));
		replacement.put("occurredOn", occurredOn);
		replacement.put("method", method);
		replacement.put("voucherNo", voucherNo);
		replacement.put("note", note);
		return replacement;
	}

	private static void compareImmutable(Map<String, Object> row, Map<String, Object> input, String field,
			String column) {
		if (!input.containsKey(field)) {
			return;
		}
		Object expected = row.get(column);
		Object actual = input.get(field);
		String expectedText = expected == null ? null : expected.toString();
		String actualText = actual == null ? null : actual.toString();
		if (!java.util.Objects.equals(expectedText, actualText)) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("field", field);
			details.put("expected", expectedText);
			throw MoldApiException.validation("/body/replacement/" + field, "const", details,
					"不可修改字段 " + field);
		}
	}

	private Map<String, Object> impactOf(Map<String, Object> row, Map<String, Object> replacement) {
		BigDecimal amountBefore = ((BigDecimal) row.get("amount")).setScale(2, RoundingMode.HALF_UP);
		BigDecimal amountAfter = replacement == null ? null : (BigDecimal) replacement.get("amount");
		LocalDate dateBefore = CashEntryService.dateOf(row.get("occurred_on"));
		LocalDate dateAfter = replacement == null ? null : (LocalDate) replacement.get("occurredOn");
		String periodBefore = CashEntryService.periodOf(dateBefore);
		String periodAfter = CashEntryService.periodOf(dateAfter);
		int sign = "IN".equals(row.get("direction")) ? 1 : -1;

		List<Map<String, Object>> periodDeltas = new ArrayList<>();
		if (replacement == null) {
			periodDeltas.add(periodDelta(periodBefore, (String) row.get("currency"),
					amountBefore.negate(), sign));
		}
		else if (periodBefore.equals(periodAfter)) {
			BigDecimal delta = amountAfter.subtract(amountBefore);
			periodDeltas.add(periodDelta(periodBefore, (String) row.get("currency"), delta, sign));
		}
		else {
			periodDeltas.add(periodDelta(periodBefore, (String) row.get("currency"), amountBefore.negate(), sign));
			periodDeltas.add(periodDelta(periodAfter, (String) row.get("currency"), amountAfter, sign));
			periodDeltas.sort((left, right) -> ((String) left.get("period")).compareTo((String) right.get("period")));
		}

		BigDecimal totalAmountDelta = replacement == null ? amountBefore.negate() : amountAfter.subtract(amountBefore);
		BigDecimal totalNetDelta = totalAmountDelta.multiply(BigDecimal.valueOf(sign));

		Map<String, Object> impact = new LinkedHashMap<>();
		impact.put("mode", replacement == null ? "VOID" : "REPLACE");
		impact.put("currency", row.get("currency"));
		impact.put("direction", row.get("direction"));
		impact.put("dateBefore", dateBefore.toString());
		impact.put("dateAfter", dateAfter == null ? null : dateAfter.toString());
		impact.put("periodBefore", periodBefore);
		impact.put("periodAfter", periodAfter);
		impact.put("amountBefore", CashEntryService.money(amountBefore));
		impact.put("amountAfter", CashEntryService.money(amountAfter));
		impact.put("periodDeltas", periodDeltas);
		impact.put("totalAmountDelta", CashEntryService.money(totalAmountDelta));
		impact.put("totalNetDelta", CashEntryService.money(totalNetDelta));
		impact.put("totalUnchanged", replacement != null && amountAfter.compareTo(amountBefore) == 0);
		return impact;
	}

	private static Map<String, Object> periodDelta(String period, String currency, BigDecimal amountDelta, int sign) {
		Map<String, Object> delta = new LinkedHashMap<>();
		delta.put("period", period);
		delta.put("currency", currency);
		delta.put("amountDelta", CashEntryService.money(amountDelta));
		delta.put("netDelta", CashEntryService.money(amountDelta.multiply(BigDecimal.valueOf(sign))));
		return delta;
	}

	private Map<String, Object> purchasePreview(Map<String, Object> row, Map<String, Object> purchaseRow,
			Map<String, Object> replacement, boolean locking) {
		if (purchaseRow == null) {
			return null;
		}
		BigDecimal paidOther = jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(CASE WHEN direction = 'OUT' THEN amount ELSE -amount END), 0)
				FROM cash_entries
				WHERE purchase_id = ? AND status = 'CONFIRMED' AND id <> ?
				""" + (locking ? " FOR SHARE" : ""), BigDecimal.class, row.get("purchase_id"), row.get("id"));
		if (paidOther == null) {
			paidOther = BigDecimal.ZERO;
		}
		BigDecimal amountAfter = replacement == null ? null : (BigDecimal) replacement.get("amount");
		BigDecimal newPaidNet = paidOther.add(amountAfter == null ? BigDecimal.ZERO : amountAfter);
		BigDecimal amountTotal = ((BigDecimal) purchaseRow.get("amount_total")).setScale(2, RoundingMode.HALF_UP);

		Map<String, Object> preview = new LinkedHashMap<>();
		preview.put("purchaseId", purchaseRow.get("id"));
		preview.put("currency", purchaseRow.get("currency"));
		preview.put("amountTotal", CashEntryService.money(amountTotal));
		preview.put("paidOther", CashEntryService.money(paidOther));
		preview.put("amountAfter", CashEntryService.money(amountAfter));
		preview.put("newPaidNet", CashEntryService.money(newPaidNet));
		preview.put("overPayment", newPaidNet.compareTo(amountTotal) > 0);
		preview.put("purchaseStatus", purchaseRow.get("status"));
		return preview;
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

	private record Correction(String mode, String reason, Map<String, Object> replacement, Map<String, Object> impact,
			Map<String, Object> purchase) {
	}

}
