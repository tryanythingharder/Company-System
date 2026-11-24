/*
 * 2026-11 决策单测：项目暂停 / 恢复 / 取消（ProjectService.changeStatus）。
 *
 * 用真实的 ProjectService，只替换 JdbcTemplate / 身份 / 审计 / 事件四个协作者，不连数据库。
 * 覆盖：合法流转、状态不允许、必须填原因、版本冲突、非负责人拒绝、老板放行、审计里保留原因。
 * 真实部署栈上的 200/403/409/422 另由 deploy/e2e/decisions_flow.py 验证。
 */
package com.pig4cloud.pig.mold.project;

import com.pig4cloud.pig.mold.audit.AuditWriter;
import com.pig4cloud.pig.mold.event.EventWriter;
import com.pig4cloud.pig.mold.security.MoldActor;
import com.pig4cloud.pig.mold.security.MoldActorResolver;
import com.pig4cloud.pig.mold.web.MoldApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectStatusChangeTest {

	private static final String PM = "11111111-1111-1111-1111-111111111111";

	private static final String BOSS = "22222222-2222-2222-2222-222222222222";

	private static final String PROG = "33333333-3333-3333-3333-333333333333";

	/** 内存里的一行项目；UPDATE projects SET status 会改它。 */
	static class FakeJdbc extends JdbcTemplate {

		final Map<String, Object> project = new LinkedHashMap<>();

		final List<String> updates = new ArrayList<>();

		FakeJdbc(String status) {
			project.put("id", "p-1");
			project.put("code", "P-1");
			project.put("name", "测试项目");
			project.put("business_type", "MOLD_SET");
			project.put("status", status);
			project.put("owner_user_id", PM);
			project.put("owner_display_name", "项目经理");
			project.put("version", 3);
		}

		@Override
		public List<Map<String, Object>> queryForList(String sql, Object... args) {
			return List.of(new LinkedHashMap<>(project));
		}

		@Override
		public int update(String sql, Object... args) {
			updates.add(sql);
			if (sql.contains("UPDATE projects") && sql.contains("SET status = ?")) {
				project.put("status", args[0]);
				project.put("version", ((Number) project.get("version")).intValue() + 1);
			}
			return 1;
		}

	}

	static class FixedActor extends MoldActorResolver {

		private final MoldActor actor;

		FixedActor(String userId, String role) {
			super(null);
			this.actor = new MoldActor(userId, 1L, "u", "用户", "ACTIVE", 1, false, List.of(role));
		}

		@Override
		public MoldActor current() {
			return actor;
		}

	}

	static class RecordingAudit extends AuditWriter {

		final List<Object[]> records = new ArrayList<>();

		RecordingAudit() {
			super(null, null, null);
		}

		@Override
		public void record(String entityType, String entityId, String action, Map<String, Object> before,
				Map<String, Object> after) {
			records.add(new Object[] { entityType, entityId, action, before, after });
		}

	}

	static class RecordingEvents extends EventWriter {

		final List<String> types = new ArrayList<>();

		RecordingEvents() {
			super(null, null);
		}

		@Override
		public void emit(String eventType, String resourceType, String resourceId, String projectId, String stageId,
				String actorId, Integer resourceVersion, String visibility, String status, String previousStatus,
				List<String> changedFields) {
			types.add(eventType + ":" + previousStatus + ">" + status);
		}

	}

	private final RecordingAudit audit = new RecordingAudit();

	private final RecordingEvents events = new RecordingEvents();

	private ProjectService service(FakeJdbc jdbc, String userId, String role) {
		return new ProjectService(jdbc, new FixedActor(userId, role), audit, events);
	}

	private static Map<String, Object> reason(String text) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("reason", text);
		return body;
	}

	@Test
	void ownerHoldsActiveProjectAndReasonIsAudited() {
		FakeJdbc jdbc = new FakeJdbc("ACTIVE");

		Map<String, Object> after = service(jdbc, PM, "PM").changeStatus("p-1", "HOLD", 3, reason("客户暂缓"));

		assertEquals("ON_HOLD", after.get("status"));
		assertEquals(4, after.get("version"));
		assertEquals(1, audit.records.size());
		assertEquals("HOLD", audit.records.get(0)[2]);
		@SuppressWarnings("unchecked")
		Map<String, Object> auditAfter = (Map<String, Object>) audit.records.get(0)[4];
		assertEquals("客户暂缓", auditAfter.get("statusReason"));
		assertEquals(List.of("project.status.changed:ACTIVE>ON_HOLD"), events.types);
	}

	@Test
	void resumeAllowsEmptyReason() {
		FakeJdbc jdbc = new FakeJdbc("ON_HOLD");

		Map<String, Object> after = service(jdbc, PM, "PM").changeStatus("p-1", "RESUME", 3, null);

		assertEquals("ACTIVE", after.get("status"));
		@SuppressWarnings("unchecked")
		Map<String, Object> auditAfter = (Map<String, Object>) audit.records.get(0)[4];
		assertNull(auditAfter.get("statusReason"));
	}

	@Test
	void cancelIsAllowedFromActiveAndOnHold() {
		for (String from : List.of("ACTIVE", "ON_HOLD")) {
			FakeJdbc jdbc = new FakeJdbc(from);
			Map<String, Object> after = service(jdbc, PM, "PM").changeStatus("p-1", "CANCEL", 3, reason("客户撤单"));
			assertEquals("CANCELLED", after.get("status"), "from " + from);
		}
	}

	@Test
	void holdAndCancelRequireReason() {
		for (String action : List.of("HOLD", "CANCEL")) {
			FakeJdbc jdbc = new FakeJdbc("ACTIVE");
			MoldApiException ex = assertThrows(MoldApiException.class,
					() -> service(jdbc, PM, "PM").changeStatus("p-1", action, 3, reason("  ")));
			assertEquals(422, ex.getStatus(), action);
			assertTrue(jdbc.updates.isEmpty(), "缺原因不能写库：" + action);
		}
	}

	@Test
	void illegalTransitionsAreRejectedWithoutWriting() {
		String[][] cases = { { "ACTIVE", "RESUME" }, { "ON_HOLD", "HOLD" }, { "CANCELLED", "RESUME" },
				{ "CANCELLED", "CANCEL" }, { "CANCELLED", "HOLD" }, { "COMPLETED", "CANCEL" } };
		for (String[] c : cases) {
			FakeJdbc jdbc = new FakeJdbc(c[0]);
			MoldApiException ex = assertThrows(MoldApiException.class,
					() -> service(jdbc, PM, "PM").changeStatus("p-1", c[1], 3, reason("x")));
			assertEquals(409, ex.getStatus(), c[0] + " " + c[1]);
			assertEquals("INVALID_STATE_TRANSITION", ex.getErrorCode(), c[0] + " " + c[1]);
			assertTrue(jdbc.updates.isEmpty(), "非法流转不能写库：" + c[0] + " " + c[1]);
		}
	}

	@Test
	void staleVersionIsConflict() {
		FakeJdbc jdbc = new FakeJdbc("ACTIVE");

		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> service(jdbc, PM, "PM").changeStatus("p-1", "HOLD", 2, reason("x")));

		assertEquals(409, ex.getStatus());
		assertEquals("VERSION_CONFLICT", ex.getErrorCode());
		assertTrue(jdbc.updates.isEmpty());
	}

	@Test
	void missingIfMatchIsValidationError() {
		FakeJdbc jdbc = new FakeJdbc("ACTIVE");

		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> service(jdbc, PM, "PM").changeStatus("p-1", "HOLD", null, reason("x")));

		assertEquals(422, ex.getStatus());
	}

	@Test
	void nonOwnerIsForbidden() {
		FakeJdbc jdbc = new FakeJdbc("ACTIVE");

		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> service(jdbc, PROG, "PROGRAMMER").changeStatus("p-1", "HOLD", 3, reason("x")));

		assertEquals(403, ex.getStatus());
		assertEquals("NOT_PROJECT_OWNER", ex.getErrorCode());
		assertTrue(jdbc.updates.isEmpty());
	}

	@Test
	void bossCanChangeStatusOfOthersProject() {
		FakeJdbc jdbc = new FakeJdbc("ACTIVE");

		Map<String, Object> after = service(jdbc, BOSS, "OWNER").changeStatus("p-1", "HOLD", 3, reason("老板暂停"));

		assertEquals("ON_HOLD", after.get("status"));
	}

	@Test
	void unknownFieldsAreRejected() {
		FakeJdbc jdbc = new FakeJdbc("ACTIVE");
		Map<String, Object> body = reason("x");
		body.put("status", "COMPLETED");

		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> service(jdbc, PM, "PM").changeStatus("p-1", "HOLD", 3, body));

		assertEquals(422, ex.getStatus());
		assertTrue(jdbc.updates.isEmpty());
	}

}
