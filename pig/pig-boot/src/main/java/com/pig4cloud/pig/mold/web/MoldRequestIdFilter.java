/*
 * 模具加工管理系统 M2 后端切片
 * 请求级 requestId：优先复用 X-Request-Id，否则生成 req_ 前缀的随机串。
 * 该值同时写入响应头、错误体、audit_logs.request_id，以及日志 MDC（键 requestId），
 * 便于按一次请求串起 nginx 访问日志、应用日志与审计记录。
 * 外部传入值只接受 [A-Za-z0-9_.-]{1,64}，防日志注入。
 */
package com.pig4cloud.pig.mold.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MoldRequestIdFilter extends OncePerRequestFilter {

	public static final String ATTRIBUTE = "mold.requestId";

	public static final String HEADER = "X-Request-Id";

	public static final String MDC_KEY = "requestId";

	private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String requestId = request.getHeader(HEADER);
		if (requestId == null || !SAFE.matcher(requestId).matches()) {
			requestId = "req_" + UUID.randomUUID().toString().replace("-", "");
		}
		request.setAttribute(ATTRIBUTE, requestId);
		response.setHeader(HEADER, requestId);
		MDC.put(MDC_KEY, requestId);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}

}
