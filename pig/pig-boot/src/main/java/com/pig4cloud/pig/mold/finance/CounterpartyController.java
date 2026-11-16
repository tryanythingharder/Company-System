/*
 * 模具加工管理系统 M7-A 往来单位接口（契约 6.8）。
 */
package com.pig4cloud.pig.mold.finance;

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
@RequestMapping("/mold/counterparties")
public class CounterpartyController {

	private static final String REPLAYED_HEADER = "Idempotent-Replayed";

	private final CounterpartyService counterpartyService;

	private final IdempotencyService idempotencyService;

	@GetMapping
	@PreAuthorize("@pms.hasPermission('mold:purchase:list')")
	public R<Map<String, Object>> list(@RequestParam(required = false) String pageNum,
			@RequestParam(required = false) String pageSize, @RequestParam(required = false) String sort,
			@RequestParam(required = false) String order, @RequestParam(required = false) String type) {
		PageQuery page = PageQuery.parse(pageNum, pageSize, sort, order, CounterpartyService.SORTABLE, "name");
		return R.ok(counterpartyService.list(page, type));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("@pms.hasPermission('mold:counterparty:create')")
	public R<Map<String, Object>> create(
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("counterparty.create", idempotencyKey,
				"POST /mold/counterparties", body, 201, () -> counterpartyService.create(body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PatchMapping("/{counterpartyId}")
	@PreAuthorize("@pms.hasPermission('mold:counterparty:update')")
	public R<Map<String, Object>> update(@PathVariable String counterpartyId,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.execute("counterparty.update", idempotencyKey,
				"PATCH /mold/counterparties/" + counterpartyId + "|" + ifMatch, body, 200,
				() -> counterpartyService.update(counterpartyId, MoldIfMatch.parse(ifMatch), body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	private static void markReplayed(HttpServletResponse response, IdempotencyService.Outcome outcome) {
		if (outcome.replayed()) {
			response.setHeader(REPLAYED_HEADER, "true");
		}
	}

}
