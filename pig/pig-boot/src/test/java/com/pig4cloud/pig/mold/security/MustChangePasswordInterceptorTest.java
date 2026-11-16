/*
 * M2 修复轮单测：强制改密服务端闸门（G-04）。
 * mustChangePassword=true 的账号：除 /auth/me、/auth/logout、/auth/password 外，
 * 一律 403 PASSWORD_CHANGE_REQUIRED，且错误体不能与普通 403 混。
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

class MustChangePasswordInterceptorTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void blocksBusinessCallsWithPasswordChangeRequired() throws Exception {
		MustChangePasswordInterceptor interceptor = interceptor(true);

		MockHttpServletResponse response = new MockHttpServletResponse();
		boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

		assertFalse(allowed, "强制改密账号不应被放行");
		assertEquals(403, response.getStatus());

		JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
		assertEquals(403, body.path("code").asInt());
		assertEquals("PASSWORD_CHANGE_REQUIRED", body.path("data").path("errorCode").asText());
		assertEquals("/auth/password", body.path("data").path("details").path("changePasswordPath").asText());
		JsonNode allowedPaths = body.path("data").path("details").path("allowedPaths");
		assertEquals(4, allowedPaths.size(), "白名单声明必须为 4 条");
		assertEquals("/auth/refresh", allowedPaths.path(0).asText());
		assertEquals("/auth/me", allowedPaths.path(1).asText());
		assertEquals("/auth/logout", allowedPaths.path(2).asText());
		assertEquals("/auth/password", allowedPaths.path(3).asText());
		assertFalse(allowedPaths.toString().contains("/auth/login"), "未登录入口不属于闸门白名单");
	}

	@Test
	void passesWhenPasswordAlreadyChanged() throws Exception {
		MustChangePasswordInterceptor interceptor = interceptor(false);

		MockHttpServletResponse response = new MockHttpServletResponse();
		boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

		assertTrue(allowed);
		assertEquals(200, response.getStatus());
	}

	@Test
	void passesUnauthenticatedRequestsToNormal401Chain() throws Exception {
		MustChangePasswordInterceptor interceptor = new MustChangePasswordInterceptor(objectMapper, resolver(true));

		MockHttpServletResponse response = new MockHttpServletResponse();
		boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

		assertTrue(allowed, "未登录不应在这里改写成 403");
		assertEquals(200, response.getStatus());
	}

	private MustChangePasswordInterceptor interceptor(boolean mustChangePassword) {
		authenticate();
		return new MustChangePasswordInterceptor(objectMapper, resolver(mustChangePassword));
	}

	private void authenticate() {
		PigUser pigUser = new PigUser(7L, "m2_slice4_pwd", List.of(), 1L, List.of(1L), List.of(), null, null,
				"临时账号", "临时账号", null, "not-a-real-password", true, true, "0", true, null, true, List.of());
		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken(pigUser, null, pigUser.getAuthorities()));
	}

	private MoldActorResolver resolver(boolean mustChangePassword) {
		MoldActor actor = new MoldActor("u-1", 7L, "m2_slice4_pwd", "临时账号", "ACTIVE", 1, mustChangePassword,
				List.of("FINANCE"));
		return new MoldActorResolver(new JdbcTemplate()) {
			@Override
			public MoldActor bySysUserId(Long sysUserId) {
				return actor;
			}

			@Override
			public MoldActor current() {
				return actor;
			}
		};
	}

}
