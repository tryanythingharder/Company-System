/*
 * M2 修复轮单测：已签发 token 的停用闸门。
 *
 * 覆盖：
 *   1) 停用账号的请求被拒为 401 UNAUTHENTICATED（不是 403，凭证失效语义）；
 *   2) 删除账号同样被拒，文案可区分；
 *   3) 正常账号放行；
 *   4) 未登录不在这里改写（交给 401 入口点）；
 *   5) 状态查询异常时放行（fail-open，避免一次数据库抖动放大成全员掉线）。
 */
package com.pig4cloud.pig.mold.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pig4cloud.pig.common.security.service.PigUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountStatusInterceptorTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void blocksLockedAccountWith401() throws Exception {
		authenticate();
		AccountStatusInterceptor interceptor = interceptor(AccountStatusGate.State.LOCKED);

		MockHttpServletResponse response = new MockHttpServletResponse();
		boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

		assertFalse(allowed, "停用账号的已签发 token 不应被放行");
		assertEquals(401, response.getStatus());

		JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
		assertEquals(401, body.path("code").asInt());
		assertEquals("UNAUTHENTICATED", body.path("data").path("errorCode").asText());
		assertTrue(body.path("msg").asText().contains("停用"), "文案应说明停用");
	}

	@Test
	void blocksDeletedAccountWithDistinctMessage() throws Exception {
		authenticate();
		AccountStatusInterceptor interceptor = interceptor(AccountStatusGate.State.DELETED);

		MockHttpServletResponse response = new MockHttpServletResponse();
		boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

		assertFalse(allowed);
		assertEquals(401, response.getStatus());

		JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
		assertTrue(body.path("msg").asText().contains("删除"), "文案应说明删除");
	}

	@Test
	void passesActiveAccount() throws Exception {
		authenticate();
		AccountStatusInterceptor interceptor = interceptor(AccountStatusGate.State.ACTIVE);

		MockHttpServletResponse response = new MockHttpServletResponse();
		boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

		assertTrue(allowed);
		assertEquals(200, response.getStatus());
	}

	@Test
	void passesUnauthenticatedRequestsToNormal401Chain() throws Exception {
		AccountStatusInterceptor interceptor = interceptor(AccountStatusGate.State.LOCKED);

		MockHttpServletResponse response = new MockHttpServletResponse();
		boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

		assertTrue(allowed, "未登录不应在这里改写成 401");
		assertEquals(200, response.getStatus());
	}

	@Test
	void failsOpenWhenStatusLookupBreaks() throws Exception {
		authenticate();
		AccountStatusGate broken = new AccountStatusGate(new JdbcTemplate()) {
			@Override
			public State bySysUserId(Long sysUserId) {
				throw new IllegalStateException("db down");
			}
		};
		AccountStatusInterceptor interceptor = new AccountStatusInterceptor(objectMapper, broken);

		MockHttpServletResponse response = new MockHttpServletResponse();
		boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

		assertTrue(allowed, "数据库异常时不应放大成全员掉线");
		assertEquals(200, response.getStatus());
	}

	private AccountStatusInterceptor interceptor(AccountStatusGate.State state) {
		AccountStatusGate gate = new AccountStatusGate(new JdbcTemplate()) {
			@Override
			public State bySysUserId(Long sysUserId) {
				return state;
			}
		};
		return new AccountStatusInterceptor(objectMapper, gate);
	}

	private void authenticate() {
		PigUser pigUser = new PigUser(7L, "tmp_lock_gate", List.of(), 1L, List.of(1L), List.of(), null, null,
				"临时账号", "临时账号", null, "not-a-real-password", true, true, "0", true, null, true, List.of());
		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken(pigUser, null, pigUser.getAuthorities()));
	}

}
