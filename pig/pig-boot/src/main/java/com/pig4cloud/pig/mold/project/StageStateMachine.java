/*
 * 模具加工管理系统 M2 后端切片
 * 阶段状态机（contract 6.6 / feature-parity P2、P3）：
 *   NOT_STARTED → IN_PROGRESS（START）
 *   IN_PROGRESS → BLOCKED（BLOCK，必须原因）
 *   BLOCKED     → IN_PROGRESS（RESUME）
 *   IN_PROGRESS → COMPLETED（COMPLETE）
 * COMPLETED 无后续动作；返工/重开（REOPEN）与阶段作废属未确认规则，返回 409 POLICY_NOT_CONFIRMED。
 */
package com.pig4cloud.pig.mold.project;

import com.pig4cloud.pig.mold.web.MoldApiException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StageStateMachine {

	public static final String NOT_STARTED = "NOT_STARTED";

	public static final String IN_PROGRESS = "IN_PROGRESS";

	public static final String BLOCKED = "BLOCKED";

	public static final String COMPLETED = "COMPLETED";

	public static final Set<String> STATUSES = Set.of(NOT_STARTED, IN_PROGRESS, BLOCKED, COMPLETED);

	public static final Set<String> ACTIONS = Set.of("START", "BLOCK", "RESUME", "COMPLETE");

	private static final Map<String, String> ACTION_TARGETS = Map.of("START", IN_PROGRESS, "BLOCK", BLOCKED, "RESUME",
			IN_PROGRESS, "COMPLETE", COMPLETED);

	private static final Map<String, List<String>> ALLOWED_TARGETS = Map.of(NOT_STARTED, List.of(IN_PROGRESS),
			IN_PROGRESS, List.of(BLOCKED, COMPLETED), BLOCKED, List.of(IN_PROGRESS), COMPLETED, List.of());

	private static final Map<String, String> ACTION_OF_TARGET = Map.of(NOT_STARTED + ">" + IN_PROGRESS, "START",
			BLOCKED + ">" + IN_PROGRESS, "RESUME", IN_PROGRESS + ">" + BLOCKED, "BLOCK",
			IN_PROGRESS + ">" + COMPLETED, "COMPLETE");

	private StageStateMachine() {
	}

	public record Transition(String action, String fromStatus, String toStatus) {
	}

	/**
	 * 支持契约格式 `toStatus` 与旧客户端 `action` 两种入参。
	 * @throws MoldApiException 422 未知目标状态；409 未确认规则或非法流转
	 */
	public static Transition resolve(String currentStatus, Object rawToStatus, Object rawAction) {
		String toStatus = text(rawToStatus);
		Object actionValue = rawAction;
		if (toStatus == null) {
			String action = text(actionValue);
			if (action != null) {
				String upper = action.toUpperCase();
				if ("REOPEN".equals(upper) || "VOID".equals(upper) || "CANCEL".equals(upper)) {
					throw policy(currentStatus);
				}
				toStatus = ACTION_TARGETS.get(upper);
			}
		}
		if (toStatus == null) {
			throw MoldApiException.validation("/body/toStatus", "required", null, "必须提供 toStatus");
		}
		toStatus = toStatus.toUpperCase();

		if (NOT_STARTED.equals(toStatus) || "CANCELLED".equals(toStatus) || "VOID".equals(toStatus)) {
			// 返工/重开与阶段作废不在已确认业务规则内（旧系统 closedOperations）。
			throw policy(currentStatus);
		}
		if (!STATUSES.contains(toStatus)) {
			throw MoldApiException.validation("/body/toStatus", "enum",
					Map.of("allowedValues", List.of(IN_PROGRESS, BLOCKED, COMPLETED)), "未知阶段状态");
		}

		List<String> allowed = ALLOWED_TARGETS.getOrDefault(currentStatus, List.of());
		if (!allowed.contains(toStatus)) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("conflictKind", "STAGE_STATE");
			details.put("resourceType", "STAGE");
			details.put("currentStatus", currentStatus);
			details.put("requestedStatus", toStatus);
			details.put("allowedTargets", allowed);
			details.put("allowedActions", allowedActions(currentStatus));
			details.put("terminal", allowed.isEmpty());
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "当前阶段状态不允许该操作", details);
		}

		String action = ACTION_OF_TARGET.get(currentStatus + ">" + toStatus);
		if (action == null) {
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "当前阶段状态不允许该操作");
		}
		return new Transition(action, currentStatus, toStatus);
	}

	/**
	 * 前端「我的工作」按状态显示动作。
	 */
	public static List<Map<String, Object>> allowedActions(String status) {
		List<String> targets = ALLOWED_TARGETS.getOrDefault(status, List.of());
		return targets.stream().map(target -> {
			String action = ACTION_OF_TARGET.get(status + ">" + target);
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("action", action);
			item.put("targetStatus", target);
			item.put("reasonRequired", BLOCKED.equals(target));
			return item;
		}).toList();
	}

	private static MoldApiException policy(String currentStatus) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("conflictKind", "POLICY");
		details.put("resourceType", "STAGE");
		details.put("currentStatus", currentStatus);
		details.put("allowedActions", allowedActions(currentStatus));
		return new MoldApiException(409, "POLICY_NOT_CONFIRMED", "阶段返工/重开与作废规则尚未确认，本地版本关闭该操作",
				details);
	}

	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		String text = value.toString().trim();
		return text.isEmpty() ? null : text;
	}

}
