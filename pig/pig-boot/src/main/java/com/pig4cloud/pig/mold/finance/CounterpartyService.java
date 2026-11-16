/*
 * 模具加工管理系统 M7-A 往来单位（契约 6.8，3 操作）
 *   GET    /counterparties        mold:purchase:list
 *   POST   /counterparties        mold:counterparty:create（幂等必填）
 *   PATCH  /counterparties/{id}   mold:counterparty:update（If-Match 必填）
 *
 * 状态口径：库内 ACTIVE / INACTIVE，对外按旧契约映射为 ACTIVE / DISABLED。
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CounterpartyService {

	public static final Set<String> SORTABLE = Set.of("name", "type", "status");

	public static final Set<String> TYPES = Set.of("CUSTOMER", "SUPPLIER", "OTHER");

	public static final Set<String> PATCH_FIELDS = Set.of("name", "contact", "status");

	private static final Map<String, String> SORT_COLUMNS = Map.of("name", "c.name", "type", "c.type", "status",
			"c.status");

	private static final Map<String, String> OUT_STATUS = Map.of("ACTIVE", "ACTIVE", "INACTIVE", "DISABLED");

	private static final Map<String, String> IN_STATUS = Map.of("ACTIVE", "ACTIVE", "DISABLED", "INACTIVE");

	private final JdbcTemplate jdbcTemplate;

	private final MoldActorResolver actorResolver;

	private final AuditWriter auditWriter;

	private final EventWriter eventWriter;

	public Map<String, Object> list(PageQuery page, String type) {
		List<String> conditions = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		conditions.add("1 = 1");
		if (StringUtils.hasText(type)) {
			String value = type.trim();
			if (!TYPES.contains(value)) {
				throw MoldApiException.validation("/query/type", "enum", Map.of("allowedValues", List.copyOf(TYPES)),
						"参数无效 type");
			}
			conditions.add("c.type = ?");
			args.add(value);
		}
		String where = " WHERE " + String.join(" AND ", conditions);

		Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM counterparties c" + where, Long.class,
				args.toArray());
		long totalValue = total == null ? 0L : total;

		String direction = "desc".equals(page.order()) ? "DESC" : "ASC";
		String sql = "SELECT c.* FROM counterparties c" + where + " ORDER BY " + SORT_COLUMNS.get(page.sort()) + " "
				+ direction + ", c.id " + direction + " LIMIT ? OFFSET ?";
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(page.pageSize());
		pageArgs.add((long) (page.pageNum() - 1) * page.pageSize());

		List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, pageArgs.toArray()).stream().map(this::dto)
			.toList();
		return MoldResponses.page(rows, totalValue, page.pageNum(), page.pageSize(), dataVersion());
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> create(Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		MoldFields.rejectUnknownFields(body, Set.of("name", "type", "contact", "note", "phone", "taxNo"));
		String name = MoldFields.text(body.get("name"), "name", 200);
		String type = MoldFields.enumValue(body.get("type"), "type", TYPES);
		String contact = MoldFields.optionalText(body.get("contact"), "contact", 64);
		String phone = MoldFields.optionalText(body.get("phone"), "phone", 32);
		String taxNo = MoldFields.optionalText(body.get("taxNo"), "taxNo", 64);
		String note = MoldFields.optionalText(body.get("note"), "note", 2000);

		String id = UUID.randomUUID().toString();
		jdbcTemplate.update("""
				INSERT INTO counterparties (id, type, name, contact_name, phone, tax_no, note, status,
					created_by, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 1)
				""", id, type, name, contact, phone, taxNo, note, actor.userId());

		Map<String, Object> after = dto(loadRow(id));
		auditWriter.record("COUNTERPARTY", id, "CREATE", null, after);
		eventWriter.emit("counterparty.created", "COUNTERPARTY", id, null, null, actor.userId(),
				number(after.get("version")), "FINANCE", "ACTIVE", null, List.of("name", "type", "contact"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> update(String id, Integer expectedVersion, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> beforeRow = loadRow(id, true);
		if (beforeRow == null) {
			throw new MoldApiException(404, "NOT_FOUND", "往来单位不存在");
		}
		int currentVersion = ((Number) beforeRow.get("version")).intValue();
		if (expectedVersion == null) {
			throw MoldApiException.validation("/header/If-Match", "required", null, "缺少 If-Match 版本号");
		}
		if (expectedVersion != currentVersion) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("current", currentVersion);
			throw new MoldApiException(409, "VERSION_CONFLICT", "版本冲突，请刷新后重试", details);
		}

		MoldFields.rejectUnknownFields(body, PATCH_FIELDS);
		MoldFields.requireAtLeastOneField(body, PATCH_FIELDS);

		String name = body.containsKey("name") ? MoldFields.text(body.get("name"), "name", 200)
				: (String) beforeRow.get("name");
		String contact = body.containsKey("contact") ? MoldFields.optionalText(body.get("contact"), "contact", 64)
				: (String) beforeRow.get("contact_name");
		String status = body.containsKey("status") ? MoldFields.enumValue(body.get("status"), "status",
				Set.of("ACTIVE", "DISABLED")) : OUT_STATUS.get((String) beforeRow.get("status"));
		String dbStatus = IN_STATUS.getOrDefault(status, "ACTIVE");

		Map<String, Object> before = dto(beforeRow);
		jdbcTemplate.update("""
				UPDATE counterparties
				   SET name = ?, contact_name = ?, status = ?, version = version + 1, updated_at = UTC_TIMESTAMP(3)
				 WHERE id = ?
				""", name, contact, dbStatus, id);

		Map<String, Object> after = dto(loadRow(id));
		auditWriter.record("COUNTERPARTY", id, "UPDATE", before, after);
		eventWriter.emit("counterparty.updated", "COUNTERPARTY", id, null, null, actor.userId(),
				number(after.get("version")), "FINANCE", dbStatus, (String) beforeRow.get("status"),
				List.of("name", "contact", "status"));
		return after;
	}

	public Map<String, Object> get(String id) {
		Map<String, Object> row = loadRow(id);
		if (row == null) {
			throw new MoldApiException(404, "NOT_FOUND", "往来单位不存在");
		}
		return dto(row);
	}

	private Map<String, Object> loadRow(String id) {
		return loadRow(id, false);
	}

	private Map<String, Object> loadRow(String id, boolean forUpdate) {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"SELECT * FROM counterparties WHERE id = ?" + (forUpdate ? " FOR UPDATE" : ""), id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private Map<String, Object> dto(Map<String, Object> row) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", row.get("id"));
		dto.put("name", row.get("name"));
		dto.put("type", row.get("type"));
		dto.put("contact", row.get("contact_name"));
		dto.put("phone", row.get("phone"));
		dto.put("taxNo", row.get("tax_no"));
		dto.put("status", OUT_STATUS.getOrDefault((String) row.get("status"), (String) row.get("status")));
		dto.put("note", row.get("note"));
		dto.put("version", number(row.get("version")));
		dto.put("lastUpdatedAt", MoldTime.iso(row.get("updated_at")));
		return dto;
	}

	private String dataVersion() {
		Long version = jdbcTemplate.queryForObject("SELECT version FROM event_clock WHERE singleton = 1", Long.class);
		return String.valueOf(version == null ? 0L : version);
	}

	private static int number(Object value) {
		return value == null ? 0 : ((Number) value).intValue();
	}

}
