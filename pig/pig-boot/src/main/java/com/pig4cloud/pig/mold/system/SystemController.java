/*
 * 模具加工管理系统 M7-A / A4 系统接口（契约 6.10.3，2 操作）。
 *
 *   GET /mold/healthz        免认证，HTTP 200 即健康，body 走 R 信封（G-7）
 *   GET /mold/system/policy  登录态，只读策略快照
 */
package com.pig4cloud.pig.mold.system;

import com.pig4cloud.pig.common.core.util.R;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class SystemController {

	@Value("${MOLD_BUILD_VERSION:dev}")
	private String buildVersion;

	@GetMapping("/healthz")
	public R<Map<String, Object>> healthz() {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("status", "ok");
		data.put("version", buildVersion);
		return R.ok(data);
	}

	@GetMapping("/mold/system/policy")
	public R<Map<String, Object>> policy() {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("parallelStages", true);
		data.put("assignedEmployeeMayComplete", true);
		data.put("completionRequiresAllStages", true);
		data.put("completionRequiresNoOpenIssues", true);
		data.put("partialPayments", true);
		data.put("allowOverPayment", false);
		data.put("allowOverReceipt", false);
		data.put("allowOverInvoice", false);
		data.put("currencies", List.of("CNY", "USD", "EUR", "HKD"));
		data.put("closedOperations", List.of("reopenProject", "voidPurchase", "voidReceipt", "voidInvoice",
				"createPurchaseRefund", "voidCashEntry", "reverseCashEntry", "reconcileCashEntry",
				"unreconcileCashEntry"));
		data.put("allowedCashCategories", List.of("CUSTOMER_RECEIPT", "DAILY_EXPENSE", "OTHER_INCOME",
				"OTHER_EXPENSE"));
		data.put("maxUploadBytes", 10 * 1024 * 1024);
		data.put("maxAttachmentsPerOwner", 20);
		data.put("allowedMime", List.of("image/jpeg", "image/png", "image/heic", "application/pdf"));
		data.put("financialOwners", List.of("PURCHASE", "PURCHASE_RECEIPT", "PURCHASE_INVOICE", "CASH_ENTRY"));
		data.put("financialCategories", List.of("PURCHASE_ORDER", "DELIVERY_NOTE", "INVOICE", "PAYMENT_VOUCHER",
				"CONTRACT"));
		data.put("sessionDays", 7);
		data.put("idempotencyHours", 24);
		data.put("sseHeartbeatMs", 20000);
		data.put("ssePollMs", 250);
		data.put("sseMaxConnections", 3);
		data.put("eventRetentionDays", 7);
		data.put("eventMaxCount", 10000);
		return R.ok(data);
	}

}
