/*
 * 模具加工管理系统 M2 修复轮
 * 注销固定口径：/auth/logout 永远 200（不因 token 已吊销/格式非法变成 401/500）。
 *
 * 背景：Pig 的资源服务器过滤器会解析 Authorization 头；已吊销或非法的 token
 * 会在进入控制器前被 401 拦截，导致同一 token 第一次 200、第二次 401。
 * 本过滤器在 Spring Security 过滤器链之前（order = MIN_VALUE）把 token 移到
 * 请求属性并隐藏 Authorization 头，控制器再从属性中取 token 尽力吊销。
 */
package com.pig4cloud.pig.mold.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;

public class MoldLogoutRequestFilter extends OncePerRequestFilter {

	/** 控制器读取的请求属性名。 */
	public static final String TOKEN_ATTRIBUTE = "mold.logout.token";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			String token = header.substring(7).trim();
			if (!token.isEmpty()) {
				request.setAttribute(TOKEN_ATTRIBUTE, token);
			}
		}
		chain.doFilter(new AuthorizationHiddenRequest(request), response);
	}

	/**
	 * 对下游隐藏 Authorization 头，避免资源服务器把已吊销 token 判成 401。
	 */
	private static final class AuthorizationHiddenRequest extends HttpServletRequestWrapper {

		private AuthorizationHiddenRequest(HttpServletRequest request) {
			super(request);
		}

		@Override
		public String getHeader(String name) {
			if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
				return null;
			}
			return super.getHeader(name);
		}

		@Override
		public Enumeration<String> getHeaders(String name) {
			if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
				return Collections.emptyEnumeration();
			}
			return super.getHeaders(name);
		}

		@Override
		public Enumeration<String> getHeaderNames() {
			Enumeration<String> names = super.getHeaderNames();
			if (names == null) {
				return Collections.emptyEnumeration();
			}
			return Collections.enumeration(Collections.list(names)
				.stream()
				.filter(name -> !HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name))
				.toList());
		}

	}

}
