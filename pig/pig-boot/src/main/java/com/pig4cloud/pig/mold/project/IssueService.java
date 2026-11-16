/*
 * 模具加工管理系统 M2 后端切片
 * G5 异常事项（契约 6.7）：
 *   GET    /issues                     列表（全角色，排序白名单 title/severity/status，默认 id ASC）
 *   POST   /issues                     登记异常（D6：六个角色均保留 mold:issue:create）
 *   GET    /issues/{issueId}           详情
 *   PATCH  /issues/{issueId}           编辑（If-Match 必填）
 *   POST   /issues/{issueId}/resolve   解决
 *   POST   /issues/{issueId}/cancel    关闭
 *
 * 数据范围：update/resolve/cancel 只允许报告人、处理人、项目负责人。
 * 所有写操作在同一事务内写业务表、audit_logs 与 event_outbox，审计失败整体回滚。
 */
package com.pig4cloud.pig.mold.project;

import com.pig4cloud.pig.mold.audit.AuditWriter;
import com.pig4cloud.pig.mold.event.EventWriter;
import com.pig4cloud.pig.mold.security.MoldActor;
import com.pig4cloud.pig.mold.security.MoldActorResolver;
import com.pig4cloud.pig.mold.web.MoldApiException;
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
public class IssueService {

	public static final Set<String> SORTABLE = Set.of("id", "title", "severity", "status");

	public static final Set<String> STATUSES = Set.of("OPEN", "RESOLVED", "CANCELLED");

	public static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH");

	private static final Map<String, String> SORT_COLUMNS = Map.of("id", "i.id", "title", "i.title", "severity",
			"i.severity", "status", "i.status");

	private static final String SELECT_FROM = """
			SELECT i.*, u.display_name AS handler_display_name, r.display_name AS reporter_display_name
			FROM issues i
			LEFT JOIN users u ON u.id = i.handler_id
			JOIN users r ON r.id = i.reporter_id
			""";

	private final JdbcTemplate jdbcTemplate;

	private final MoldActorResolver actorResolver;

	private final AuditWriter auditWriter;

	private final EventWriter eventWriter;

