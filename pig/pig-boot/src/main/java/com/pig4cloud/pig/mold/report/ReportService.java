/*
 * 模具加工管理系统 M7-A / A3 报表（契约 6.9.3）
 *
 *   GET /mold/reports/project-cash   projectId 必填
 *   GET /mold/reports/cash-summary   projectId/allocationStatus/currency/dateFrom/dateTo 可选
 *
 * 口径：
 *  - 只统计 status='CONFIRMED' 的 cash_entries 与 purchases；
 *  - 金额一律字符串 2 位小数，无数据 "0.00"；
 *  - categories[] 恒 9 项、按 CashCategory 枚举顺序，缺数据补 0；
 *  - conversion 恒 "NONE"，不跨币种换算；
 *  - reconciledNet 只累加区间内 CONFIRMED 且 reconciled_at IS NOT NULL 的流水（G-9）；
 *  - dateFrom/dateTo 同时过滤 occurred_on 与 purchase_date（G-11，已知口径）。
 */
package com.pig4cloud.pig.mold.report;

import com.pig4cloud.pig.mold.web.MoldApiException;
import com.pig4cloud.pig.mold.web.MoldTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReportService {

	/** 契约 6.9.3：categories[] 固定 9 项、顺序按 CashCategory 枚举。 */
	public static final List<String> CATEGORY_ORDER = List.of("CUSTOMER_RECEIPT", "CUSTOMER_REFUND",
			"PURCHASE_PAYMENT", "PURCHASE_REFUND", "DAILY_EXPENSE", "EXPENSE_REFUND", "OTHER_INCOME", "INCOME_REFUND",
			"OTHER_EXPENSE");

	public static final Map<String, String> CATEGORY_DIRECTION = Map.ofEntries(
			Map.entry("CUSTOMER_RECEIPT", "IN"), Map.entry("CUSTOMER_REFUND", "OUT"),
			Map.entry("PURCHASE_PAYMENT", "OUT"), Map.entry("PURCHASE_REFUND", "IN"),
			Map.entry("DAILY_EXPENSE", "OUT"), Map.entry("EXPENSE_REFUND", "IN"), Map.entry("OTHER_INCOME", "IN"),
			Map.entry("INCOME_REFUND", "OUT"), Map.entry("OTHER_EXPENSE", "OUT"));

	public static final Set<String> CURRENCIES = Set.of("CNY", "USD", "EUR", "HKD");

	public static final Set<String> ALLOCATIONS = Set.of("PROJECT", "PUBLIC", "UNASSIGNED");

	private final JdbcTemplate jdbcTemplate;

	public Map<String, Object> projectCash(String projectId, String dateFrom, String dateTo) {
		if (!StringUtils.hasText(projectId)) {
			throw MoldApiException.validation("/query/projectId", "required", null, "projectId 必填");
		}
		Long exists = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM projects WHERE id = ? AND deleted_at IS NULL", Long.class, projectId.trim());
		if (exists == null || exists == 0) {
			throw new MoldApiException(404, "NOT_FOUND", "项目不存在或已删除");
		}
		Filters filters = filters(projectId.trim(), "PROJECT", null, dateFrom, dateTo);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("projectId", projectId.trim());
		data.put("currencies", currencies(filters));
		return data;
	}

	public Map<String, Object> cashSummary(String projectId, String allocationStatus, String currency,
			String dateFrom, String dateTo) {
		String allocation = null;
		if (StringUtils.hasText(allocationStatus)) {
			allocation = allocationStatus.trim();
			if (!ALLOCATIONS.contains(allocation)) {
				throw MoldApiException.validation("/query/allocationStatus", "enum",
						Map.of("allowedValues", List.copyOf(ALLOCATIONS)), "参数无效 allocationStatus");
			}
		}
		String currencyValue = null;
		if (StringUtils.hasText(currency)) {
			currencyValue = currency.trim();
			if (!CURRENCIES.contains(currencyValue)) {
				throw MoldApiException.validation("/query/currency", "enum",
						Map.of("allowedValues", List.copyOf(CURRENCIES)), "参数无效 currency");
			}
		}
		String project = StringUtils.hasText(projectId) ? projectId.trim() : null;
		if (project != null) {
			Long exists = jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM projects WHERE id = ? AND deleted_at IS NULL", Long.class, project);
			if (exists == null || exists == 0) {
				throw new MoldApiException(404, "NOT_FOUND", "项目不存在或已删除");
			}
		}
		Filters filters = filters(project, allocation, currencyValue, dateFrom, dateTo);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("currencies", currencies(filters));
		data.put("byAllocation", byAllocation(filters));
		return data;
	}

	// ------------------------------------------------------------------ 内部

	private List<Map<String, Object>> currencies(Filters filters) {
		List<Map<String, Object>> cashRows = jdbcTemplate.queryForList("""
				SELECT currency, category, SUM(amount) AS amount, COUNT(*) AS cnt
				FROM cash_entries
				%s
				GROUP BY currency, category
				""".formatted(filters.cashWhere()), filters.cashArgs().toArray());

		List<Map<String, Object>> purchaseRows = jdbcTemplate.queryForList("""
				SELECT currency, SUM(amount_total) AS amount
				FROM purchases
				%s
				GROUP BY currency
				""".formatted(filters.purchaseWhere()), filters.purchaseArgs().toArray());

		List<Map<String, Object>> reconciledRows = jdbcTemplate.queryForList("""
				SELECT currency, SUM(CASE WHEN direction = 'IN' THEN amount ELSE -amount END) AS net
				FROM cash_entries
				%s AND reconciled_at IS NOT NULL
				GROUP BY currency
				""".formatted(filters.cashWhere()), filters.cashArgs().toArray());

		Set<String> currencies = new LinkedHashSet<>();
		cashRows.forEach(row -> currencies.add((String) row.get("currency")));
		purchaseRows.forEach(row -> currencies.add((String) row.get("currency")));
		List<String> ordered = new ArrayList<>(currencies);
		ordered.sort(Comparator.naturalOrder());

		List<Map<String, Object>> blocks = new ArrayList<>();
		for (String currency : ordered) {
			Map<String, BigDecimal> amounts = new LinkedHashMap<>();
			Map<String, Integer> counts = new LinkedHashMap<>();
			for (String category : CATEGORY_ORDER) {
				amounts.put(category, BigDecimal.ZERO);
				counts.put(category, 0);
			}
			for (Map<String, Object> row : cashRows) {
				if (!currency.equals(row.get("currency"))) {
					continue;
				}
				String category = (String) row.get("category");
				if (!amounts.containsKey(category)) {
					continue;
				}
				amounts.put(category, (BigDecimal) row.get("amount"));
				counts.put(category, ((Number) row.get("cnt")).intValue());
			}

			List<Map<String, Object>> categories = new ArrayList<>();
			BigDecimal netCash = BigDecimal.ZERO;
			for (String category : CATEGORY_ORDER) {
				String direction = CATEGORY_DIRECTION.get(category);
				BigDecimal amount = amounts.get(category);
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("category", category);
				item.put("direction", direction);
				item.put("amount", money(amount));
				item.put("count", counts.get(category));
				categories.add(item);
				netCash = "IN".equals(direction) ? netCash.add(amount) : netCash.subtract(amount);
			}

			BigDecimal purchaseCommitted = BigDecimal.ZERO;
			for (Map<String, Object> row : purchaseRows) {
				if (currency.equals(row.get("currency"))) {
					purchaseCommitted = (BigDecimal) row.get("amount");
				}
			}
			BigDecimal reconciledNet = BigDecimal.ZERO;
			for (Map<String, Object> row : reconciledRows) {
				if (currency.equals(row.get("currency"))) {
					reconciledNet = (BigDecimal) row.get("net");
				}
			}

			Map<String, Object> block = new LinkedHashMap<>();
			block.put("currency", currency);
			block.put("categories", categories);
			block.put("netCash", money(netCash));
			block.put("purchaseCommitted", money(purchaseCommitted));
			block.put("paidNet", money(amounts.get("PURCHASE_PAYMENT").subtract(amounts.get("PURCHASE_REFUND"))));
			block.put("collectedNet",
					money(amounts.get("CUSTOMER_RECEIPT").subtract(amounts.get("CUSTOMER_REFUND"))));
			block.put("reconciledNet", money(reconciledNet));
			block.put("conversion", "NONE");
			blocks.add(block);
		}
		return blocks;
	}

	private List<Map<String, Object>> byAllocation(Filters filters) {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
				SELECT allocation_status, currency,
				       SUM(CASE WHEN direction = 'IN' THEN amount ELSE -amount END) AS net
				FROM cash_entries
				%s
				GROUP BY allocation_status, currency
				ORDER BY allocation_status, currency
				""".formatted(filters.cashWhere()), filters.cashArgs().toArray());
		List<Map<String, Object>> result = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("allocationStatus", row.get("allocation_status"));
			item.put("currency", row.get("currency"));
			item.put("netCash", money((BigDecimal) row.get("net")));
			result.add(item);
		}
		return result;
	}

	private static Filters filters(String projectId, String allocationStatus, String currency, String dateFromRaw,
			String dateToRaw) {
		List<Object> cashArgs = new ArrayList<>();
		List<Object> purchaseArgs = new ArrayList<>();
		List<String> cash = new ArrayList<>();
		List<String> purchase = new ArrayList<>();
		cash.add("status = 'CONFIRMED'");
		purchase.add("status = 'CONFIRMED'");
		if (projectId != null) {
			cash.add("project_id = ?");
			purchase.add("project_id = ?");
			cashArgs.add(projectId);
			purchaseArgs.add(projectId);
		}
		if (allocationStatus != null) {
			cash.add("allocation_status = ?");
			cashArgs.add(allocationStatus);
		}
		if (currency != null) {
			cash.add("currency = ?");
			purchase.add("currency = ?");
			cashArgs.add(currency);
			purchaseArgs.add(currency);
		}
		LocalDate dateFrom = parseDate(dateFromRaw, "dateFrom");
		LocalDate dateTo = parseDate(dateToRaw, "dateTo");
		if (dateFrom != null) {
			cash.add("occurred_on >= ?");
			purchase.add("purchase_date >= ?");
			cashArgs.add(java.sql.Date.valueOf(dateFrom));
			purchaseArgs.add(java.sql.Date.valueOf(dateFrom));
		}
		if (dateTo != null) {
			cash.add("occurred_on <= ?");
			purchase.add("purchase_date <= ?");
			cashArgs.add(java.sql.Date.valueOf(dateTo));
			purchaseArgs.add(java.sql.Date.valueOf(dateTo));
		}
		return new Filters(" WHERE " + String.join(" AND ", cash), List.copyOf(cashArgs),
				" WHERE " + String.join(" AND ", purchase), List.copyOf(purchaseArgs));
	}

	private static LocalDate parseDate(String raw, String field) {
		if (!StringUtils.hasText(raw)) {
			return null;
		}
		try {
			return LocalDate.parse(raw.trim());
		}
		catch (RuntimeException ex) {
			throw MoldApiException.validation("/query/" + field, "format", Map.of("format", "YYYY-MM-DD"),
					"参数无效 " + field);
		}
	}

	private static String money(BigDecimal value) {
		return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

	private record Filters(String cashWhere, List<Object> cashArgs, String purchaseWhere, List<Object> purchaseArgs) {
	}

}
