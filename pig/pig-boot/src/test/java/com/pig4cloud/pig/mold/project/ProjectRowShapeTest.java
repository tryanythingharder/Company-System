/*
 * M2 修复轮单测：
 *  - G-21 重复 code 的 409 必须带 details.field / details.value；
 *  - 列表行必须平铺 blockedStageCount，且与 stageCounts.blocked 一致。
 */
package com.pig4cloud.pig.mold.project;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectRowShapeTest {

	@Test
	void duplicateCodeDetailsHasFieldAndValue() {
		Map<String, Object> details = ProjectService.duplicateCodeDetails("M2-S4-DUP-001");
		assertEquals("code", details.get("field"));
		assertEquals("M2-S4-DUP-001", details.get("value"));
	}

	@Test
	void blockedStageCountIsFlattenedFromStageCounts() {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", "p-1");
		row.put("code", "M2-S4-ROW-001");
		row.put("name", "统计卡用例");
		row.put("business_type", "MOLD_SET");
		row.put("status", "ACTIVE");
		row.put("owner_user_id", "u-1");
		row.put("owner_display_name", "负责人");
		row.put("stage_total", 4);
		row.put("stage_not_started", 1);
		row.put("stage_in_progress", 1);
		row.put("stage_blocked", 2);
		row.put("stage_completed", 0);
		row.put("open_issue_count", 0);
		row.put("version", 3);

		Map<String, Object> dto = new ProjectService(null, null, null, null).dto(row);

		assertEquals(2, dto.get("blockedStageCount"));
		@SuppressWarnings("unchecked")
		Map<String, Object> counts = (Map<String, Object>) dto.get("stageCounts");
		assertEquals(2, counts.get("blocked"));
	}

	@Test
	void blockedStageCountDefaultsToZeroWhenNoStages() {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", "p-2");
		row.put("version", 1);

		Map<String, Object> dto = new ProjectService(null, null, null, null).dto(row);

		assertEquals(0, dto.get("blockedStageCount"));
	}

}
