/*
 * 模具加工管理系统 M2 后端切片
 * G5 异常事项接口（契约 6.7）。
 *
 * 权限说明：sys_menu 已补 mold:issue:update / mold:issue:cancel 两枚权限
 * （db/mold_m6_issue_permissions.sql，按契约 6.7 绑定 6 个固定角色）；
 * 本控制器按契约严格校验，不再用 mold:issue:handle 兜底。
 */
package com.pig4cloud.pig.mold.project;

import com.pig4cloud.pig.common.core.util.R;
import com.pig4cloud.pig.mold.web.IdempotencyService;
import com.pig4cloud.pig.mold.web.MoldIfMatch;
import com.pig4cloud.pig.mold.web.MoldResponses;
import com.pig4cloud.pig.mold.web.PageQuery;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mold/issues")
public class IssueController {

	private static final String REPLAYED_HEADER = "Idempotent-Replayed";

	private final IssueService issueService;

	private final IdempotencyService idempotencyService;

	@GetMapping
	@PreAuthorize("@pms.hasPermission('mold:project:list')")
	public R<Map<String, Object>> list(@RequestParam(required = false) String pageNum,
			@RequestParam(required = false) String pageSize, @RequestParam(required = false) String sort,
			@RequestParam(required = false) String order, @RequestParam(required = false) String projectId,
			@RequestParam(required = false) String status, @RequestParam(required = false) String severity) {
		PageQuery page = PageQuery.parse(pageNum, pageSize, sort, order, IssueService.SORTABLE, "id");
		return R.ok(issueService.list(page, projectId, status, severity));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("@pms.hasPermission('mold:issue:create')")
	public R<Map<String, Object>> create(
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("issue.create", idempotencyKey,
				"POST /mold/issues", body, 201, () -> issueService.create(body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@GetMapping("/{issueId}")
	@PreAuthorize("@pms.hasPermission('mold:project:list')")
	public R<Map<String, Object>> detail(@PathVariable String issueId, HttpServletResponse response) {
		Map<String, Object> data = issueService.get(issueId);
		MoldResponses.etag(response, data.get("version"));
		return R.ok(data);
	}

	@PatchMapping("/{issueId}")
	@PreAuthorize("@pms.hasPermission('mold:issue:update')")
	public R<Map<String, Object>> update(@PathVariable String issueId,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.execute("issue.update", idempotencyKey,
				"PATCH /mold/issues/" + issueId + "|" + ifMatch, body, 200,
				() -> issueService.update(issueId, MoldIfMatch.parse(ifMatch), body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PostMapping("/{issueId}/resolve")
	@PreAuthorize("@pms.hasPermission('mold:issue:handle')")
	public R<Map<String, Object>> resolve(@PathVariable String issueId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("issue.resolve", idempotencyKey,
				"POST /mold/issues/" + issueId + "/resolve", body, 200, () -> issueService.resolve(issueId, body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PostMapping("/{issueId}/cancel")
	@PreAuthorize("@pms.hasPermission('mold:issue:cancel')")
	public R<Map<String, Object>> cancel(@PathVariable String issueId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("issue.cancel", idempotencyKey,
				"POST /mold/issues/" + issueId + "/cancel", body, 200, () -> issueService.cancel(issueId, body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	private static void markReplayed(HttpServletResponse response, IdempotencyService.Outcome outcome) {
		if (outcome.replayed()) {
			response.setHeader(REPLAYED_HEADER, "true");
		}
	}

}
