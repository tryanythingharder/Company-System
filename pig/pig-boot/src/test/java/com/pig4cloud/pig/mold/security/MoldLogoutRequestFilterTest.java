/*
 * M2 修复轮单测：注销固定 200 的前置过滤器。
 * 断言 token 被移到请求属性，且下游看不到 Authorization 头，
 * 这样已吊销 token 的重复注销不会在安全过滤器里变成 401。
 */
package com.pig4cloud.pig.mold.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MoldLogoutRequestFilterTest {

	@Test
	void movesTokenToAttributeAndHidesHeader() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/logout");
		request.addHeader("Authorization", "Bearer token-abc");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<HttpServletRequest> downstream = new AtomicReference<>();

		new MoldLogoutRequestFilter().doFilter(request, response,
				(req, res) -> downstream.set((HttpServletRequest) req));

		assertEquals("token-abc", request.getAttribute(MoldLogoutRequestFilter.TOKEN_ATTRIBUTE));
		assertNull(downstream.get().getHeader("Authorization"));
		assertNull(downstream.get().getHeader("authorization"));
		assertEquals(0, Collections.list(downstream.get().getHeaderNames())
			.stream()
			.filter(name -> "Authorization".equalsIgnoreCase(name))
			.count());
	}

	@Test
	void leavesRequestsWithoutTokenAlone() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/logout");
		MockHttpServletResponse response = new MockHttpServletResponse();

		new MoldLogoutRequestFilter().doFilter(request, response, (req, res) -> {
		});

		assertNull(request.getAttribute(MoldLogoutRequestFilter.TOKEN_ATTRIBUTE));
	}

}