	public Map<String, Object> list(PageQuery page, String projectId, String status, String severity) {
		List<String> conditions = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		conditions.add("i.deleted_at IS NULL");
		if (StringUtils.hasText(projectId)) {
			conditions.add("i.project_id = ?");
			args.add(projectId.trim());
		}
		if (StringUtils.hasText(status)) {
			conditions.add("i.status = ?");
			args.add(enumValue(status, "/query/status", STATUSES));
		}
		if (StringUtils.hasText(severity)) {
			conditions.add("i.severity = ?");
			args.add(enumValue(severity, "/query/severity", SEVERITIES));
		}
		String where = " WHERE " + String.join(" AND ", conditions);

		Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM issues i" + where, Long.class, args.toArray());
		String direction = "desc".equals(page.order()) ? "DESC" : "ASC";
		String sql = SELECT_FROM + where + " ORDER BY " + SORT_COLUMNS.get(page.sort()) + " " + direction + ", i.id "
				+ direction + " LIMIT ? OFFSET ?";

		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(page.pageSize());
		pageArgs.add((long) (page.pageNum() - 1) * page.pageSize());
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, pageArgs.toArray()).stream().map(this::dto)
			.toList();
		return MoldResponses.page(rows, total == null ? 0L : total, page.pageNum(), page.pageSize(), dataVersion());
	}

	public Map<String, Object> listForProject(String projectId) {
		requireProject(projectId, false);
		List<Map<String, Object>> rows = jdbcTemplate
			.queryForList(SELECT_FROM + " WHERE i.project_id = ? AND i.deleted_at IS NULL "
					+ "ORDER BY i.created_at, i.id", projectId)
			.stream()
			.map(this::dto)
			.toList();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("rows", rows);
		data.put("total", rows.size());
		data.put("asOf", MoldTime.nowIso());
		data.put("dataVersion", dataVersion());
		return data;
	}

	public Map<String, Object> get(String issueId) {
		return dto(loadIssue(issueId, false));
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> create(Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		rejectUnknownFields(body, Set.of("projectId", "stageId", "title", "description", "severity",
				"assigneeUserId"));

		String projectId = text(body.get("projectId"), "projectId", 36);
		Map<String, Object> project = requireProject(projectId, true);
		requireEditable(project);

		String stageId = optionalText(body.get("stageId"), "stageId", 36);
		requireStageInProject(stageId, projectId);
		String title = text(body.get("title"), "title", 200);
		String description = text(body.get("description"), "description", 5000);
		String severity = enumValue(body.get("severity"), "/body/severity", SEVERITIES);
		String assigneeUserId = optionalText(body.get("assigneeUserId"), "assigneeUserId", 36);
		requireActiveUser(assigneeUserId, "assigneeUserId");

		String issueId = UUID.randomUUID().toString();
		jdbcTemplate.update("""
				INSERT INTO issues
					(id, project_id, stage_id, title, description, severity, status, reporter_id, handler_id,
					 occurred_at, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 1)
				""", issueId, projectId, stageId, title, description, severity, actor.userId(), assigneeUserId);
		touchProject(projectId);

		Map<String, Object> after = dto(loadIssue(issueId, false));
		auditWriter.record("ISSUE", issueId, "CREATE", null, after);
		eventWriter.emit("issue.created", "ISSUE", issueId, projectId, stageId, actor.userId(),
				number(after.get("version")), "BASIC", "OPEN", null,
				List.of("title", "description", "severity", "assigneeUserId"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> update(String issueId, Integer expectedVersion, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> beforeRow = loadIssue(issueId, true);
		Map<String, Object> project = requireProject((String) beforeRow.get("project_id"), true);
		requireHandler(actor, beforeRow, project);
		requireEditable(project);
		requireOpen(beforeRow);
		checkVersion(expectedVersion, number(beforeRow.get("version")));

		rejectUnknownFields(body, Set.of("title", "description", "severity", "assigneeUserId"));
		String title = body.containsKey("title") ? text(body.get("title"), "title", 200)
				: (String) beforeRow.get("title");
		String description = body.containsKey("description") ? text(body.get("description"), "description", 5000)
				: (String) beforeRow.get("description");
		String severity = body.containsKey("severity") ? enumValue(body.get("severity"), "/body/severity", SEVERITIES)
				: (String) beforeRow.get("severity");
		String assigneeUserId = body.containsKey("assigneeUserId")
				? optionalText(body.get("assigneeUserId"), "assigneeUserId", 36)
				: (String) beforeRow.get("handler_id");
		requireActiveUser(assigneeUserId, "assigneeUserId");

		jdbcTemplate.update("""
				UPDATE issues
				   SET title = ?, description = ?, severity = ?, handler_id = ?,
				       version = version + 1, updated_at = UTC_TIMESTAMP(3)
				 WHERE id = ?
				""", title, description, severity, assigneeUserId, issueId);
		touchProject((String) beforeRow.get("project_id"));

		Map<String, Object> after = dto(loadIssue(issueId, false));
		auditWriter.record("ISSUE", issueId, "UPDATE", dto(beforeRow), after);
		eventWriter.emit("issue.updated", "ISSUE", issueId, (String) beforeRow.get("project_id"),
				(String) beforeRow.get("stage_id"), actor.userId(), number(after.get("version")), "BASIC", "OPEN",
				"OPEN", List.of("title", "description", "severity", "assigneeUserId"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> resolve(String issueId, Map<String, Object> body) {
		return close(issueId, body, "RESOLVED");
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> cancel(String issueId, Map<String, Object> body) {
		return close(issueId, body, "CANCELLED");
	}

	private Map<String, Object> close(String issueId, Map<String, Object> body, String targetStatus) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> beforeRow = loadIssue(issueId, true);
		Map<String, Object> project = requireProject((String) beforeRow.get("project_id"), true);
		requireHandler(actor, beforeRow, project);
		requireEditable(project);
		requireOpen(beforeRow);

		String note;
		if ("RESOLVED".equals(targetStatus)) {
			rejectUnknownFields(body, Set.of("resolution", "resolvedBy"));
			String resolvedBy = optionalText(body.get("resolvedBy"), "resolvedBy", 36);
			if (resolvedBy != null && !resolvedBy.equals(actor.userId())) {
				throw MoldApiException.validation("/body/resolvedBy", "not", null, "resolvedBy 不能冒用他人");
			}
			note = text(body.get("resolution"), "resolution", 2000);
		}
		else {
			rejectUnknownFields(body, Set.of("reason"));
			note = text(body.get("reason"), "reason", 2000);
		}

		jdbcTemplate.update("""
				UPDATE issues
				   SET status = ?, resolved_at = UTC_TIMESTAMP(3), resolution_note = ?,
				       version = version + 1, updated_at = UTC_TIMESTAMP(3)
				 WHERE id = ?
				""", targetStatus, note, issueId);
		touchProject((String) beforeRow.get("project_id"));

		Map<String, Object> after = dto(loadIssue(issueId, false));
		String action = "RESOLVED".equals(targetStatus) ? "RESOLVE" : "CANCEL";
		String eventType = "RESOLVED".equals(targetStatus) ? "issue.resolved" : "issue.cancelled";
		auditWriter.record("ISSUE", issueId, action, dto(beforeRow), after);
		eventWriter.emit(eventType, "ISSUE", issueId, (String) beforeRow.get("project_id"),
				(String) beforeRow.get("stage_id"), actor.userId(), number(after.get("version")), "BASIC",
				targetStatus, "OPEN", List.of("status", "resolution"));
		return after;
	}

	private Map<String, Object> requireProject(String projectId, boolean forUpdate) {
		String sql = "SELECT * FROM projects WHERE id = ? AND deleted_at IS NULL" + (forUpdate ? " FOR UPDATE" : "");
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, projectId);
		if (rows.isEmpty()) {
			throw new MoldApiException(404, "NOT_FOUND", "项目不存在或已删除");
		}
		return rows.get(0);
	}

	private Map<String, Object> loadIssue(String issueId, boolean forUpdate) {
		String sql = SELECT_FROM + " WHERE i.id = ? AND i.deleted_at IS NULL" + (forUpdate ? " FOR UPDATE" : "");
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, issueId);
		if (rows.isEmpty()) {
			throw new MoldApiException(404, "NOT_FOUND", "异常不存在或已删除");
		}
		return rows.get(0);
	}

	private void requireStageInProject(String stageId, String projectId) {
		if (stageId == null) {
			return;
		}
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM project_stages WHERE id = ? AND project_id = ? AND deleted_at IS NULL",
				Long.class, stageId, projectId);
		if (count == null || count == 0) {
			throw MoldApiException.validation("/body/stageId", "reference", null, "阶段不属于该项目或已删除");
		}
	}

	private void requireHandler(MoldActor actor, Map<String, Object> issue, Map<String, Object> project) {
		if (actor.userId().equals(issue.get("reporter_id")) || actor.userId().equals(issue.get("handler_id"))
				|| actor.userId().equals(project.get("owner_user_id"))) {
			return;
		}
		throw new MoldApiException(403, "FORBIDDEN", "仅报告人、处理人或项目负责人可维护该异常");
	}

	private void requireEditable(Map<String, Object> project) {
		String status = (String) project.get("status");
		if (!"ACTIVE".equals(status) && !"ON_HOLD".equals(status)) {
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "项目已关闭，不可维护异常");
		}
	}

	private void requireOpen(Map<String, Object> issue) {
		if (!"OPEN".equals(issue.get("status"))) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("currentStatus", issue.get("status"));
			details.put("requestedAction", "UPDATE");
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "异常已处理，不能重复操作", details);
		}
	}

	private void checkVersion(Integer expectedVersion, int currentVersion) {
		if (expectedVersion == null) {
			throw MoldApiException.validation("/header/If-Match", "required", null, "缺少 If-Match 版本号");
		}
		if (expectedVersion != currentVersion) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("current", currentVersion);
			throw new MoldApiException(409, "VERSION_CONFLICT", "版本冲突，请刷新后重试", details);
		}
	}

	private void touchProject(String projectId) {
		jdbcTemplate.update("UPDATE projects SET updated_at = UTC_TIMESTAMP(3) WHERE id = ?", projectId);
	}

	private Map<String, Object> dto(Map<String, Object> row) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", row.get("id"));
		dto.put("projectId", row.get("project_id"));
		dto.put("stageId", row.get("stage_id"));
		dto.put("title", row.get("title"));
		dto.put("description", row.get("description"));
		dto.put("severity", row.get("severity"));
		dto.put("status", row.get("status"));
		dto.put("assigneeUserId", row.get("handler_id"));
		dto.put("assigneeDisplayName", row.get("handler_display_name"));
		dto.put("reporterId", row.get("reporter_id"));
		dto.put("reporterDisplayName", row.get("reporter_display_name"));
		dto.put("resolution", row.get("resolution_note"));
		dto.put("version", number(row.get("version")));
		dto.put("occurredAt", MoldTime.iso(row.get("occurred_at")));
		dto.put("resolvedAt", MoldTime.iso(row.get("resolved_at")));
		dto.put("createdAt", MoldTime.iso(row.get("created_at")));
		dto.put("updatedAt", MoldTime.iso(row.get("updated_at")));
		return dto;
	}

	private String dataVersion() {
		Long version = jdbcTemplate.queryForObject("SELECT version FROM event_clock WHERE singleton = 1", Long.class);
		return String.valueOf(version == null ? 0L : version);
	}

	private void rejectUnknownFields(Map<String, Object> body, Set<String> allowed) {
		for (String key : body.keySet()) {
			if (!allowed.contains(key)) {
				throw MoldApiException.validation("/body/" + key, "additionalProperties", null,
						"不允许通过该接口修改字段 " + key);
			}
		}
	}

	private void requireActiveUser(String userId, String field) {
		if (userId == null) {
			return;
		}
		Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE id = ? AND status = 'ACTIVE'",
				Long.class, userId);
		if (count == null || count == 0) {
			throw MoldApiException.validation("/body/" + field, "reference", null, "用户不存在或已停用");
		}
	}

	private static String enumValue(Object value, String path, Set<String> allowed) {
		String text = value == null ? null : value.toString().trim();
		if (text == null || !allowed.contains(text)) {
			throw MoldApiException.validation(path, "enum", Map.of("allowedValues", List.copyOf(allowed)),
					"取值无效");
		}
		return text;
	}

	private static String text(Object value, String field, int maxLength) {
		String text = value == null ? null : value.toString().trim();
		if (!StringUtils.hasText(text)) {
			throw MoldApiException.validation("/body/" + field, "required", null, field + " 不能为空");
		}
		if (text.length() > maxLength) {
			throw MoldApiException.validation("/body/" + field, "maxLength", Map.of("limit", maxLength),
					field + " 长度不能超过 " + maxLength);
		}
		return text;
	}

	private static String optionalText(Object value, String field, int maxLength) {
		if (value == null || !StringUtils.hasText(value.toString())) {
			return null;
		}
		String text = value.toString().trim();
		if (text.length() > maxLength) {
			throw MoldApiException.validation("/body/" + field, "maxLength", Map.of("limit", maxLength),
					field + " 长度不能超过 " + maxLength);
		}
		return text;
	}

	private static int number(Object value) {
		return value == null ? 0 : ((Number) value).intValue();
	}

}
