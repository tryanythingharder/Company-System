/*
 * M2 单测：阶段状态机四态流转与未确认规则（契约 6.6 / 02 P2、P3）。
 */
package com.pig4cloud.pig.mold.project;

import com.pig4cloud.pig.mold.web.MoldApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StageStateMachineTest {

	@Test
	void startFromNotStarted() {
		StageStateMachine.Transition transition = StageStateMachine.resolve("NOT_STARTED", "IN_PROGRESS", null);
		assertEquals("START", transition.action());
		assertEquals("IN_PROGRESS", transition.toStatus());
	}

	@Test
	void blockAndResume() {
		assertEquals("BLOCK", StageStateMachine.resolve("IN_PROGRESS", "BLOCKED", null).action());
		assertEquals("RESUME", StageStateMachine.resolve("BLOCKED", "IN_PROGRESS", null).action());
	}

	@Test
	void completeOnlyFromInProgress() {
		assertEquals("COMPLETE", StageStateMachine.resolve("IN_PROGRESS", "COMPLETED", null).action());
	}

	@Test
	void acceptsLegacyActionNames() {
		assertEquals("IN_PROGRESS", StageStateMachine.resolve("NOT_STARTED", null, "START").toStatus());
		assertEquals("COMPLETED", StageStateMachine.resolve("IN_PROGRESS", null, "COMPLETE").toStatus());
	}

	@Test
	void illegalTransitionIs409WithAllowedActions() {
		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> StageStateMachine.resolve("COMPLETED", "IN_PROGRESS", null));
		assertEquals(409, ex.getStatus());
		assertEquals("INVALID_STATE_TRANSITION", ex.getErrorCode());

		@SuppressWarnings("unchecked")
		Map<String, Object> details = (Map<String, Object>) ex.getDetails();
		assertEquals("STAGE_STATE", details.get("conflictKind"));
		assertEquals("COMPLETED", details.get("currentStatus"));
		assertEquals(List.of(), details.get("allowedTargets"));
		assertEquals(List.of(), details.get("allowedActions"));
	}

	@Test
	void blockedStageDetailsListResumeAsAllowedAction() {
		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> StageStateMachine.resolve("BLOCKED", "COMPLETED", null));

		@SuppressWarnings("unchecked")
		Map<String, Object> details = (Map<String, Object>) ex.getDetails();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> allowedActions = (List<Map<String, Object>>) details.get("allowedActions");
		assertEquals(1, allowedActions.size());
		assertEquals("RESUME", allowedActions.get(0).get("action"));
		assertEquals("IN_PROGRESS", allowedActions.get(0).get("targetStatus"));
	}

	@Test
	void policyConflictAlsoCarriesAllowedActions() {
		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> StageStateMachine.resolve("COMPLETED", "NOT_STARTED", null));
		assertEquals("POLICY_NOT_CONFIRMED", ex.getErrorCode());

		@SuppressWarnings("unchecked")
		Map<String, Object> details = (Map<String, Object>) ex.getDetails();
		assertEquals("POLICY", details.get("conflictKind"));
		assertEquals(List.of(), details.get("allowedActions"));
	}

	@Test
	void reopenAndVoidArePolicyNotConfirmed() {
		assertEquals("POLICY_NOT_CONFIRMED",
				assertThrows(MoldApiException.class,
						() -> StageStateMachine.resolve("COMPLETED", "NOT_STARTED", null)).getErrorCode());
		assertEquals("POLICY_NOT_CONFIRMED",
				assertThrows(MoldApiException.class,
						() -> StageStateMachine.resolve("IN_PROGRESS", null, "REOPEN")).getErrorCode());
	}

	@Test
	void unknownStatusIs422() {
		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> StageStateMachine.resolve("IN_PROGRESS", "WAITING", null));
		assertEquals(422, ex.getStatus());
		assertEquals("VALIDATION_FAILED", ex.getErrorCode());
	}

}
