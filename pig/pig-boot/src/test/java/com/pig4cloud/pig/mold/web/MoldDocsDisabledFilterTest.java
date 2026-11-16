/*
 * M2 加固轮单测：接口文档路径一律 404。
 * 背景：springdoc 在 application-m2.yml 已关闭，但 knife4j 的 doc.html 是 jar 内静态资源，
 * 关配置后仍会返回 200；由本过滤器统一返回 404，避免留下「能打开的空文档入口」。
 */
package com.pig4cloud.pig.mold.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoldDocsDisabledFilterTest {

	@Test
	void blocksDocsPaths() {
		assertTrue(MoldDocsDisabledFilter.isDocsPath("/v3/api-docs"));
		assertTrue(MoldDocsDisabledFilter.isDocsPath("/v3/api-docs/swagger-config"));
		assertTrue(MoldDocsDisabledFilter.isDocsPath("/swagger-ui"));
		assertTrue(MoldDocsDisabledFilter.isDocsPath("/swagger-ui/index.html"));
		assertTrue(MoldDocsDisabledFilter.isDocsPath("/swagger-ui.html"));
		assertTrue(MoldDocsDisabledFilter.isDocsPath("/swagger-resources"));
		assertTrue(MoldDocsDisabledFilter.isDocsPath("/doc.html"));
	}

	@Test
	void allowsBusinessPaths() {
		assertFalse(MoldDocsDisabledFilter.isDocsPath("/mold/projects"));
		assertFalse(MoldDocsDisabledFilter.isDocsPath("/auth/login"));
		assertFalse(MoldDocsDisabledFilter.isDocsPath("/healthz"));
		// 前缀相似但不是文档路径，不能误伤
		assertFalse(MoldDocsDisabledFilter.isDocsPath("/doc.htmlx"));
		assertFalse(MoldDocsDisabledFilter.isDocsPath("/v3/api-docs-extra"));
		assertFalse(MoldDocsDisabledFilter.isDocsPath("/swagger-uix"));
		assertFalse(MoldDocsDisabledFilter.isDocsPath(""));
		assertFalse(MoldDocsDisabledFilter.isDocsPath(null));
	}

	@Test
	void returns404WithoutCallingChain() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/doc.html");
		request.setContextPath("/admin");
		request.setRequestURI("/admin/doc.html");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean chainCalled = new AtomicBoolean(false);

		new MoldDocsDisabledFilter().doFilter(request, response, (req, res) -> chainCalled.set(true));

		assertEquals(404, response.getStatus());
		assertFalse(chainCalled.get(), "文档路径不应进入下游过滤链");
	}

	@Test
	void passesBusinessRequestDownstream() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/mold/projects");
		request.setContextPath("/admin");
		request.setRequestURI("/admin/mold/projects");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean chainCalled = new AtomicBoolean(false);

		new MoldDocsDisabledFilter().doFilter(request, response, (req, res) -> chainCalled.set(true));

		assertTrue(chainCalled.get(), "业务路径必须放行");
		assertEquals(200, response.getStatus());
	}

}
