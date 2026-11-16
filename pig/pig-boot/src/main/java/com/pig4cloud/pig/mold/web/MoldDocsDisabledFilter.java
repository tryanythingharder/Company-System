/*
 * 模具加工管理系统 M2 加固轮
 * 接口文档路径一律 404：
 *  - springdoc 的 /v3/api-docs、/swagger-ui/** 在 application-m2.yml 已关闭；
 *  - 但 knife4j 的 doc.html 是打进 jar 的静态资源（META-INF/resources/doc.html），
 *    关 springdoc 不会让它消失，实测仍返回 200，等于留着一个能加载空文档的入口；
 *  - 因此在这里对文档路径统一返回 404，语义上等同「该资源不存在」，
 *    也避免返回 403 反而暴露「这里有东西只是被禁止」。
 *
 * 只拦文档路径，不影响任何业务接口；order 置于安全过滤器之前，
 * 未登录访问也直接 404，不会先弹 401。
 */
package com.pig4cloud.pig.mold.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class MoldDocsDisabledFilter extends OncePerRequestFilter {

	/** 需要 404 的文档前缀（servlet 路径，不含 context-path）。 */
	private static final List<String> BLOCKED_PREFIXES = List.of("/v3/api-docs", "/swagger-ui", "/swagger-resources");

	private static final List<String> BLOCKED_EXACT = List.of("/doc.html", "/swagger-ui.html");

	/** 判断去掉 context-path 后的请求路径是否属于接口文档。 */
	static boolean isDocsPath(String path) {
		if (path == null || path.isEmpty()) {
			return false;
		}
		if (BLOCKED_EXACT.contains(path)) {
			return true;
		}
		for (String prefix : BLOCKED_PREFIXES) {
			if (path.equals(prefix) || path.startsWith(prefix + "/")) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String uri = request.getRequestURI();
		String contextPath = request.getContextPath();
		String path = contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
				? uri.substring(contextPath.length()) : uri;
		if (isDocsPath(path)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		filterChain.doFilter(request, response);
	}

}
