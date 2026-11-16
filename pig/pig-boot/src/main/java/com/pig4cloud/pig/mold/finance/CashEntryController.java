/*
 * 模具加工管理系统 M7-A / A2 财务台账与更正接口（契约 6.9，10 操作）。
 */
package com.pig4cloud.pig.mold.finance;

import com.pig4cloud.pig.common.core.util.R;
import com.pig4cloud.pig.mold.web.IdempotencyService;
import com.pig4cloud.pig.mold.web.MoldIfMatch;
import com.pig4cloud.pig.mold.web.MoldResponses;
import com.pig4cloud.pig.mold.web.PageQuery;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
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
@RequestMapping("/mold/cash-entries")
public class CashEntryController {

	private static final String REPLAYED_HEADER = "Idempotent-Replayed";

	private final CashEntryService cashEntryService;

	private final CorrectionService correctionService;

	private final IdempotencyService idempotencyService;

	@GetMapping
	@PreAuthorize("@pms.hasPermission('mold:cash:list')")
	public R<Map<String, Object>> list(@RequestParam(required = false) String pageNum,
			@RequestParam(required = false) String pageSize, @RequestParam(required = false) String sort,
			@RequestParam(required = false) String order, @RequestParam(required = false) String projectId,
			@RequestParam(required = false) String purchaseId, @RequestParam(required = false) String direction,
			@RequestParam(required = false) String category, @RequestParam(required = false) String currency,
			@RequestParam(required = false) String reconciled, @RequestParam(required = false) String allocationStatus,
			@RequestParam(required = false) String status, @RequestParam(required = false) String dateFrom,
			@RequestParam(required = false) String dateTo, @RequestParam(required = false) String q) {
		PageQuery page = PageQuery.parse(pageNum, pageSize, sort, order, CashEntryService.SORTABLE, "occurredOn");
		if (!StringUtils.hasText(sort) && !StringUtils.hasText(order)) {
			page = new PageQuery(page.pageNum(), page.pageSize(), page.sort(), "desc");
		}
		return R.ok(cashEntryService.list(page, projectId, purchaseId, direction, category, currency, reconciled,
				allocationStatus, status, dateFrom, dateTo, q));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("@pms.hasPermission('mold:cash:create')")
	public R<Map<String, Object>> create(
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("cash.create", idempotencyKey,
				"POST /mold/cash-entries", body, 201, () -> cashEntryService.create(body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@GetMapping("/{entryId}")
	@PreAuthorize("@pms.hasPermission('mold:cash:list')")
	public R<Map<String, Object>> get(@PathVariable String entryId, HttpServletResponse response) {
		Map<String, Object> data = cashEntryService.get(entryId);
		setEtag(response, data.get("version"));
		return R.ok(data);
	}

	@PatchMapping("/{entryId}")
	@PreAuthorize("@pms.hasPermission('mold:cash:update')")
	public R<Map<String, Object>> update(@PathVariable String entryId,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.execute("cash.update", idempotencyKey,
				"PATCH /mold/cash-entries/" + entryId + "|" + ifMatch, body, 200,
				() -> cashEntryService.update(entryId, MoldIfMatch.parse(ifMatch), body));
		markReplayed(response, outcome);
		setEtag(response, outcome.data().get("version"));
		return R.ok(outcome.data());
	}

	@PostMapping("/{entryId}/void")
	@PreAuthorize("@pms.hasPermission('mold:cash:void')")
	public R<Map<String, Object>> voidEntry(@PathVariable String entryId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) {
		Map<String, Object> payload = body == null ? Map.of() : body;
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("cash.void", idempotencyKey,
				"POST /mold/cash-entries/" + entryId + "/void", payload, 200,
				() -> cashEntryService.voidEntry(entryId, payload));
		markReplayed(response, outcome);
		setEtag(response, outcome.data().get("version"));
		return R.ok(outcome.data());
	}

	@PostMapping("/{entryId}/reverse")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("@pms.hasPermission('mold:cash:reverse')")
	public R<Map<String, Object>> reverse(@PathVariable String entryId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("cash.reverse", idempotencyKey,
				"POST /mold/cash-entries/" + entryId + "/reverse", body, 201,
				() -> cashEntryService.reverse(entryId, body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PostMapping("/{entryId}/reconcile")
	@PreAuthorize("@pms.hasPermission('mold:cash:reconcile')")
	public R<Map<String, Object>> reconcile(@PathVariable String entryId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("cash.reconcile", idempotencyKey,
				"POST /mold/cash-entries/" + entryId + "/reconcile", body, 200,
				() -> cashEntryService.reconcile(entryId, body));
		markReplayed(response, outcome);
		setEtag(response, outcome.data().get("version"));
		return R.ok(outcome.data());
	}

	@PostMapping("/{entryId}/unreconcile")
	@PreAuthorize("@pms.hasPermission('mold:cash:unreconcile')")
	public R<Map<String, Object>> unreconcile(@PathVariable String entryId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("cash.unreconcile", idempotencyKey,
				"POST /mold/cash-entries/" + entryId + "/unreconcile", body, 200,
				() -> cashEntryService.unreconcile(entryId, body));
		markReplayed(response, outcome);
		setEtag(response, outcome.data().get("version"));
		return R.ok(outcome.data());
	}

	@PostMapping("/{entryId}/corrections/preview")
	@PreAuthorize("@pms.hasPermission('mold:correction:preview')")
	public R<Map<String, Object>> previewCorrection(@PathVariable String entryId,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestBody Map<String, Object> body) {
		return R.ok(correctionService.preview(entryId, MoldIfMatch.parse(ifMatch), body));
	}

	@PostMapping("/{entryId}/corrections")
	@PreAuthorize("@pms.hasPermission('mold:correction:apply')")
	public R<Map<String, Object>> correct(@PathVariable String entryId,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("cash.correct", idempotencyKey,
				"POST /mold/cash-entries/" + entryId + "/corrections|" + ifMatch, body, 200,
				() -> correctionService.apply(entryId, MoldIfMatch.parse(ifMatch), body));
		markReplayed(response, outcome);
		setEtag(response, outcome.data().get("version"));
		return R.ok(outcome.data());
	}

	private static void markReplayed(HttpServletResponse response, IdempotencyService.Outcome outcome) {
		if (outcome.replayed()) {
			response.setHeader(REPLAYED_HEADER, "true");
		}
	}

	private static void setEtag(HttpServletResponse response, Object version) {
		MoldResponses.etag(response, version);
	}

}
