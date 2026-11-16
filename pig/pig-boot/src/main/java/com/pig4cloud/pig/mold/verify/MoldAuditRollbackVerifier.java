/*
 * 模具加工管理系统 M2 后端切片
 * 仅用于 M2 完成标准验证：证明“业务回滚时审计也回滚”。
 * 只有激活 Spring profile `m2verify` 时才注册，生产环境不得启用。
 *
 * 两个用例：
 *   1) PATCH 项目后强制回滚（业务表与 audit_logs 均不落库）；
 *   2) 新建账号后强制回滚（users / sys_user / user_roles 与 audit_logs 均不落库）。
 */
package com.pig4cloud.pig.mold.verify;

import com.pig4cloud.pig.common.core.util.R;
import com.pig4cloud.pig.mold.finance.CashEntryService;
import com.pig4cloud.pig.mold.finance.CorrectionService;
import com.pig4cloud.pig.mold.finance.CounterpartyService;
import com.pig4cloud.pig.mold.finance.PurchaseService;
import com.pig4cloud.pig.mold.project.ProjectService;
import com.pig4cloud.pig.mold.user.UserService;
import com.pig4cloud.pig.mold.web.MoldApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Profile("m2verify")
@RestController
@RequiredArgsConstructor
@RequestMapping("/mold/_verify")
public class MoldAuditRollbackVerifier {

	private final ProjectService projectService;

	private final UserService userService;

	private final CounterpartyService counterpartyService;

	private final PurchaseService purchaseService;

	private final CashEntryService cashEntryService;

	private final CorrectionService correctionService;

	private final PlatformTransactionManager transactionManager;

	private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

