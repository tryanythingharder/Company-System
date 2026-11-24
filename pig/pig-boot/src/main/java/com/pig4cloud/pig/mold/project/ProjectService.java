/*
 * 模具加工管理系统 M2 后端切片
 * G3 项目：列表（含 q 搜索）/ 详情 / 新建 / 更新 / 变更负责人 / 成员 / 全量导出 / 完工 / 重开。
 *
 * 可见性取舍：旧系统 14310 实测 6 个角色（含 PM / VIEWER）调用 /projects 均返回全部项目，
 * feature-parity 也未要求按参与关系过滤列表，因此列表与导出对全部持 mold:project:list 的角色开放；
 * 写操作按对象级范围限制为该项目 owner_user_id 本人（变更负责人仅 OWNER，沿用旧系统 user:manage）。
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectService {

	public static final Set<String> SORTABLE = Set.of("code", "name", "businessType", "status", "lastUpdatedAt");

	public static final Set<String> BUSINESS_TYPES = Set.of("MOLD_SET", "PART_PROCESS");

	/** PATCH 项目允许的字段；空对象不算有效修改（422）。 */
	public static final Set<String> PATCH_FIELDS = Set.of("name", "dueDate", "customerId", "note");

	private static final Map<String, String> SORT_COLUMNS = Map.of("code", "p.code", "name", "p.name",
			"businessType", "p.business_type", "status", "p.status", "lastUpdatedAt", "p.updated_at");

	private static final List<String> CSV_HEADER = List.of("编号", "名称", "类型", "状态", "负责人", "计划交期", "阶段总数",
			"已完成阶段", "未解决异常", "最近更新");

	private static final String SELECT_FROM = """
			SELECT p.*, u.display_name AS owner_display_name,
			       COALESCE(sc.total, 0) AS stage_total,
			       COALESCE(sc.ns, 0)    AS stage_not_started,
			       COALESCE(sc.ip, 0)    AS stage_in_progress,
			       COALESCE(sc.bl, 0)    AS stage_blocked,
			       COALESCE(sc.co, 0)    AS stage_completed,
			       (SELECT COUNT(*) FROM issues i
			         WHERE i.project_id = p.id AND i.status = 'OPEN' AND i.deleted_at IS NULL) AS open_issue_count
			FROM projects p
			JOIN users u ON u.id = p.owner_user_id
			LEFT JOIN (
			    SELECT project_id,
			           COUNT(*) AS total,
			           SUM(status = 'NOT_STARTED') AS ns,
			           SUM(status = 'IN_PROGRESS') AS ip,
			           SUM(status = 'BLOCKED')     AS bl,
			           SUM(status = 'COMPLETED')   AS co
			    FROM project_stages
			    WHERE deleted_at IS NULL
			    GROUP BY project_id
			) sc ON sc.project_id = p.id
			""";

	private final JdbcTemplate jdbcTemplate;

	private final MoldActorResolver actorResolver;

	private final AuditWriter auditWriter;

	private final EventWriter eventWriter;

	public Map<String, Object> list(PageQuery page, String status, String businessType, String ownerUserId, String q) {
		Filter filter = filter(status, businessType, ownerUserId, q);

		Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM projects p" + filter.where(), Long.class,
				filter.args().toArray());
		long totalValue = total == null ? 0L : total;

		String column = SORT_COLUMNS.get(page.sort());
		String direction = "desc".equals(page.order()) ? "DESC" : "ASC";
		String sql = SELECT_FROM + filter.where() + " ORDER BY " + column + " " + direction + ", p.id " + direction
				+ " LIMIT ? OFFSET ?";

		List<Object> pageArgs = new ArrayList<>(filter.args());
		pageArgs.add(page.pageSize());
		pageArgs.add((long) (page.pageNum() - 1) * page.pageSize());

		List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, pageArgs.toArray()).stream().map(this::dto)
			.toList();
		return MoldResponses.page(rows, totalValue, page.pageNum(), page.pageSize(), dataVersion());
	}

	public Map<String, Object> get(String projectId) {
		Map<String, Object> row = loadRow(projectId, false);
		if (row == null) {
			throw new MoldApiException(404, "NOT_FOUND", "项目不存在或已删除");
		}
		return dto(row);
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> create(Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		rejectUnknownFields(body, Set.of("code", "name", "businessType", "ownerUserId", "dueDate", "customerId",
				"note"));

		String code = text(body.get("code"), "code", 64);
		String name = text(body.get("name"), "name", 200);
		String businessType = enumValue(body.get("businessType"), "businessType", BUSINESS_TYPES);
		String ownerUserId = text(body.get("ownerUserId"), "ownerUserId", 36);
		LocalDate dueDate = date(body.get("dueDate"), "dueDate");
		String customerId = optionalText(body.get("customerId"), "customerId", 36);
		String note = optionalText(body.get("note"), "note", 2000);

		requireActiveUser(ownerUserId, "ownerUserId");
		requireCustomer(customerId);

		Long existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM projects WHERE code = ?", Long.class, code);
		if (existing != null && existing > 0) {
			throw new MoldApiException(409, "PROJECT_CODE_DUPLICATE", "项目编号已存在", duplicateCodeDetails(code));
		}

		String projectId = UUID.randomUUID().toString();
		try {
			jdbcTemplate.update("""
					INSERT INTO projects
						(id, code, name, business_type, customer_id, owner_user_id, due_date, status, note,
						 created_by, created_at, updated_at, version)
					VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 1)
					""", projectId, code, name, businessType, customerId, ownerUserId, dueDate, note, actor.userId());
		}
		catch (DuplicateKeyException ex) {
			throw new MoldApiException(409, "PROJECT_CODE_DUPLICATE", "项目编号已存在", duplicateCodeDetails(code));
		}

		Map<String, Object> after = dto(loadRow(projectId, false));
		auditWriter.record("PROJECT", projectId, "CREATE", null, after);
		eventWriter.emit("project.created", "PROJECT", projectId, projectId, null, actor.userId(),
				number(after.get("version")), "BASIC", "ACTIVE", null,
				List.of("code", "name", "businessType", "ownerUserId", "dueDate", "customerId", "note"));
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> update(String projectId, Integer expectedVersion, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> beforeRow = loadRow(projectId, true);
		if (beforeRow == null) {
			throw new MoldApiException(404, "NOT_FOUND", "项目不存在或已删除");
		}
		if (!isManager(actor, beforeRow)) {
			throw new MoldApiException(403, "NOT_PROJECT_OWNER", "仅该项目负责人或老板可维护该项目");
		}
		requireEditable(beforeRow);

		int currentVersion = ((Number) beforeRow.get("version")).intValue();
		if (expectedVersion == null) {
			throw MoldApiException.validation("/header/If-Match", "required", null, "缺少 If-Match 版本号");
		}
		if (expectedVersion != currentVersion) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("current", currentVersion);
			throw new MoldApiException(409, "VERSION_CONFLICT", "版本冲突，请刷新后重试", details);
		}

		rejectUnknownFields(body, PATCH_FIELDS);
		requirePatchField(body);

		String name = body.containsKey("name") ? text(body.get("name"), "name", 200) : (String) beforeRow.get("name");
		String note = body.containsKey("note") ? optionalText(body.get("note"), "note", 2000)
				: (String) beforeRow.get("note");
		Object dueDate = body.containsKey("dueDate") ? date(body.get("dueDate"), "dueDate") : beforeRow.get("due_date");
		String customerId = body.containsKey("customerId") ? optionalText(body.get("customerId"), "customerId", 36)
				: (String) beforeRow.get("customer_id");
		requireCustomer(customerId);

		Map<String, Object> before = dto(beforeRow);

		jdbcTemplate.update("""
				UPDATE projects
				   SET name = ?, due_date = ?, customer_id = ?, note = ?,
				       version = version + 1, updated_at = UTC_TIMESTAMP(3), updated_by = ?
				 WHERE id = ?
				""", name, dueDate, customerId, note, actor.userId(), projectId);

		Map<String, Object> afterRow = loadRow(projectId, false);
		Map<String, Object> after = dto(afterRow);
		auditWriter.record("PROJECT", projectId, "UPDATE", before, after);
		return after;
	}

	/**
	 * 变更负责人（契约 6.5）：仅 OWNER 可操作，沿用旧系统 user:manage 语义；必须填写 reason。
	 */
	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> transferOwner(String projectId, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		if (!actor.roles().contains("OWNER")) {
			throw new MoldApiException(403, "FORBIDDEN", "仅 OWNER 可变更项目负责人");
		}
		Map<String, Object> beforeRow = loadRow(projectId, true);
		if (beforeRow == null) {
			throw new MoldApiException(404, "NOT_FOUND", "项目不存在或已删除");
		}
		rejectUnknownFields(body, Set.of("ownerUserId", "reason"));
		String ownerUserId = text(body.get("ownerUserId"), "ownerUserId", 36);
		String reason = text(body.get("reason"), "reason", 2000);
		requireActiveUser(ownerUserId, "ownerUserId");

		if (ownerUserId.equals(beforeRow.get("owner_user_id"))) {
			throw MoldApiException.validation("/body/ownerUserId", "same", null, "新负责人与当前负责人相同");
		}

		Map<String, Object> before = dto(beforeRow);
		before.put("transferReason", reason);

		jdbcTemplate.update("""
				UPDATE projects
				   SET owner_user_id = ?, version = version + 1,
				       updated_at = UTC_TIMESTAMP(3), updated_by = ?
				 WHERE id = ?
				""", ownerUserId, actor.userId(), projectId);

		Map<String, Object> after = dto(loadRow(projectId, false));
		auditWriter.record("PROJECT", projectId, "TRANSFER_OWNER", before, after);
		eventWriter.emit("project.owner.transferred", "PROJECT", projectId, projectId, null, actor.userId(),
				number(after.get("version")), "BASIC", null, null, List.of("ownerUserId"));
		return after;
	}

	public Map<String, Object> members(String projectId) {
		Map<String, Object> project = loadRow(projectId, false);
		if (project == null) {
			throw new MoldApiException(404, "NOT_FOUND", "项目不存在或已删除");
		}
		// 契约 6.5：GET 不限（登录 + mold:project:member 即可），写仍限项目负责人。
		return membersPayload(projectId);
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> setMembers(String projectId, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> project = loadRow(projectId, true);
		if (project == null) {
			throw new MoldApiException(404, "NOT_FOUND", "项目不存在或已删除");
		}
		requireProjectOwner(actor, project);
		rejectUnknownFields(body, Set.of("members"));
		if (!(body.get("members") instanceof List<?> rawMembers)) {
			throw MoldApiException.validation("/body/members", "type", null, "members 必须是数组");
		}

		List<Map<String, Object>> next = new ArrayList<>();
		Set<String> seen = new java.util.LinkedHashSet<>();
		for (Object item : rawMembers) {
			if (!(item instanceof Map<?, ?> raw)) {
				throw MoldApiException.validation("/body/members", "type", null, "成员项必须是对象");
			}
			Map<String, Object> member = new LinkedHashMap<>();
			for (Object key : raw.keySet()) {
				if (!Set.of("userId", "accessLevel").contains(key.toString())) {
					throw MoldApiException.validation("/body/members", "additionalProperties", null,
							"成员项不允许字段 " + key);
				}
			}
			String userId = text(raw.get("userId"), "members.userId", 36);
			String accessLevel = enumValue(raw.get("accessLevel") == null ? "VIEW" : raw.get("accessLevel"),
					"members.accessLevel", Set.of("VIEW", "EDIT"));
			if (!seen.add(userId)) {
				throw MoldApiException.validation("/body/members", "uniqueItems", null, "成员不能重复");
			}
			requireActiveUser(userId, "members.userId");
			member.put("userId", userId);
			member.put("accessLevel", accessLevel);
			next.add(member);
		}

		Map<String, Object> before = Map.of("rows", memberRows(projectId));
		jdbcTemplate.update("DELETE FROM project_members WHERE project_id = ?", projectId);
		for (Map<String, Object> member : next) {
			jdbcTemplate.update("""
					INSERT INTO project_members (project_id, user_id, access_level, added_by, added_at)
					VALUES (?, ?, ?, ?, UTC_TIMESTAMP(3))
					""", projectId, member.get("userId"), member.get("accessLevel"), actor.userId());
		}
		jdbcTemplate.update("UPDATE projects SET updated_at = UTC_TIMESTAMP(3) WHERE id = ?", projectId);

		Map<String, Object> after = membersPayload(projectId);
		auditWriter.record("PROJECT", projectId, "SET_MEMBERS", before, Map.of("rows", after.get("rows")));
		eventWriter.emit("project.members.updated", "PROJECT", projectId, projectId, null, actor.userId(),
				number(project.get("version")), "BASIC", null, null, List.of("members"));
		return after;
	}

	/**
	 * 全量导出（D1）：按当前筛选条件取全部行，不受 pageSize 100 上限影响；CSV 流式写出。
	 */
	public void export(String status, String businessType, String ownerUserId, String q, String sort, String order,
			OutputStream out) {
		Filter filter = filter(status, businessType, ownerUserId, q);
		String column = SORT_COLUMNS.get(sort);
		String direction = "desc".equals(order) ? "DESC" : "ASC";
		String sql = SELECT_FROM + filter.where() + " ORDER BY " + column + " " + direction + ", p.id " + direction;

		try {
			out.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
			writeLine(out, String.join(",", CSV_HEADER));
			jdbcTemplate.query(sql, rs -> {
				try {
					while (rs.next()) {
						writeLine(out, String.join(",", csv(rs.getString("code")), csv(rs.getString("name")),
								csv(rs.getString("business_type")), csv(rs.getString("status")),
								csv(rs.getString("owner_display_name")), csv(MoldTime.iso(rs.getDate("due_date"))),
								String.valueOf(rs.getLong("stage_total")), String.valueOf(rs.getLong("stage_completed")),
								String.valueOf(rs.getLong("open_issue_count")),
								csv(MoldTime.iso(rs.getTimestamp("updated_at")))));
					}
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
				return null;
			}, filter.args().toArray());
			out.flush();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private Filter filter(String status, String businessType, String ownerUserId, String q) {
		List<String> conditions = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		conditions.add("p.deleted_at IS NULL");
		if (StringUtils.hasText(status)) {
			conditions.add("p.status = ?");
			args.add(status.trim());
		}
		if (StringUtils.hasText(businessType)) {
			String normalized = businessType.trim();
			if (!BUSINESS_TYPES.contains(normalized)) {
				throw MoldApiException.validation("/query/businessType", "enum",
						Map.of("allowedValues", List.copyOf(BUSINESS_TYPES)), "businessType 取值无效");
			}
			conditions.add("p.business_type = ?");
			args.add(normalized);
		}
		if (StringUtils.hasText(ownerUserId)) {
			conditions.add("p.owner_user_id = ?");
			args.add(ownerUserId.trim());
		}
		if (StringUtils.hasText(q)) {
			String like = "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
			conditions.add("(p.code LIKE ? ESCAPE '!' OR p.name LIKE ? ESCAPE '!')");
			args.add(like);
			args.add(like);
		}
		return new Filter(" WHERE " + String.join(" AND ", conditions), args);
	}

	private record Filter(String where, List<Object> args) {
	}

	/**
	 * D1 确认整单完工（契约 6.5）：仅该项目负责人；必须带 If-Match 与完工说明。
	 * 门槛顺序：无有效阶段 → 有未完成阶段 → 有未解决异常，均 409 且 details 说明缺什么。
	 */
	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> complete(String projectId, Integer expectedVersion, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> beforeRow = loadRow(projectId, true);
		if (beforeRow == null) {
			throw new MoldApiException(404, "NOT_FOUND", "项目不存在或已删除");
		}
		if (!isManager(actor, beforeRow)) {
			throw new MoldApiException(403, "NOT_PROJECT_OWNER", "仅该项目负责人或老板可确认整单完工");
		}
		String currentStatus = (String) beforeRow.get("status");
		if (!"ACTIVE".equals(currentStatus) && !"ON_HOLD".equals(currentStatus)) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("conflictKind", "PROJECT_STATE");
			details.put("resourceType", "PROJECT");
			details.put("resourceId", projectId);
			details.put("currentStatus", currentStatus);
			details.put("requestedAction", "COMPLETE");
			details.put("version", number(beforeRow.get("version")));
			details.put("projectStatus", currentStatus);
			details.put("allowedActions", List.of());
			details.put("terminal", true);
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "项目已关闭，不可确认完工", details);
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

		rejectUnknownFields(body, Set.of("completionNote"));
		String completionNote = text(body.get("completionNote"), "completionNote", 2000);

		List<CompletionRules.StageRef> stages = jdbcTemplate
			.queryForList("""
					SELECT id, name, status FROM project_stages
					WHERE project_id = ? AND deleted_at IS NULL ORDER BY sort_order, id
					""", projectId)
			.stream()
			.map(row -> new CompletionRules.StageRef((String) row.get("id"), (String) row.get("name"),
					(String) row.get("status")))
			.toList();
		List<CompletionRules.IssueRef> openIssues = jdbcTemplate
			.queryForList("""
					SELECT id, title, stage_id FROM issues
					WHERE project_id = ? AND status = 'OPEN' AND deleted_at IS NULL ORDER BY created_at, id
					""", projectId)
			.stream()
			.map(row -> new CompletionRules.IssueRef((String) row.get("id"), (String) row.get("title"),
					(String) row.get("stage_id")))
			.toList();
		CompletionRules.validate(stages, openIssues);

		Map<String, Object> before = dto(beforeRow);
		jdbcTemplate.update("""
				UPDATE projects
				   SET status = 'COMPLETED', completed_at = UTC_TIMESTAMP(3), completed_by = ?,
				       completion_note = ?, version = version + 1, updated_at = UTC_TIMESTAMP(3), updated_by = ?
				 WHERE id = ?
				""", actor.userId(), completionNote, actor.userId(), projectId);

		Map<String, Object> afterRow = loadRow(projectId, false);
		Map<String, Object> after = dto(afterRow);
		auditWriter.record("PROJECT", projectId, "COMPLETE", before, after);
		eventWriter.emit("project.completed", "PROJECT", projectId, projectId, null, actor.userId(),
				number(after.get("version")), "BASIC", "COMPLETED", currentStatus,
				List.of("status", "completedAt", "completedBy", "completionNote"));
		return after;
	}

	/**
	 * 项目状态变更（2026-11 拍板）：暂停 ACTIVE→ON_HOLD、恢复 ON_HOLD→ACTIVE、取消 ACTIVE/ON_HOLD→CANCELLED。
	 * 仅该项目负责人或老板（OWNER）；暂停与取消必须填写原因（记入审计）；取消为终态，重开暂不开放。
	 */
	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> changeStatus(String projectId, String action, Integer expectedVersion,
			Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> beforeRow = loadRow(projectId, true);
		if (beforeRow == null) {
			throw new MoldApiException(404, "NOT_FOUND", "项目不存在或已删除");
		}
		if (!isManager(actor, beforeRow)) {
			throw new MoldApiException(403, "NOT_PROJECT_OWNER", "仅该项目负责人或老板可变更项目状态");
		}
		String current = (String) beforeRow.get("status");
		String target;
		List<String> from;
		switch (action) {
			case "HOLD" -> {
				target = "ON_HOLD";
				from = List.of("ACTIVE");
			}
			case "RESUME" -> {
				target = "ACTIVE";
				from = List.of("ON_HOLD");
			}
			case "CANCEL" -> {
				target = "CANCELLED";
				from = List.of("ACTIVE", "ON_HOLD");
			}
			default -> throw new IllegalArgumentException("unknown action " + action);
		}
		if (!from.contains(current)) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("conflictKind", "PROJECT_STATE");
			details.put("resourceType", "PROJECT");
			details.put("resourceId", projectId);
			details.put("currentStatus", current);
			details.put("requestedAction", action);
			details.put("allowedFromStatuses", from);
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "当前项目状态不允许该操作", details);
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
		Map<String, Object> safeBody = body == null ? Map.of() : body;
		rejectUnknownFields(safeBody, Set.of("reason"));
		String reason = "RESUME".equals(action) ? optionalText(safeBody.get("reason"), "reason", 2000)
				: text(safeBody.get("reason"), "reason", 2000);

		Map<String, Object> before = dto(beforeRow);
		jdbcTemplate.update("""
				UPDATE projects
				   SET status = ?, version = version + 1, updated_at = UTC_TIMESTAMP(3), updated_by = ?
				 WHERE id = ?
				""", target, actor.userId(), projectId);
		Map<String, Object> after = dto(loadRow(projectId, false));
		Map<String, Object> auditAfter = new LinkedHashMap<>(after);
		auditAfter.put("statusReason", reason);
		auditWriter.record("PROJECT", projectId, action, before, auditAfter);
		eventWriter.emit("project.status.changed", "PROJECT", projectId, projectId, null, actor.userId(),
				number(after.get("version")), "BASIC", target, current, List.of("status"));
		return after;
	}

	/**
	 * 项目重开属旧系统 closedOperations，业务规则未确认：一律 409 POLICY_NOT_CONFIRMED，不写任何数据。
	 */
	public void reopen(String projectId) {
		throw new MoldApiException(409, "POLICY_NOT_CONFIRMED", "项目重开规则尚未确认，本地版本关闭该操作");
	}

	/** 项目负责人本人，或老板（OWNER，2026-11 拍板：可直接操作任意项目）。 */
	private static boolean isManager(MoldActor actor, Map<String, Object> project) {
		return actor.roles().contains("OWNER") || actor.userId().equals(project.get("owner_user_id"));
	}

	private Map<String, Object> loadRow(String projectId, boolean forUpdate) {
		String sql = SELECT_FROM + " WHERE p.id = ? AND p.deleted_at IS NULL" + (forUpdate ? " FOR UPDATE" : "");
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, projectId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private void requireProjectOwner(MoldActor actor, Map<String, Object> project) {
		if (!isManager(actor, project)) {
			throw new MoldApiException(403, "NOT_PROJECT_OWNER", "仅该项目负责人或老板可维护成员");
		}
	}

	private void requireEditable(Map<String, Object> project) {
		String status = (String) project.get("status");
		if (!"ACTIVE".equals(status) && !"ON_HOLD".equals(status)) {
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "项目已关闭，不可编辑");
		}
	}

	private void requireActiveUser(String userId, String field) {
		if (userId == null) {
			throw MoldApiException.validation("/body/" + field, "required", null, field + " 不能为空");
		}
		Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE id = ? AND status = 'ACTIVE'",
				Long.class, userId);
		if (count == null || count == 0) {
			throw MoldApiException.validation("/body/" + field, "reference", null, "用户不存在或已停用");
		}
	}

	private void requireCustomer(String customerId) {
		if (customerId == null) {
			return;
		}
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM counterparties WHERE id = ? AND type = 'CUSTOMER' AND status = 'ACTIVE'",
				Long.class, customerId);
		if (count == null || count == 0) {
			throw MoldApiException.validation("/body/customerId", "reference", null, "客户不存在或已停用");
		}
	}

	private Map<String, Object> membersPayload(String projectId) {
		List<Map<String, Object>> rows = memberRows(projectId);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("rows", rows);
		data.put("total", rows.size());
		data.put("asOf", MoldTime.nowIso());
		return data;
	}

	private List<Map<String, Object>> memberRows(String projectId) {
		return jdbcTemplate.queryForList("""
				SELECT pm.user_id, u.display_name, pm.access_level, pm.added_by, pm.added_at
				FROM project_members pm
				JOIN users u ON u.id = pm.user_id
				WHERE pm.project_id = ?
				ORDER BY u.display_name, pm.user_id
				""", projectId).stream().map(row -> {
					Map<String, Object> dto = new LinkedHashMap<>();
					dto.put("userId", row.get("user_id"));
					dto.put("displayName", row.get("display_name"));
					dto.put("accessLevel", row.get("access_level"));
					dto.put("addedBy", row.get("added_by"));
					dto.put("addedAt", MoldTime.iso(row.get("added_at")));
					return dto;
				}).toList();
	}

	/**
	 * 阶段计数（列表行与详情共用）；blocked 同时以 blockedStageCount 平铺输出，
	 * 供前端第 4 张统计卡直接使用。
	 */
	static Map<String, Object> stageCounts(Map<String, Object> row) {
		Map<String, Object> stageCounts = new LinkedHashMap<>();
		stageCounts.put("total", number(row.get("stage_total")));
		stageCounts.put("notStarted", number(row.get("stage_not_started")));
		stageCounts.put("inProgress", number(row.get("stage_in_progress")));
		stageCounts.put("blocked", number(row.get("stage_blocked")));
		stageCounts.put("completed", number(row.get("stage_completed")));
		return stageCounts;
	}

	Map<String, Object> dto(Map<String, Object> row) {
		Map<String, Object> owner = new LinkedHashMap<>();
		owner.put("id", row.get("owner_user_id"));
		owner.put("displayName", row.get("owner_display_name"));

		Map<String, Object> stageCounts = stageCounts(row);

		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", row.get("id"));
		dto.put("code", row.get("code"));
		dto.put("name", row.get("name"));
		dto.put("businessType", row.get("business_type"));
		dto.put("status", row.get("status"));
		dto.put("owner", owner);
		dto.put("ownerUserId", row.get("owner_user_id"));
		dto.put("customerId", row.get("customer_id"));
		dto.put("dueDate", MoldTime.iso(row.get("due_date")));
		dto.put("stageCounts", stageCounts);
		dto.put("blockedStageCount", stageCounts.get("blocked"));
		dto.put("openIssueCount", number(row.get("open_issue_count")));
		dto.put("lastUpdatedAt", MoldTime.iso(row.get("updated_at")));
		dto.put("version", number(row.get("version")));
		dto.put("completedAt", MoldTime.iso(row.get("completed_at")));
		dto.put("completedBy", row.get("completed_by"));
		dto.put("completionNote", row.get("completion_note"));
		dto.put("reopenedAt", MoldTime.iso(row.get("reopened_at")));
		dto.put("note", row.get("note"));
		return dto;
	}

	private String dataVersion() {
		Long version = jdbcTemplate.queryForObject("SELECT version FROM event_clock WHERE singleton = 1", Long.class);
		return String.valueOf(version == null ? 0L : version);
	}

	private static void writeLine(OutputStream out, String line) throws IOException {
		out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
	}

	static Map<String, Object> duplicateCodeDetails(String code) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("field", "code");
		details.put("value", code);
		return details;
	}

	static String csv(String value) {
		if (value == null) {
			return "";
		}
		if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
			return "\"" + value.replace("\"", "\"\"") + "\"";
		}
		return value;
	}

	private void rejectUnknownFields(Map<String, Object> body, Set<String> allowed) {
		for (String key : body.keySet()) {
			if (!allowed.contains(key)) {
				throw MoldApiException.validation("/body/" + key, "additionalProperties", null,
						"不允许通过该接口修改字段 " + key);
			}
		}
	}

	/**
	 * {} 空对象不构成有效修改：直接 422，避免无意义地 version+1 并写一条 UPDATE 审计。
	 * 与「空请求体 422」口径一致。
	 */
	static void requirePatchField(Map<String, Object> body) {
		if (body == null || body.keySet().stream().noneMatch(PATCH_FIELDS::contains)) {
			throw MoldApiException.validation("/body", "required", null,
					"空对象不构成有效修改，至少提供一个字段：name、dueDate、customerId、note");
		}
	}

	private static int number(Object value) {
		if (value == null) {
			return 0;
		}
		return ((Number) value).intValue();
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

	private static String enumValue(Object value, String field, Set<String> allowed) {
		String text = value == null ? null : value.toString().trim();
		if (text == null || !allowed.contains(text)) {
			throw MoldApiException.validation("/body/" + field, "enum", Map.of("allowedValues", List.copyOf(allowed)),
					field + " 取值无效");
		}
		return text;
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

}
