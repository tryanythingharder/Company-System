/*
 * 模具加工管理系统 M2 后端切片
 * 请求级 requestId：优先复用 X-Request-Id，否则生成 req_ 前缀的随机串。
 * 该值同时写入响应头、错误体与 audit_logs.request_id。
 */
package com.pig4cloud.pig.mold.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MoldRequestIdFilter extends OncePerRequestFilter {

	public static final String ATTRIBUTE = "mold.requestId";

	public static final String HEADER = "X-Request-Id";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String requestId = request.getHeader(HEADER);
		if (!StringUtils.hasText(requestId) || requestId.length() > 64) {
			requestId = "req_" + UUID.randomUUID().toString().replace("-", "");
		}
		request.setAttribute(ATTRIBUTE, requestId);
		response.setHeader(HEADER, requestId);
		filterChain.doFilter(request, response);
	}

}