	@PostMapping("/audit-rollback/{projectId}")
	public R<Map<String, Object>> auditRollback(@PathVariable String projectId,
			@RequestBody Map<String, Object> body) {
		Map<String, Object> before = projectService.get(projectId);
		int version = ((Number) before.get("version")).intValue();
		Object ifMatchVersion = body.get("ifMatch");
		int expected = ifMatchVersion == null ? version : ((Number) ifMatchVersion).intValue();

		Map<String, Object> payload = new LinkedHashMap<>(body);
		payload.remove("ifMatch");

		TransactionTemplate template = new TransactionTemplate(transactionManager);
		boolean rolledBack = false;
		try {
			template.executeWithoutResult(status -> {
				projectService.update(projectId, expected, payload);
				throw new ForcedRollback("m2verify: forced rollback after business write + audit");
			});
		}
		catch (ForcedRollback expectedEx) {
			rolledBack = true;
		}

		Map<String, Object> after = projectService.get(projectId);
		if (!rolledBack) {
			throw new MoldApiException(500, "INTERNAL_ERROR", "验证事务未按预期回滚");
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("rolledBack", true);
		result.put("versionBefore", before.get("version"));
		result.put("versionAfter", after.get("version"));
		result.put("nameBefore", before.get("name"));
		result.put("nameAfter", after.get("name"));
		return R.ok(result);
	}

	/**
	 * 新建账号 + 审计写入后强制回滚；返回前后计数，证明 accounts / audit_logs 都没有新增。
	 */
	@PostMapping("/user-create-rollback")
	public R<Map<String, Object>> userCreateRollback() {
		long usersBefore = count("users");
		long sysUsersBefore = count("sys_user");
		long userRolesBefore = count("user_roles");
		long auditBefore = count("audit_logs");
		String loginName = "m2rb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("loginName", loginName);
		payload.put("displayName", "回滚验证账号");
		payload.put("password", "M2Rollback!2026x");
		payload.put("roles", List.of("VIEWER"));

		TransactionTemplate template = new TransactionTemplate(transactionManager);
		boolean rolledBack = false;
		try {
			template.executeWithoutResult(status -> {
				userService.create(payload);
				throw new ForcedRollback("m2verify: forced rollback after user create + audit");
			});
		}
		catch (ForcedRollback expectedEx) {
			rolledBack = true;
		}

		long usersAfter = count("users");
		long sysUsersAfter = count("sys_user");
		long userRolesAfter = count("user_roles");
		long auditAfter = count("audit_logs");
		if (!rolledBack || usersBefore != usersAfter || sysUsersBefore != sysUsersAfter
				|| userRolesBefore != userRolesAfter || auditBefore != auditAfter) {
			throw new MoldApiException(500, "INTERNAL_ERROR", "验证事务未按预期回滚");
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("rolledBack", true);
		result.put("loginName", loginName);
		result.put("usersBefore", usersBefore);
		result.put("usersAfter", usersAfter);
		result.put("sysUsersBefore", sysUsersBefore);
		result.put("sysUsersAfter", sysUsersAfter);
		result.put("userRolesBefore", userRolesBefore);
		result.put("userRolesAfter", userRolesAfter);
		result.put("auditBefore", auditBefore);
		result.put("auditAfter", auditAfter);
		return R.ok(result);
	}

	/**
	 * 新建往来单位 + 审计 + 事件后强制回滚：证明三张表都没有新增。
	 */
	@PostMapping("/counterparty-create-rollback")
	public R<Map<String, Object>> counterpartyCreateRollback() {
		long counterpartiesBefore = count("counterparties");
		long auditBefore = count("audit_logs");
		long outboxBefore = count("event_outbox");
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("name", "回滚验证供应商-" + suffix);
		payload.put("type", "SUPPLIER");
		payload.put("contact", "回滚验证");

		TransactionTemplate template = new TransactionTemplate(transactionManager);
		boolean rolledBack = false;
		try {
			template.executeWithoutResult(status -> {
				counterpartyService.create(payload);
				throw new ForcedRollback("m2verify: forced rollback after counterparty create + audit + event");
			});
		}
		catch (ForcedRollback expectedEx) {
			rolledBack = true;
		}

		long counterpartiesAfter = count("counterparties");
		long auditAfter = count("audit_logs");
		long outboxAfter = count("event_outbox");
		if (!rolledBack || counterpartiesBefore != counterpartiesAfter || auditBefore != auditAfter
				|| outboxBefore != outboxAfter) {
			throw new MoldApiException(500, "INTERNAL_ERROR", "验证事务未按预期回滚");
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("rolledBack", true);
		result.put("counterpartiesBefore", counterpartiesBefore);
		result.put("counterpartiesAfter", counterpartiesAfter);
		result.put("auditBefore", auditBefore);
		result.put("auditAfter", auditAfter);
		result.put("outboxBefore", outboxBefore);
		result.put("outboxAfter", outboxAfter);
		return R.ok(result);
	}

	/**
	 * 新建采购单 + 明细 + 审计 + 事件后强制回滚：证明 purchases / purchase_items 等均无新增。
	 */
	@PostMapping("/purchase-create-rollback")
	public R<Map<String, Object>> purchaseCreateRollback(@RequestBody Map<String, Object> body) {
		long purchasesBefore = count("purchases");
		long itemsBefore = count("purchase_items");
		long auditBefore = count("audit_logs");
		long outboxBefore = count("event_outbox");

		TransactionTemplate template = new TransactionTemplate(transactionManager);
		boolean rolledBack = false;
		try {
			template.executeWithoutResult(status -> {
				purchaseService.create(body);
				throw new ForcedRollback("m2verify: forced rollback after purchase create + items + audit + event");
			});
		}
		catch (ForcedRollback expectedEx) {
			rolledBack = true;
		}

		long purchasesAfter = count("purchases");
		long itemsAfter = count("purchase_items");
		long auditAfter = count("audit_logs");
		long outboxAfter = count("event_outbox");
		if (!rolledBack || purchasesBefore != purchasesAfter || itemsBefore != itemsAfter || auditBefore != auditAfter
				|| outboxBefore != outboxAfter) {
			throw new MoldApiException(500, "INTERNAL_ERROR", "验证事务未按预期回滚");
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("rolledBack", true);
		result.put("purchasesBefore", purchasesBefore);
		result.put("purchasesAfter", purchasesAfter);
		result.put("itemsBefore", itemsBefore);
		result.put("itemsAfter", itemsAfter);
		result.put("auditBefore", auditBefore);
		result.put("auditAfter", auditAfter);
		result.put("outboxBefore", outboxBefore);
		result.put("outboxAfter", outboxAfter);
		return R.ok(result);
	}

	/**
	 * 新建流水 + 审计 + 事件后强制回滚。
	 */
	@PostMapping("/cash-create-rollback")
	public R<Map<String, Object>> cashCreateRollback(@RequestBody Map<String, Object> body) {
		long entriesBefore = count("cash_entries");
		long auditBefore = count("audit_logs");
		long outboxBefore = count("event_outbox");

		TransactionTemplate template = new TransactionTemplate(transactionManager);
		boolean rolledBack = false;
		try {
			template.executeWithoutResult(status -> {
				cashEntryService.create(body);
				throw new ForcedRollback("m2verify: forced rollback after cash entry create + audit + event");
			});
		}
		catch (ForcedRollback expectedEx) {
			rolledBack = true;
		}

		long entriesAfter = count("cash_entries");
		long auditAfter = count("audit_logs");
		long outboxAfter = count("event_outbox");
		if (!rolledBack || entriesBefore != entriesAfter || auditBefore != auditAfter
				|| outboxBefore != outboxAfter) {
			throw new MoldApiException(500, "INTERNAL_ERROR", "验证事务未按预期回滚");
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("rolledBack", true);
		result.put("entriesBefore", entriesBefore);
		result.put("entriesAfter", entriesAfter);
		result.put("auditBefore", auditBefore);
		result.put("auditAfter", auditAfter);
		result.put("outboxBefore", outboxBefore);
		result.put("outboxAfter", outboxAfter);
		return R.ok(result);
	}

	/**
	 * 更正（B1 作废）+ 审计 + 事件后强制回滚：原件版本与状态保持不变。
	 */
	@PostMapping("/cash-correct-rollback")
	public R<Map<String, Object>> cashCorrectRollback(@RequestBody Map<String, Object> body) {
		String entryId = String.valueOf(body.get("entryId"));
		Map<String, Object> before = jdbcTemplate.queryForMap(
				"SELECT status, version FROM cash_entries WHERE id = ?", entryId);
		long entriesBefore = count("cash_entries");
		long auditBefore = count("audit_logs");
		long outboxBefore = count("event_outbox");
		int version = ((Number) before.get("version")).intValue();

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("reason", body.getOrDefault("reason", "m2verify 回滚验证"));

		TransactionTemplate template = new TransactionTemplate(transactionManager);
		boolean rolledBack = false;
		try {
			template.executeWithoutResult(status -> {
				correctionService.apply(entryId, version, payload);
				throw new ForcedRollback("m2verify: forced rollback after correction + audit + event");
			});
		}
		catch (ForcedRollback expectedEx) {
			rolledBack = true;
		}

		Map<String, Object> after = jdbcTemplate.queryForMap(
				"SELECT status, version FROM cash_entries WHERE id = ?", entryId);
		long entriesAfter = count("cash_entries");
		long auditAfter = count("audit_logs");
		long outboxAfter = count("event_outbox");
		if (!rolledBack || entriesBefore != entriesAfter || auditBefore != auditAfter
				|| outboxBefore != outboxAfter || !before.equals(after)) {
			throw new MoldApiException(500, "INTERNAL_ERROR", "验证事务未按预期回滚");
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("rolledBack", true);
		result.put("statusBefore", before.get("status"));
		result.put("statusAfter", after.get("status"));
		result.put("versionBefore", before.get("version"));
		result.put("versionAfter", after.get("version"));
		result.put("entriesBefore", entriesBefore);
		result.put("entriesAfter", entriesAfter);
		result.put("auditBefore", auditBefore);
		result.put("auditAfter", auditAfter);
		result.put("outboxBefore", outboxBefore);
		result.put("outboxAfter", outboxAfter);
		return R.ok(result);
	}

	private long count(String table) {
		Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
		return value == null ? 0L : value;
	}

	private static class ForcedRollback extends RuntimeException {

		ForcedRollback(String message) {
			super(message);
		}

	}

}
