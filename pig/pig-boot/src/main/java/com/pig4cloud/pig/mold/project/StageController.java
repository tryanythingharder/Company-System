/*
 * 模具加工管理系统 M2 后端切片
 * G4 阶段接口（契约 6.6）。
 */
package com.pig4cloud.pig.mold.project;

import com.pig4cloud.pig.common.core.util.R;
import com.pig4cloud.pig.mold.web.IdempotencyService;
import com.pig4cloud.pig.mold.web.MoldIfMatch;
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
@RequestMapping("/mold")
public class StageController {

	private final StageService stageService;

	private final IdempotencyService idempotencyService;

	@GetMapping("/projects/{projectId}/stages")
	@PreAuthorize("@pms.hasPermission('mold:project:list')")
	public R<Map<String, Object>> list(@PathVariable String projectId) {
		return R.ok(stageService.listStages(projectId));
	}

	@PostMapping("/projects/{projectId}/stages")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("@pms.hasPermission('mold:stage:create')")
	public R<Map<String, Object>> create(@PathVariable String projectId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("stage.create", idempotencyKey,
				"POST /mold/projects/" + projectId + "/stages", body, 201,
				() -> stageService.createStage(projectId, body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PatchMapping("/stages/{stageId}")
	@PreAuthorize("@pms.hasPermission('mold:stage:update')")
	public R<Map<String, Object>> update(@PathVariable String stageId,
			@RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestBody Map<String, Object> body) {
		return R.ok(stageService.updateStage(stageId, MoldIfMatch.parse(ifMatch), body));
	}

	@PostMapping("/stages/{stageId}/transitions")
	@PreAuthorize("@pms.hasPermission('mold:stage:transition')")
	public R<Map<String, Object>> transition(@PathVariable String stageId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("stage.transition", idempotencyKey,
				"POST /mold/stages/" + stageId + "/transitions", body, 200,
				() -> stageService.transition(stageId, body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PostMapping("/stages/{stageId}/void")
	@PreAuthorize("@pms.hasPermission('mold:stage:create')")
	public R<Map<String, Object>> voidStage(@PathVariable String stageId,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) {
		Map<String, Object> safeBody = body == null ? new java.util.LinkedHashMap<>() : body;
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("stage.void", idempotencyKey,
				"POST /mold/stages/" + stageId + "/void|" + ifMatch, safeBody, 200,
				() -> stageService.voidStage(stageId, MoldIfMatch.parse(ifMatch), safeBody));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@GetMapping("/stages/{stageId}/events")
	@PreAuthorize("@pms.hasPermission('mold:project:list')")
	public R<Map<String, Object>> events(@PathVariable String stageId,
			@RequestParam(required = false) String pageNum, @RequestParam(required = false) String pageSize,
			@RequestParam(required = false) String sort, @RequestParam(required = false) String order) {
		PageQuery page = PageQuery.parse(pageNum, pageSize, sort, order, StageService.eventSortable(), "id");
		return R.ok(stageService.events(stageId, page));
	}

	@GetMapping("/me/stages")
	@PreAuthorize("@pms.hasPermission('mold:project:list')")
	public R<Map<String, Object>> mine(@RequestParam(required = false) String pageNum,
			@RequestParam(required = false) String pageSize, @RequestParam(required = false) String sort,
			@RequestParam(required = false) String order, @RequestParam(required = false) String status) {
		PageQuery page = PageQuery.parse(pageNum, pageSize, sort, order, StageService.SORTABLE, "id");
		return R.ok(stageService.myStages(page, status));
	}

	private static void markReplayed(HttpServletResponse response, IdempotencyService.Outcome outcome) {
		if (outcome.replayed()) {
			response.setHeader("Idempotent-Replayed", "true");
		}
	}

}
