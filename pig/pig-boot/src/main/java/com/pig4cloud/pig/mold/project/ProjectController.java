/*
 * 模具加工管理系统 M2 后端切片
 * G3 项目接口：列表 / 导出 / 详情 / 新建 / 更新 / 变更负责人 / 成员 / 完工 / 重开。
 */
package com.pig4cloud.pig.mold.project;

import com.pig4cloud.pig.common.core.util.R;
import com.pig4cloud.pig.mold.web.IdempotencyService;
import com.pig4cloud.pig.mold.web.MoldIfMatch;
import com.pig4cloud.pig.mold.web.MoldResponses;
import com.pig4cloud.pig.mold.web.PageQuery;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mold/projects")
public class ProjectController {

	private static final String REPLAYED_HEADER = "Idempotent-Replayed";

	private final ProjectService projectService;

	private final IssueService issueService;

	private final IdempotencyService idempotencyService;

	@GetMapping
	@PreAuthorize("@pms.hasPermission('mold:project:list')")
	public R<Map<String, Object>> list(@RequestParam(required = false) String pageNum,
			@RequestParam(required = false) String pageSize, @RequestParam(required = false) String sort,
			@RequestParam(required = false) String order, @RequestParam(required = false) String status,
			@RequestParam(required = false) String businessType, @RequestParam(required = false) String ownerUserId,
			@RequestParam(required = false) String q) {
		PageQuery page = PageQuery.parse(pageNum, pageSize, sort, order, ProjectService.SORTABLE, "code");
		return R.ok(projectService.list(page, status, businessType, ownerUserId, q));
	}

	/**
	 * 全量导出（D1）：当前筛选条件下一次取全部行，不受 pageSize 上限影响；CSV 流式输出。
	 * 需要导出权限（mold:project:export）；导出行的可见范围与列表一致。
	 */
	@GetMapping("/export")
	@PreAuthorize("@pms.hasPermission('mold:project:export')")
	public ResponseEntity<StreamingResponseBody> export(@RequestParam(required = false) String sort,
			@RequestParam(required = false) String order, @RequestParam(required = false) String status,
			@RequestParam(required = false) String businessType, @RequestParam(required = false) String ownerUserId,
			@RequestParam(required = false) String q) {
		PageQuery page = PageQuery.parse("1", "20", sort, order, ProjectService.SORTABLE, "code");
		String filename = "projects-" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)
				+ ".csv";
		StreamingResponseBody body = out -> projectService.export(status, businessType, ownerUserId, q, page.sort(),
				page.order(), out);
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
			.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body(body);
	}

	@GetMapping("/{projectId}")
	@PreAuthorize("@pms.hasPermission('mold:project:list')")
	public R<Map<String, Object>> detail(@PathVariable String projectId, HttpServletResponse response) {
		Map<String, Object> data = projectService.get(projectId);
		MoldResponses.etag(response, data.get("version"));
		return R.ok(data);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("@pms.hasPermission('mold:project:create')")
	public R<Map<String, Object>> create(
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("project.create", idempotencyKey,
				"POST /mold/projects", body, 201, () -> projectService.create(body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PatchMapping("/{projectId}")
	@PreAuthorize("@pms.hasPermission('mold:project:update')")
	public R<Map<String, Object>> update(@PathVariable String projectId,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.execute("project.update", idempotencyKey,
				"PATCH /mold/projects/" + projectId + "|" + ifMatch, body, 200,
				() -> projectService.update(projectId, MoldIfMatch.parse(ifMatch), body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PutMapping("/{projectId}/owner")
	@PreAuthorize("@pms.hasPermission('mold:project:owner')")
	public R<Map<String, Object>> transferOwner(@PathVariable String projectId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.execute("project.owner", idempotencyKey,
				"PUT /mold/projects/" + projectId + "/owner", body, 200,
				() -> projectService.transferOwner(projectId, body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@GetMapping("/{projectId}/members")
	// 能看项目详情就能看成员（详情页成员卡片对编程、操机、财务、只读也要显示）；改成员仍要 member 权限
	@PreAuthorize("@pms.hasPermission('mold:project:member', 'mold:project:list')")
	public R<Map<String, Object>> members(@PathVariable String projectId) {
		return R.ok(projectService.members(projectId));
	}

	@PutMapping("/{projectId}/members")
	@PreAuthorize("@pms.hasPermission('mold:project:member')")
	public R<Map<String, Object>> setMembers(@PathVariable String projectId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.execute("project.members", idempotencyKey,
				"PUT /mold/projects/" + projectId + "/members", body, 200,
				() -> projectService.setMembers(projectId, body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@GetMapping("/{projectId}/issues")
	@PreAuthorize("@pms.hasPermission('mold:project:list')")
	public R<Map<String, Object>> issues(@PathVariable String projectId) {
		return R.ok(issueService.listForProject(projectId));
	}

	@PostMapping("/{projectId}/complete")
	@PreAuthorize("@pms.hasPermission('mold:project:complete')")
	public R<Map<String, Object>> complete(@PathVariable String projectId,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("project.complete", idempotencyKey,
				"POST /mold/projects/" + projectId + "/complete|" + ifMatch, body, 200,
				() -> projectService.complete(projectId, MoldIfMatch.parse(ifMatch), body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PostMapping("/{projectId}/hold")
	@PreAuthorize("@pms.hasPermission('mold:project:update')")
	public R<Map<String, Object>> hold(@PathVariable String projectId,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) {
		return changeStatus("HOLD", projectId, ifMatch, idempotencyKey, body, response);
	}

	@PostMapping("/{projectId}/resume")
	@PreAuthorize("@pms.hasPermission('mold:project:update')")
	public R<Map<String, Object>> resume(@PathVariable String projectId,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) {
		return changeStatus("RESUME", projectId, ifMatch, idempotencyKey, body, response);
	}

	@PostMapping("/{projectId}/cancel")
	@PreAuthorize("@pms.hasPermission('mold:project:update')")
	public R<Map<String, Object>> cancel(@PathVariable String projectId,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) {
		return changeStatus("CANCEL", projectId, ifMatch, idempotencyKey, body, response);
	}

	private R<Map<String, Object>> changeStatus(String action, String projectId, String ifMatch,
			String idempotencyKey, Map<String, Object> body, HttpServletResponse response) {
		Map<String, Object> safeBody = body == null ? new java.util.LinkedHashMap<>() : body;
		String path = action.toLowerCase();
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("project." + path, idempotencyKey,
				"POST /mold/projects/" + projectId + "/" + path + "|" + ifMatch, safeBody, 200,
				() -> projectService.changeStatus(projectId, action, MoldIfMatch.parse(ifMatch), safeBody));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PostMapping("/{projectId}/reopen")
	@PreAuthorize("@pms.hasPermission('mold:project:reopen')")
	public R<Void> reopen(@PathVariable String projectId) {
		projectService.reopen(projectId);
		return R.ok();
	}

	private static void markReplayed(HttpServletResponse response, IdempotencyService.Outcome outcome) {
		if (outcome.replayed()) {
			response.setHeader(REPLAYED_HEADER, "true");
		}
	}

}
