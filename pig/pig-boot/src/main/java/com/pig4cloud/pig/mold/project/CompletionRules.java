/*
 * 模具加工管理系统 M2 后端切片
 * D1 完工三要素（契约 6.5 / 6.6）：
 *   1) 至少一个有效阶段；
 *   2) 所有阶段已完成；
 *   3) 项目没有任何未解决异常（status=OPEN 且未软删）。
 * 缺一项返回 409，details 说明缺的是哪一项以及具体阶段/异常。
 */
package com.pig4cloud.pig.mold.project;

import com.pig4cloud.pig.mold.web.MoldApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CompletionRules {

	private CompletionRules() {
	}

	public record StageRef(String id, String name, String status) {
	}

	public record IssueRef(String id, String title, String stageId) {
	}

	public static void validate(List<StageRef> stages, List<IssueRef> openIssues) {
		if (stages == null || stages.isEmpty()) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("stageCount", 0);
			details.put("missing", "STAGES");
			throw new MoldApiException(409, "PROJECT_HAS_NO_STAGES", "至少需要一个有效阶段才能确认整单完工", details);
		}

		List<Map<String, Object>> open = new ArrayList<>();
		for (StageRef stage : stages) {
			if (!"COMPLETED".equals(stage.status())) {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("id", stage.id());
				item.put("name", stage.name());
				item.put("status", stage.status());
				open.add(item);
			}
		}
		if (!open.isEmpty()) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("missing", "OPEN_STAGES");
			details.put("stages", open);
			throw new MoldApiException(409, "PROJECT_HAS_OPEN_STAGES", "仍有未完成阶段，不能确认整单完工", details);
		}

		if (openIssues != null && !openIssues.isEmpty()) {
			List<Map<String, Object>> issues = new ArrayList<>();
			for (IssueRef issue : openIssues) {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("id", issue.id());
				item.put("title", issue.title());
				item.put("stageId", issue.stageId());
				issues.add(item);
			}
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("missing", "OPEN_ISSUES");
			details.put("issues", issues);
			throw new MoldApiException(409, "PROJECT_HAS_OPEN_ISSUES", "仍有未解决异常，不能确认整单完工", details);
		}
	}

}
