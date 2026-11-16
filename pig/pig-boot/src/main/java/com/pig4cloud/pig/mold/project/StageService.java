/*
 * 模具加工管理系统 M2 后端切片
 * G4 阶段与时间线（契约 6.6）：
 *   GET    /projects/{projectId}/stages   阶段列表（全角色只读）
 *   POST   /projects/{projectId}/stages   新增阶段（仅该项目负责人）
 *   PATCH  /stages/{stageId}              编辑阶段（对象级：负责人 / 阶段责任人 / 阶段成员）
 *   POST   /stages/{stageId}/transitions  状态推进（同上）
 *   GET    /stages/{stageId}/events       阶段历史（append-only，只读）
 *   GET    /me/stages                     我的阶段（责任人 / 成员）
 *
 * 所有写操作在同一事务内写业务表、stage_events、event_outbox 与 audit_logs；
 * 审计或事件写入失败时业务整体回滚。
 */
package com.pig4cloud.pig.mold.project;

import com.pig4cloud.pig.mold.audit.AuditWriter;
import com.pig4cloud.pig.mold.event.EventWriter;
import com.pig4cloud.pig.mold.security.MoldActor;
import com.pig4cloud.pig.mold.security.MoldActorResolver;
import com.pig4cloud.pig.mold.web.MoldApiException;
import com.pig4cloud.pig.mold.web.MoldResponses;
import com.pig4cloud.pig.mold.web.MoldTime;
import com.pig4cloud.pig.mold.web.MoldRequestIdFilter;
import com.pig4cloud.pig.mold.web.PageQuery;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class StageService {

	public static final Set<String> SORTABLE = Set.of("name", "status", "sortOrder", "updatedAt");

	private static final Map<String, String> SORT_COLUMNS = Map.of("id", "s.id", "name", "s.name", "status",
			"s.status", "sortOrder", "s.sort_order", "updatedAt", "s.updated_at");

	private static final Set<String> EVENT_SORTABLE = Set.of("id", "action", "occurredAt");

	private static final Map<String, String> EVENT_SORT_COLUMNS = Map.of("id", "e.id", "action", "e.action",
			"occurredAt", "e.created_at");

	private static final String STAGE_SELECT = """
			SELECT s.* FROM project_stages s
			""";

	private final JdbcTemplate jdbcTemplate;

	private final MoldActorResolver actorResolver;

	private final AuditWriter auditWriter;

	private final EventWriter eventWriter;

	public Map<String, Object> listStages(String projectId) {
		requireProject(projectId, false);
		List<Map<String, Object>> rows = jdbcTemplate
			.queryForList(STAGE_SELECT + " WHERE s.project_id = ? AND s.deleted_at IS NULL ORDER BY s.sort_order, s.id",
					projectId)
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

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> createStage(String projectId, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> project = requireProject(projectId, true);
		requireOwner(actor, project);
		requireEditableProject(project);

		rejectUnknownFields(body, Set.of("name", "sortOrder", "responsibleUserId", "plannedStart", "plannedEnd",
				"note"));
		String name = text(body.get("name"), "name", 200);
		int sortOrder = integer(body.get("sortOrder"), "sortOrder", 0);
		String responsibleUserId = blankToNull(body.get("responsibleUserId"));
		LocalDate plannedStart = date(body.get("plannedStart"), "plannedStart");
		LocalDate plannedEnd = date(body.get("plannedEnd"), "plannedEnd");
		requireActiveUser(responsibleUserId);
		requireDateOrder(plannedStart, plannedEnd);
		String note = nullableText(body.get("note"), "note", 2000);

		String stageId = java.util.UUID.randomUUID().toString();
		jdbcTemplate.update("""
				INSERT INTO project_stages
					(id, project_id, name, sort_order, responsible_user_id, planned_start, planned_end, note,
					 created_by, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 1)
				""", stageId, projectId, name, sortOrder, responsibleUserId, plannedStart, plannedEnd, note,
				actor.userId());

		Map<String, Object> stage = loadStage(stageId, false);
		touchProject(projectId);
		appendEvent(stageId, "CREATE", null, StageStateMachine.NOT_STARTED, note, null, actor.userId());
		auditWriter.record("STAGE", stageId, "CREATE", null, dto(stage));
		eventWriter.emit("stage.created", "STAGE", stageId, projectId, stageId, actor.userId(),
				number(stage.get("version")), "BASIC", StageStateMachine.NOT_STARTED, null, List.of("name"));
		return dto(stage);
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> updateStage(String stageId, Integer expectedVersion, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> stage = loadStage(stageId, true);
		Map<String, Object> project = requireProject((String) stage.get("project_id"), true);
		requireEditableProject(project);

		boolean manager = actor.userId().equals(project.get("owner_user_id"));
		if (!manager) {
			requireAssigned(actor, stage);
			Set<String> employeeFields = Set.of("note", "plannedStart", "plannedEnd");
			for (String key : body.keySet()) {
				if (!employeeFields.contains(key)) {
					throw new MoldApiException(403, "FORBIDDEN", "员工仅可更新本人阶段计划与说明");
				}
			}
		}

		checkVersion(expectedVersion, number(stage.get("version")));
		rejectUnknownFields(body, Set.of("name", "sortOrder", "responsibleUserId", "plannedStart", "plannedEnd",
				"note"));

		String oldStatus = (String) stage.get("status");
		String name = body.containsKey("name") ? text(body.get("name"), "name", 200) : (String) stage.get("name");
		int sortOrder = body.containsKey("sortOrder") ? integer(body.get("sortOrder"), "sortOrder", 0)
				: number(stage.get("sort_order"));
		String responsibleUserId = body.containsKey("responsibleUserId") ? blankToNull(body.get("responsibleUserId"))
				: (String) stage.get("responsible_user_id");
		LocalDate plannedStart = body.containsKey("plannedStart") ? date(body.get("plannedStart"), "plannedStart")
				: toDate(stage.get("planned_start"));
		LocalDate plannedEnd = body.containsKey("plannedEnd") ? date(body.get("plannedEnd"), "plannedEnd")
				: toDate(stage.get("planned_end"));
		String note = body.containsKey("note") ? nullableText(body.get("note"), "note", 2000)
				: (String) stage.get("note");

		if (manager && body.containsKey("responsibleUserId") && !StageStateMachine.NOT_STARTED.equals(oldStatus)) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("conflictKind", "STAGE_STATE");
			details.put("resourceType", "STAGE");
			details.put("currentStatus", oldStatus);
			details.put("allowedActions", StageStateMachine.allowedActions(oldStatus));
			details.put("allowedReassignStatuses", List.of(StageStateMachine.NOT_STARTED));
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "仅未开工阶段可转派责任人", details);
		}
		requireActiveUser(responsibleUserId);
		requireDateOrder(plannedStart, plannedEnd);

		Map<String, Object> before = dto(stage);
		jdbcTemplate.update("""
				UPDATE project_stages
				   SET name = ?, sort_order = ?, responsible_user_id = ?, planned_start = ?, planned_end = ?,
				       note = ?, version = version + 1, updated_at = UTC_TIMESTAMP(3), updated_by = ?
				 WHERE id = ?
				""", name, sortOrder, responsibleUserId, plannedStart, plannedEnd, note, actor.userId(), stageId);

		Map<String, Object> after = dto(loadStage(stageId, false));
		touchProject((String) stage.get("project_id"));
		appendEvent(stageId, "UPDATE", oldStatus, oldStatus, note, null, actor.userId());
		auditWriter.record("STAGE", stageId, "UPDATE", before, after);
		eventWriter.emit("stage.updated", "STAGE", stageId, (String) stage.get("project_id"), stageId, actor.userId(),
				number(after.get("version")), "BASIC", oldStatus, oldStatus, List.of("name", "plannedStart",
						"plannedEnd", "responsibleUserId", "note"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> transition(String stageId, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> stage = loadStage(stageId, true);
		Map<String, Object> project = requireProject((String) stage.get("project_id"), true);
		if (!actor.userId().equals(project.get("owner_user_id"))) {
			requireAssigned(actor, stage);
		}
		requireEditableProject(project);

		rejectUnknownFields(body, Set.of("toStatus", "action", "note", "reason", "issueId"));
		StageStateMachine.Transition transition = StageStateMachine.resolve((String) stage.get("status"),
				body.get("toStatus"), body.get("action"));

		String note = nullableText(body.containsKey("note") ? body.get("note") : body.get("reason"), "note", 2000);
		if (StageStateMachine.BLOCKED.equals(transition.toStatus()) && !StringUtils.hasText(note)) {
			throw MoldApiException.validation("/body/note", "required", null, "标记受阻必须填写原因");
		}
		String issueId = blankToNull(body.get("issueId"));
		requireIssue(issueId, (String) stage.get("project_id"), stageId);

		String action = transition.action();
		Map<String, Object> before = dto(stage);
		jdbcTemplate.update("""
				UPDATE project_stages
				   SET status = ?,
				       actual_start = CASE WHEN ? = 'START' THEN UTC_DATE() ELSE actual_start END,
				       actual_end   = CASE WHEN ? = 'COMPLETE' THEN UTC_DATE() ELSE actual_end END,
				       version = version + 1, updated_at = UTC_TIMESTAMP(3), updated_by = ?
				 WHERE id = ?
				""", transition.toStatus(), action, action, actor.userId(), stageId);

		Map<String, Object> after = dto(loadStage(stageId, false));
		touchProject((String) stage.get("project_id"));
		appendEvent(stageId, action, transition.fromStatus(), transition.toStatus(), note, issueId, actor.userId());
		auditWriter.record("STAGE", stageId, "TRANSITION", before, after);
		eventWriter.emit("stage.transitioned", "STAGE", stageId, (String) stage.get("project_id"), stageId,
				actor.userId(), number(after.get("version")), "BASIC", transition.toStatus(),
				transition.fromStatus(), List.of("status"));
		return after;
	}

	public Map<String, Object> events(String stageId, PageQuery page) {
		loadStage(stageId, false);
		Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stage_events WHERE stage_id = ?", Long.class,
				stageId);
		String direction = "desc".equals(page.order()) ? "DESC" : "ASC";
		String sql = """
				SELECT e.* FROM stage_events e
				WHERE e.stage_id = ?
				ORDER BY """ + " " + EVENT_SORT_COLUMNS.get(page.sort()) + " " + direction + ", e.id " + direction
				+ " LIMIT ? OFFSET ?";
		List<Object> args = new ArrayList<>();
		args.add(stageId);
		args.add(page.pageSize());
		args.add((long) (page.pageNum() - 1) * page.pageSize());
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray())
			.stream()
			.map(this::eventDto)
			.toList();
		return MoldResponses.page(rows, total == null ? 0L : total, page.pageNum(), page.pageSize(), dataVersion());
	}

	public Map<String, Object> myStages(PageQuery page, String status) {
		MoldActor actor = actorResolver.current();
		List<String> conditions = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		conditions.add("s.deleted_at IS NULL");
		conditions.add("(s.responsible_user_id = ? OR EXISTS (SELECT 1 FROM stage_members m "
				+ "WHERE m.stage_id = s.id AND m.user_id = ?))");
		args.add(actor.userId());
		args.add(actor.userId());
		if (StringUtils.hasText(status)) {
			if (!StageStateMachine.STATUSES.contains(status)) {
				throw MoldApiException.validation("/query/status", "enum",
						Map.of("allowedValues", List.copyOf(StageStateMachine.STATUSES)), "未知阶段状态");
			}
			conditions.add("s.status = ?");
			args.add(status);
		}
		String where = " WHERE " + String.join(" AND ", conditions);
		Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM project_stages s" + where, Long.class,
				args.toArray());
		String direction = "desc".equals(page.order()) ? "DESC" : "ASC";
		String sql = STAGE_SELECT + where + " ORDER BY " + SORT_COLUMNS.get(page.sort()) + " " + direction + ", s.id "
				+ direction + " LIMIT ? OFFSET ?";
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(page.pageSize());
		pageArgs.add((long) (page.pageNum() - 1) * page.pageSize());
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, pageArgs.toArray())
			.stream()
			.map(this::dto)
			.toList();
		return MoldResponses.page(rows, total == null ? 0L : total, page.pageNum(), page.pageSize(), dataVersion());
	}

	/**
	 * 事件排序白名单：id / action / occurredAt（默认 id ASC，兼容旧游标语义）。
	 */
	public static Set<String> eventSortable() {
		return EVENT_SORTABLE;
	}

	private Map<String, Object> requireProject(String projectId, boolean forUpdate) {
		String sql = "SELECT * FROM projects WHERE id = ? AND deleted_at IS NULL" + (forUpdate ? " FOR UPDATE" : "");
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, projectId);
		if (rows.isEmpty()) {
			throw new MoldApiException(404, "NOT_FOUND", "项目不存在或已删除");
		}
		return rows.get(0);
	}

	private Map<String, Object> loadStage(String stageId, boolean forUpdate) {
		String sql = STAGE_SELECT + " WHERE s.id = ? AND s.deleted_at IS NULL" + (forUpdate ? " FOR UPDATE" : "");
		try {
			return jdbcTemplate.queryForMap(sql, stageId);
		}
		catch (EmptyResultDataAccessException ex) {
			throw new MoldApiException(404, "NOT_FOUND", "阶段不存在或已删除");
		}
	}

	private void requireOwner(MoldActor actor, Map<String, Object> project) {
		if (!actor.userId().equals(project.get("owner_user_id"))) {
			throw new MoldApiException(403, "NOT_PROJECT_OWNER", "仅该项目负责人可维护该项目");
		}
	}

	private void requireEditableProject(Map<String, Object> project) {
		String status = (String) project.get("status");
		if (!"ACTIVE".equals(status) && !"ON_HOLD".equals(status)) {
			// P2-09：409 必须给出可判定的上下文，不能是 details=null。
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("conflictKind", "PROJECT_STATE");
			details.put("resourceType", "PROJECT");
			details.put("resourceId", project.get("id"));
			details.put("currentStatus", status);
			details.put("allowedActions", List.of());
			details.put("allowedProjectStatuses", List.of("ACTIVE", "ON_HOLD"));
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "项目已关闭，不可维护阶段", details);
		}
	}

	private void requireAssigned(MoldActor actor, Map<String, Object> stage) {
		boolean responsible = actor.userId().equals(stage.get("responsible_user_id"));
		if (responsible) {
			return;
		}
		Long members = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM stage_members WHERE stage_id = ? AND user_id = ?", Long.class,
				stage.get("id"), actor.userId());
		if (members == null || members == 0) {
			throw new MoldApiException(403, "STAGE_NOT_ASSIGNED", "仅阶段责任人或阶段成员可维护该阶段");
		}
	}

	private void requireActiveUser(String userId) {
		if (userId == null) {
			return;
		}
		Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE id = ? AND status = 'ACTIVE'",
				Long.class, userId);
		if (count == null || count == 0) {
			throw MoldApiException.validation("/body/responsibleUserId", "reference", null, "责任人不存在或已停用");
		}
	}

	private void requireIssue(String issueId, String projectId, String stageId) {
		if (issueId == null) {
			return;
		}
		List<Map<String, Object>> rows = jdbcTemplate
			.queryForList("SELECT id, project_id, stage_id FROM issues WHERE id = ? AND deleted_at IS NULL", issueId);
		if (rows.isEmpty()) {
			throw MoldApiException.validation("/body/issueId", "reference", null, "异常不存在");
		}
		Map<String, Object> issue = rows.get(0);
		if (!projectId.equals(issue.get("project_id"))
				|| (issue.get("stage_id") != null && !stageId.equals(issue.get("stage_id")))) {
			throw MoldApiException.validation("/body/issueId", "reference", null, "异常归属与阶段不一致");
		}
	}

	private void appendEvent(String stageId, String action, String fromStatus, String toStatus, String note,
			String issueId, String actorId) {
		jdbcTemplate.update("""
				INSERT INTO stage_events
					(stage_id, action, from_status, to_status, note, issue_id, actor_id, request_id)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""", stageId, action, fromStatus, toStatus, note, issueId, actorId, requestId());
	}

	private void touchProject(String projectId) {
		jdbcTemplate.update("UPDATE projects SET updated_at = UTC_TIMESTAMP(3) WHERE id = ?", projectId);
	}

	private String requestId() {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
			HttpServletRequest request = attributes.getRequest();
			Object value = request.getAttribute(MoldRequestIdFilter.ATTRIBUTE);
			return value == null ? null : value.toString();
		}
		return null;
	}

	private Map<String, Object> dto(Map<String, Object> row) {
		String status = (String) row.get("status");
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", row.get("id"));
		dto.put("projectId", row.get("project_id"));
		dto.put("name", row.get("name"));
		dto.put("sortOrder", number(row.get("sort_order")));
		dto.put("responsibleUserId", row.get("responsible_user_id"));
		dto.put("plannedStart", MoldTime.iso(row.get("planned_start")));
		dto.put("plannedEnd", MoldTime.iso(row.get("planned_end")));
		dto.put("actualStart", MoldTime.iso(row.get("actual_start")));
		dto.put("actualEnd", MoldTime.iso(row.get("actual_end")));
		dto.put("status", status);
		dto.put("note", row.get("note"));
		dto.put("version", number(row.get("version")));
		dto.put("updatedAt", MoldTime.iso(row.get("updated_at")));
		dto.put("allowedActions", StageStateMachine.allowedActions(status));
		return dto;
	}

	private Map<String, Object> eventDto(Map<String, Object> row) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", row.get("public_id"));
		dto.put("stageId", row.get("stage_id"));
		dto.put("action", row.get("action"));
		if (row.get("from_status") != null) {
			dto.put("previousStatus", row.get("from_status"));
		}
		if (row.get("to_status") != null) {
			dto.put("status", row.get("to_status"));
		}
		dto.put("reason", row.get("note"));
		dto.put("issueId", row.get("issue_id"));
		dto.put("actorId", row.get("actor_id"));
		dto.put("occurredAt", MoldTime.iso(row.get("created_at")));
		return dto;
	}

	private String dataVersion() {
		Long version = jdbcTemplate.queryForObject("SELECT version FROM event_clock WHERE singleton = 1", Long.class);
		return String.valueOf(version == null ? 0L : version);
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

	private void rejectUnknownFields(Map<String, Object> body, Set<String> allowed) {
		if (body == null) {
			return;
		}
		for (String key : body.keySet()) {
			if (!allowed.contains(key)) {
				throw MoldApiException.validation("/body/" + key, "additionalProperties", null,
						"不允许通过该接口修改字段 " + key);
			}
		}
	}

	private static void requireDateOrder(LocalDate plannedStart, LocalDate plannedEnd) {
		if (plannedStart != null && plannedEnd != null && plannedEnd.isBefore(plannedStart)) {
			throw MoldApiException.validation("/body/plannedEnd", "format", null, "计划结束不能早于计划开始");
		}
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

	private static String nullableText(Object value, String field, int maxLength) {
		if (value == null) {
			return null;
		}
		String text = value.toString().trim();
		if (text.isEmpty()) {
			return null;
		}
		if (text.length() > maxLength) {
			throw MoldApiException.validation("/body/" + field, "maxLength", Map.of("limit", maxLength),
					field + " 长度不能超过 " + maxLength);
		}
		return text;
	}

	private static String blankToNull(Object value) {
		if (value == null || !StringUtils.hasText(value.toString())) {
			return null;
		}
		return value.toString().trim();
	}

	private static Integer integer(Object value, String field, int defaultValue) {
		if (value == null) {
			return defaultValue;
		}
		try {
			int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString().trim());
			if (parsed < 0) {
				throw MoldApiException.validation("/body/" + field, "minimum", Map.of("limit", 0),
						field + " 不能为负数");
			}
			return parsed;
		}
		catch (NumberFormatException ex) {
			throw MoldApiException.validation("/body/" + field, "type", null, field + " 必须是整数");
		}
	}

	private static LocalDate date(Object value, String field) {
		if (value == null || !StringUtils.hasText(value.toString())) {
			return null;
		}
		try {
			return LocalDate.parse(value.toString().trim());
		}
		catch (DateTimeParseException ex) {
			throw MoldApiException.validation("/body/" + field, "format", Map.of("format", "YYYY-MM-DD"),
					field + " 必须是 YYYY-MM-DD");
		}
	}

	private static LocalDate toDate(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof LocalDate localDate) {
			return localDate;
		}
		if (value instanceof java.sql.Date sqlDate) {
			return sqlDate.toLocalDate();
		}
		return LocalDate.parse(value.toString());
	}

	private static int number(Object value) {
		if (value == null) {
			return 0;
		}
		return ((Number) value).intValue();
	}

}
