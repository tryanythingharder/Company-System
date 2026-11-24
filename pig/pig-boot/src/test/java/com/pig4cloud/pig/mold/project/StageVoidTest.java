/*
 * 2026-11 决策单测：作废未开工阶段（StageService.voidStage）。
 *
 * 用真实的 StageService，只替换 JdbcTemplate / 身份 / 审计 / 事件四个协作者，不连数据库。
 * 覆盖：仅未开工可作废、必须填原因、有未解决异常不可作废、版本冲突、非负责人拒绝、老板放行、
 * 项目已关闭不可作废；成功时写软删 + 阶段事件 + 审计（含原因），返回 status=VOIDED。
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageVoidTest {

	private static final String PM = "11111111-1111-1111-1111-111111111111";

	private static final String BOSS = "22222222-2222-2222-2222-222222222222";

	private static final String PROG = "33333333-3333-3333-3333-333333333333";

	static class FakeJdbc extends JdbcTemplate {

		final Map<String, Object> stage = new LinkedHashMap<>();

		final Map<String, Object> project = new LinkedHashMap<>();

		long openIssues;

		final List<String> updates = new ArrayList<>();

		final List<Object[]> updateArgs = new ArrayList<>();

		FakeJdbc(String stageStatus, String projectStatus, long openIssues) {
			stage.put("id", "s-1");
			stage.put("project_id", "p-1");
			stage.put("name", "建错的阶段");
			stage.put("sort_order", 1);
			stage.put("status", stageStatus);
			stage.put("version", 2);
			project.put("id", "p-1");
			project.put("status", projectStatus);
			project.put("owner_user_id", PM);
			this.openIssues = openIssues;
		}

		@Override
		public Map<String, Object> queryForMap(String sql, Object... args) {
			return new LinkedHashMap<>(stage);
		}

		@Override
		public List<Map<String, Object>> queryForList(String sql, Object... args) {
			return List.of(new LinkedHashMap<>(project));
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
			assertTrue(sql.contains("FROM issues") && sql.contains("OPEN"), "只应查询未解决异常数：" + sql);
			return (T) Long.valueOf(openIssues);
		}

		@Override
		public int update(String sql, Object... args) {
			updates.add(sql);
			updateArgs.add(args);
			return 1;
		}

		boolean softDeleted() {
			return updates.stream().anyMatch((s) -> s.contains("UPDATE project_stages") && s.contains("deleted_at"));
		}

		Object[] stageEvent() {
			for (int i = 0; i < updates.size(); i++) {
				if (updates.get(i).contains("INSERT INTO stage_events")) {
					return updateArgs.get(i);
				}
			}
			return null;
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

	private StageService service(FakeJdbc jdbc, String userId, String role) {
		return new StageService(jdbc, new FixedActor(userId, role), audit, events);
	}

	private static Map<String, Object> reason(String text) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("reason", text);
		return body;
	}

	@Test
	void ownerVoidsNotStartedStage() {
		FakeJdbc jdbc = new FakeJdbc("NOT_STARTED", "ACTIVE", 0);

		Map<String, Object> after = service(jdbc, PM, "PM").voidStage("s-1", 2, reason("阶段建重复了"));

		assertEquals("VOIDED", after.get("status"));
		assertEquals(3, after.get("version"));
		assertEquals("阶段建重复了", after.get("voidReason"));
		assertTrue(jdbc.softDeleted(), "作废应写 deleted_at 软删");
		Object[] ev = jdbc.stageEvent();
		assertTrue(ev != null && "VOID".equals(ev[1]) && "阶段建重复了".equals(ev[4]), "阶段事件应记录 VOID 与原因");
		assertEquals(1, audit.records.size());
		assertEquals("VOID", audit.records.get(0)[2]);
		assertEquals(List.of("stage.voided:NOT_STARTED>VOIDED"), events.types);
	}

	@Test
	void onlyNotStartedStageCanBeVoided() {
		for (String status : List.of("IN_PROGRESS", "BLOCKED", "COMPLETED")) {
			FakeJdbc jdbc = new FakeJdbc(status, "ACTIVE", 0);
			MoldApiException ex = assertThrows(MoldApiException.class,
					() -> service(jdbc, PM, "PM").voidStage("s-1", 2, reason("x")));
			assertEquals(409, ex.getStatus(), status);
			assertEquals("INVALID_STATE_TRANSITION", ex.getErrorCode(), status);
			assertTrue(jdbc.updates.isEmpty(), "不能写库：" + status);
		}
	}

	@Test
	void reasonIsRequired() {
		for (Map<String, Object> body : java.util.Arrays.asList(null, reason(""), reason("   "))) {
			FakeJdbc jdbc = new FakeJdbc("NOT_STARTED", "ACTIVE", 0);
			MoldApiException ex = assertThrows(MoldApiException.class,
					() -> service(jdbc, PM, "PM").voidStage("s-1", 2, body));
			assertEquals(422, ex.getStatus());
			assertTrue(jdbc.updates.isEmpty());
		}
	}

	@Test
	void stageWithOpenIssuesCannotBeVoided() {
		FakeJdbc jdbc = new FakeJdbc("NOT_STARTED", "ACTIVE", 2);

		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> service(jdbc, PM, "PM").voidStage("s-1", 2, reason("x")));

		assertEquals(409, ex.getStatus());
		assertEquals("STAGE_HAS_OPEN_ISSUES", ex.getErrorCode());
		assertTrue(jdbc.updates.isEmpty());
	}

	@Test
	void staleVersionIsConflict() {
		FakeJdbc jdbc = new FakeJdbc("NOT_STARTED", "ACTIVE", 0);

		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> service(jdbc, PM, "PM").voidStage("s-1", 1, reason("x")));

		assertEquals(409, ex.getStatus());
		assertEquals("VERSION_CONFLICT", ex.getErrorCode());
		assertTrue(jdbc.updates.isEmpty());
	}

	@Test
	void nonOwnerIsForbidden() {
		FakeJdbc jdbc = new FakeJdbc("NOT_STARTED", "ACTIVE", 0);

		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> service(jdbc, PROG, "PROGRAMMER").voidStage("s-1", 2, reason("x")));

		assertEquals(403, ex.getStatus());
		assertEquals("NOT_PROJECT_OWNER", ex.getErrorCode());
		assertTrue(jdbc.updates.isEmpty());
	}

	@Test
	void bossCanVoidStageOfOthersProject() {
		FakeJdbc jdbc = new FakeJdbc("NOT_STARTED", "ON_HOLD", 0);

		Map<String, Object> after = service(jdbc, BOSS, "OWNER").voidStage("s-1", 2, reason("老板清理"));

		assertEquals("VOIDED", after.get("status"));
	}

	@Test
	void closedProjectRejectsVoid() {
		for (String status : List.of("COMPLETED", "CANCELLED")) {
			FakeJdbc jdbc = new FakeJdbc("NOT_STARTED", status, 0);
			MoldApiException ex = assertThrows(MoldApiException.class,
					() -> service(jdbc, PM, "PM").voidStage("s-1", 2, reason("x")));
			assertEquals(409, ex.getStatus(), status);
			assertTrue(jdbc.updates.isEmpty(), status);
		}
	}

}
