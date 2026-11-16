/*
 * 模具加工管理系统 M7-A / A3 审计日志查询（契约 6.10.2）
 *
 * GET /mold/audit-logs：append-only，只读；暴露 DB 全部 12 列（G-6），
 * id 以十进制字符串返回（bigint 防 JS 精度丢失），before/after/diff 反序列化为对象。
 */
package com.pig4cloud.pig.mold.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pig4cloud.pig.mold.web.MoldApiException;
import com.pig4cloud.pig.mold.web.MoldResponses;
import com.pig4cloud.pig.mold.web.MoldTime;
import com.pig4cloud.pig.mold.web.PageQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuditLogService {

	public static final Set<String> SORTABLE = Set.of("id", "createdAt", "entityType", "action");

	private static final Map<String, String> SORT_COLUMNS = Map.of("id", "id", "createdAt", "created_at",
			"entityType", "entity_type", "action", "action");

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	public Map<String, Object> list(PageQuery page, String entityType, String entityId, String actorId, String action,
			String dateFrom, String dateTo) {
		List<String> conditions = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		conditions.add("1 = 1");
		if (StringUtils.hasText(entityType)) {
			conditions.add("entity_type = ?");
			args.add(entityType.trim());
		}
		if (StringUtils.hasText(entityId)) {
			conditions.add("entity_id = ?");
			args.add(entityId.trim());
		}
		if (StringUtils.hasText(actorId)) {
			conditions.add("actor_id = ?");
			args.add(actorId.trim());
		}
		if (StringUtils.hasText(action)) {
			conditions.add("action = ?");
			args.add(action.trim());
		}
		if (StringUtils.hasText(dateFrom)) {
			conditions.add("created_at >= ?");
			args.add(java.sql.Date.valueOf(parseDate(dateFrom, "dateFrom")));
		}
		if (StringUtils.hasText(dateTo)) {
			conditions.add("created_at < DATE_ADD(?, INTERVAL 1 DAY)");
			args.add(java.sql.Date.valueOf(parseDate(dateTo, "dateTo")));
		}
		String where = " WHERE " + String.join(" AND ", conditions);

		Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs" + where, Long.class,
				args.toArray());
		long totalValue = total == null ? 0L : total;

		String direction = "desc".equals(page.order()) ? "DESC" : "ASC";
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(page.pageSize());
		pageArgs.add((long) (page.pageNum() - 1) * page.pageSize());
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"SELECT * FROM audit_logs" + where + " ORDER BY " + SORT_COLUMNS.get(page.sort()) + " " + direction
						+ ", id " + direction + " LIMIT ? OFFSET ?",
				pageArgs.toArray());

		List<Map<String, Object>> result = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			Map<String, Object> dto = new LinkedHashMap<>();
			dto.put("id", String.valueOf(row.get("id")));
			dto.put("entityType", row.get("entity_type"));
			dto.put("entityId", row.get("entity_id"));
			dto.put("action", row.get("action"));
			dto.put("actorId", row.get("actor_id"));
			dto.put("requestId", row.get("request_id"));
			dto.put("beforeData", parseJson(row.get("before_data")));
			dto.put("afterData", parseJson(row.get("after_data")));
			dto.put("diff", parseJson(row.get("diff")));
			dto.put("ip", row.get("ip"));
			dto.put("userAgent", row.get("user_agent"));
			dto.put("createdAt", MoldTime.iso(row.get("created_at")));
			result.add(dto);
		}
		return MoldResponses.page(result, totalValue, page.pageNum(), page.pageSize(), dataVersion());
	}

	private Object parseJson(Object value) {
		if (value == null) {
			return null;
		}
		try {
			return objectMapper.readValue(value.toString(), Object.class);
		}
		catch (Exception ex) {
			return value.toString();
		}
	}

	private String dataVersion() {
		Long version = jdbcTemplate.queryForObject("SELECT version FROM event_clock WHERE singleton = 1", Long.class);
		return String.valueOf(version == null ? 0L : version);
	}

	private static LocalDate parseDate(String raw, String field) {
		try {
			return LocalDate.parse(raw.trim());
		}
		catch (RuntimeException ex) {
			throw MoldApiException.validation("/query/" + field, "format", Map.of("format", "YYYY-MM-DD"),
					"参数无效 " + field);
		}
	}

}
