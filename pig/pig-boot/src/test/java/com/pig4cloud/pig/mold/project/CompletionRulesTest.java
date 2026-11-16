/*
 * M2 单测：D1 完工三要素（契约 6.5）。
 * 顺序必须固定：无有效阶段 → 有未完成阶段 → 有未解决异常。
 */
package com.pig4cloud.pig.mold.project;

import com.pig4cloud.pig.mold.web.MoldApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionRulesTest {

	@Test
	void rejectsWhenThereIsNoStage() {
		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> CompletionRules.validate(List.of(), List.of()));
		assertEquals(409, ex.getStatus());
		assertEquals("PROJECT_HAS_NO_STAGES", ex.getErrorCode());
		assertEquals("STAGES", details(ex).get("missing"));
	}

	@Test
	void rejectsWhenStagesAreNotAllCompleted() {
		List<CompletionRules.StageRef> stages = List.of(new CompletionRules.StageRef("s1", "设计", "COMPLETED"),
				new CompletionRules.StageRef("s2", "加工", "IN_PROGRESS"));
		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> CompletionRules.validate(stages, List.of()));
		assertEquals("PROJECT_HAS_OPEN_STAGES", ex.getErrorCode());
		assertEquals("OPEN_STAGES", details(ex).get("missing"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> open = (List<Map<String, Object>>) details(ex).get("stages");
		assertEquals(1, open.size());
		assertEquals("s2", open.get(0).get("id"));
		assertEquals("加工", open.get(0).get("name"));
	}

	@Test
	void rejectsWhenOpenIssueExists() {
		List<CompletionRules.StageRef> stages = List
			.of(new CompletionRules.StageRef("s1", "设计", "COMPLETED"));
		List<CompletionRules.IssueRef> issues = List
			.of(new CompletionRules.IssueRef("i1", "尺寸超差", "s1"));
		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> CompletionRules.validate(stages, issues));
		assertEquals("PROJECT_HAS_OPEN_ISSUES", ex.getErrorCode());
		assertEquals("OPEN_ISSUES", details(ex).get("missing"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> open = (List<Map<String, Object>>) details(ex).get("issues");
		assertEquals("i1", open.get(0).get("id"));
		assertEquals("尺寸超差", open.get(0).get("title"));
	}

	@Test
	void stageCheckRunsBeforeIssueCheck() {
		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> CompletionRules.validate(List.of(new CompletionRules.StageRef("s1", "加工", "BLOCKED")),
						List.of(new CompletionRules.IssueRef("i1", "未解决", null))));
		assertEquals("PROJECT_HAS_OPEN_STAGES", ex.getErrorCode());
	}

	@Test
	void acceptsAllStagesCompletedAndNoOpenIssue() {
		List<CompletionRules.StageRef> stages = List.of(
				new CompletionRules.StageRef("s1", "设计", "COMPLETED"),
				new CompletionRules.StageRef("s2", "加工", "COMPLETED"));
		assertDoesNotThrow(() -> CompletionRules.validate(stages, List.of()));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> details(MoldApiException ex) {
		Map<String, Object> details = (Map<String, Object>) ex.getDetails();
		assertTrue(details != null);
		return details;
	}

}
