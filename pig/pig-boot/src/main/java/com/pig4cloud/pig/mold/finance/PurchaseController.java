/*
 * 模具加工管理系统 M7-A 采购三段式接口（契约 6.8 的 16 个采购操作）。
 *
 * 写接口按契约标注幂等键（必填的由 IdempotencyService 校验，可选的缺省时直接执行），
 * PATCH 必须带 If-Match。
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
import org.springframework.util.StringUtils;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mold")
public class PurchaseController {

	private static final String REPLAYED_HEADER = "Idempotent-Replayed";

	private final PurchaseService purchaseService;

	private final IdempotencyService idempotencyService;

	// ------------------------------------------------------------------ 采购单

	@GetMapping("/purchases")
	@PreAuthorize("@pms.hasPermission('mold:purchase:list')")
	public R<Map<String, Object>> list(@RequestParam(required = false) String pageNum,
			@RequestParam(required = false) String pageSize, @RequestParam(required = false) String sort,
			@RequestParam(required = false) String order, @RequestParam(required = false) String projectId,
			@RequestParam(required = false) String counterpartyId, @RequestParam(required = false) String status,
			@RequestParam(required = false) String allocationStatus, @RequestParam(required = false) String currency,
			@RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
			@RequestParam(required = false) String q) {
		PageQuery page = PageQuery.parse(pageNum, pageSize, sort, order, PurchaseService.SORTABLE, "purchaseDate");
		// 旧契约默认 purchaseDate DESC；PageQuery 只在 sort 缺省时补 asc，这里按采购口径纠正。
		if (!StringUtils.hasText(sort) && !StringUtils.hasText(order)) {
			page = new PageQuery(page.pageNum(), page.pageSize(), page.sort(), "desc");
		}
		return R.ok(purchaseService.list(page, projectId, counterpartyId, status, allocationStatus, currency, dateFrom,
				dateTo, q));
	}

	@PostMapping("/purchases")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("@pms.hasPermission('mold:purchase:create')")
	public R<Map<String, Object>> create(
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("purchase.create", idempotencyKey,
				"POST /mold/purchases", body, 201, () -> purchaseService.create(body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@GetMapping("/purchases/{purchaseId}")
	@PreAuthorize("@pms.hasPermission('mold:purchase:list')")
	public R<Map<String, Object>> get(@PathVariable String purchaseId, HttpServletResponse response) {
		Map<String, Object> data = purchaseService.get(purchaseId);
		MoldResponses.etag(response, data.get("version"));
		return R.ok(data);
	}

	@PatchMapping("/purchases/{purchaseId}")
	@PreAuthorize("@pms.hasPermission('mold:purchase:update')")
	public R<Map<String, Object>> update(@PathVariable String purchaseId,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.execute("purchase.update", idempotencyKey,
				"PATCH /mold/purchases/" + purchaseId + "|" + ifMatch, body, 200,
				() -> purchaseService.update(purchaseId, MoldIfMatch.parse(ifMatch), body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PostMapping("/purchases/{purchaseId}/confirm")
	@PreAuthorize("@pms.hasPermission('mold:purchase:confirm')")
	public R<Map<String, Object>> confirm(@PathVariable String purchaseId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("purchase.confirm", idempotencyKey,
				"POST /mold/purchases/" + purchaseId + "/confirm", null, 200,
				() -> purchaseService.confirm(purchaseId));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PostMapping("/purchases/{purchaseId}/void")
	@PreAuthorize("@pms.hasPermission('mold:purchase:void')")
	public R<Map<String, Object>> voidPurchase(@PathVariable String purchaseId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) {
		Map<String, Object> payload = body == null ? Map.of() : body;
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("purchase.void", idempotencyKey,
				"POST /mold/purchases/" + purchaseId + "/void", payload, 200,
				() -> purchaseService.voidPurchase(purchaseId, payload));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@GetMapping("/purchases/{purchaseId}/summary")
	@PreAuthorize("@pms.hasPermission('mold:purchase:list')")
	public R<Map<String, Object>> summary(@PathVariable String purchaseId) {
		return R.ok(purchaseService.summary(purchaseId));
	}

	// ------------------------------------------------------------------ 收货

	@GetMapping("/purchases/{purchaseId}/receipts")
	@PreAuthorize("@pms.hasPermission('mold:purchase:list')")
	public R<Map<String, Object>> listReceipts(@PathVariable String purchaseId) {
		return R.ok(purchaseService.listReceipts(purchaseId));
	}

	@PostMapping("/purchases/{purchaseId}/receipts")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("@pms.hasPermission('mold:receipt:create')")
	public R<Map<String, Object>> createReceipt(@PathVariable String purchaseId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("receipt.create", idempotencyKey,
				"POST /mold/purchases/" + purchaseId + "/receipts", body, 201,
				() -> purchaseService.createReceipt(purchaseId, body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PostMapping("/receipts/{receiptId}/void")
	@PreAuthorize("@pms.hasPermission('mold:receipt:void')")
	public R<Map<String, Object>> voidReceipt(@PathVariable String receiptId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) {
		Map<String, Object> payload = body == null ? Map.of() : body;
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("receipt.void", idempotencyKey,
				"POST /mold/receipts/" + receiptId + "/void", payload, 200,
				() -> purchaseService.voidReceipt(receiptId, payload));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	// ------------------------------------------------------------------ 发票

	@GetMapping("/purchases/{purchaseId}/invoices")
	@PreAuthorize("@pms.hasPermission('mold:purchase:list')")
	public R<Map<String, Object>> listInvoices(@PathVariable String purchaseId) {
		return R.ok(purchaseService.listInvoices(purchaseId));
	}

	@PostMapping("/purchases/{purchaseId}/invoices")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("@pms.hasPermission('mold:invoice:create')")
	public R<Map<String, Object>> createInvoice(@PathVariable String purchaseId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("invoice.create", idempotencyKey,
				"POST /mold/purchases/" + purchaseId + "/invoices", body, 201,
				() -> purchaseService.createInvoice(purchaseId, body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PostMapping("/invoices/{invoiceId}/void")
	@PreAuthorize("@pms.hasPermission('mold:invoice:void')")
	public R<Map<String, Object>> voidInvoice(@PathVariable String invoiceId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) {
		Map<String, Object> payload = body == null ? Map.of() : body;
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("invoice.void", idempotencyKey,
				"POST /mold/invoices/" + invoiceId + "/void", payload, 200,
				() -> purchaseService.voidInvoice(invoiceId, payload));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	// ------------------------------------------------------------------ 付款 / 退款

	@GetMapping("/purchases/{purchaseId}/payments")
	@PreAuthorize("@pms.hasPermission('mold:cash:list')")
	public R<Map<String, Object>> listPayments(@PathVariable String purchaseId) {
		return R.ok(purchaseService.listPayments(purchaseId));
	}

	@PostMapping("/purchases/{purchaseId}/payments")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("@pms.hasPermission('mold:cash:create')")
	public R<Map<String, Object>> createPayment(@PathVariable String purchaseId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("purchase.payment", idempotencyKey,
				"POST /mold/purchases/" + purchaseId + "/payments", body, 201,
				() -> purchaseService.createPayment(purchaseId, body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PostMapping("/purchases/{purchaseId}/refunds")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("@pms.hasPermission('mold:cash:create')")
	public R<Map<String, Object>> createRefund(@PathVariable String purchaseId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("purchase.refund", idempotencyKey,
				"POST /mold/purchases/" + purchaseId + "/refunds", body, 201,
				() -> purchaseService.createRefund(purchaseId, body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	private static void markReplayed(HttpServletResponse response, IdempotencyService.Outcome outcome) {
		if (outcome.replayed()) {
			response.setHeader(REPLAYED_HEADER, "true");
		}
	}

}
