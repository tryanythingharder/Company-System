/*
 * 模具加工管理系统 M2 后端切片
 * G2 账号与角色接口（契约 6.4）：
 *   GET    /mold/users                          mold:user:list（仅 OWNER）
 *   GET    /mold/users/{userId}                 mold:user:list（仅 OWNER）
 *   POST   /mold/users                          mold:user:create
 *   PATCH  /mold/users/{userId}                 mold:user:update（改名 / 启停 / 手机号）
 *   PUT    /mold/users/{userId}/roles           mold:user:role
 *   POST   /mold/users/{userId}/password        mold:user:password-reset
 *   GET    /mold/directory/users                mold:directory:list
 *   GET    /mold/system/role-capabilities       登录态（只返回调用者自己的角色）
 *
 * 任何出参都不含密码或哈希；一次性临时密码只在重置响应中出现一次。
 */
package com.pig4cloud.pig.mold.user;

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
import org.springframework.web.bind.annotation.PutMapping;
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
public class UserController {

	private final UserService userService;

	private final IdempotencyService idempotencyService;

	@GetMapping("/users")
	@PreAuthorize("@pms.hasPermission('mold:user:list')")
	public R<Map<String, Object>> list(@RequestParam(required = false) String pageNum,
			@RequestParam(required = false) String pageSize, @RequestParam(required = false) String sort,
			@RequestParam(required = false) String order, @RequestParam(required = false) String status,
			@RequestParam(required = false) String keyword) {
		PageQuery page = PageQuery.parse(pageNum, pageSize, sort, order, UserService.SORTABLE, "loginName");
		return R.ok(userService.list(page, status, keyword));
	}

	@GetMapping("/users/{userId}")
	@PreAuthorize("@pms.hasPermission('mold:user:list')")
	public R<Map<String, Object>> detail(@PathVariable String userId, HttpServletResponse response) {
		Map<String, Object> data = userService.get(userId);
		MoldResponses.etag(response, data.get("version"));
		return R.ok(data);
	}

	@PostMapping("/users")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("@pms.hasPermission('mold:user:create')")
	public R<Map<String, Object>> create(
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("user.create", idempotencyKey,
				"POST /mold/users", body, 201, () -> userService.create(body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PatchMapping("/users/{userId}")
	@PreAuthorize("@pms.hasPermission('mold:user:update')")
	public R<Map<String, Object>> update(@PathVariable String userId,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.execute("user.update", idempotencyKey,
				"PATCH /mold/users/" + userId + "|" + ifMatch, body, 200,
				() -> userService.update(userId, MoldIfMatch.parse(ifMatch), body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PutMapping("/users/{userId}/roles")
	@PreAuthorize("@pms.hasPermission('mold:user:role')")
	public R<Map<String, Object>> setRoles(@PathVariable String userId,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body, HttpServletResponse response) {
		IdempotencyService.Outcome outcome = idempotencyService.execute("user.roles", idempotencyKey,
				"PUT /mold/users/" + userId + "/roles|" + ifMatch, body, 200,
				() -> userService.setRoles(userId, MoldIfMatch.parse(ifMatch), body));
		markReplayed(response, outcome);
		return R.ok(outcome.data());
	}

	@PostMapping("/users/{userId}/password")
	@PreAuthorize("@pms.hasPermission('mold:user:password-reset')")
	public R<Map<String, Object>> resetPassword(@PathVariable String userId,
			@RequestBody(required = false) Map<String, Object> body) {
		return R.ok(userService.resetPassword(userId, body));
	}

	@GetMapping("/directory/users")
	@PreAuthorize("@pms.hasPermission('mold:directory:list')")
	public R<Map<String, Object>> directory(@RequestParam(required = false) String keyword) {
		return R.ok(userService.directory(keyword));
	}

	@GetMapping("/system/role-capabilities")
	public R<Map<String, Object>> roleCapabilities() {
		return R.ok(userService.roleCapabilities());
	}

	private static void markReplayed(HttpServletResponse response, IdempotencyService.Outcome outcome) {
		if (outcome.replayed()) {
			response.setHeader("Idempotent-Replayed", "true");
		}
	}

}
