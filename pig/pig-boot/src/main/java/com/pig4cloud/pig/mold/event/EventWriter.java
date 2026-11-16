/*
 * 模具加工管理系统 M2 后端切片
 * 事件发件箱写入（契约 6.6 / 6.1 dataVersion）。
 *
 * event_outbox.id 由数据库触发器从 event_clock.version 分配，因此每次写事件都会推进
 * 列表响应里的 dataVersion，SSE 可从 Last-Event-ID 续传。
 * payload 只允许 status / previousStatus / changedFields 三个键（数据库 CHECK 约束）。
 */
package com.pig4cloud.pig.mold.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class EventWriter {

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	public void emit(String eventType, String resourceType, String resourceId, String projectId, String stageId,
			String actorId, Integer resourceVersion, String visibility, String status, String previousStatus,
			List<String> changedFields) {
		Map<String, Object> payload = new LinkedHashMap<>();
		if (status != null) {
			payload.put("status", status);
		}
		if (previousStatus != null) {
			payload.put("previousStatus", previousStatus);
		}
		if (changedFields != null && !changedFields.isEmpty()) {
			payload.put("changedFields", changedFields);
		}
		try {
			jdbcTemplate.update("""
					INSERT INTO event_outbox
						(event_type, resource_type, resource_id, project_id, stage_id, actor_id,
						 resource_version, visibility, payload)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
					""", eventType, resourceType, resourceId, projectId, stageId, actorId, resourceVersion,
					visibility == null ? "BASIC" : visibility, objectMapper.writeValueAsString(payload));
		}
		catch (Exception ex) {
			throw new IllegalStateException("事件写入失败: " + eventType + "/" + resourceId, ex);
		}
	}

}
