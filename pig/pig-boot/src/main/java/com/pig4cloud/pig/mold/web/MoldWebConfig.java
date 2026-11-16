/*
 * 模具加工管理系统 M2 后端切片
 * M2 自有 Web 组件注册：
 *  - 401 入口点（@Primary 覆盖 Pig 默认 424 行为）；
 *  - 强制改密闸门（在方法级 @PreAuthorize 之前拦截）。
 */
package com.pig4cloud.pig.mold.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pig4cloud.pig.common.security.component.ResourceAuthExceptionEntryPoint;
import com.pig4cloud.pig.mold.security.AccountStatusInterceptor;
import com.pig4cloud.pig.mold.security.MoldLogoutRequestFilter;
import com.pig4cloud.pig.mold.security.MustChangePasswordInterceptor;
import jakarta.servlet.Filter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class MoldWebConfig implements WebMvcConfigurer {

	private final MustChangePasswordInterceptor mustChangePasswordInterceptor;

	private final AccountStatusInterceptor accountStatusInterceptor;

	@Bean
	@Primary
	public ResourceAuthExceptionEntryPoint moldResourceAuthExceptionEntryPoint(ObjectMapper objectMapper,
			MessageSource messageSource) {
		return new MoldAuthEntryPoint(objectMapper, messageSource);
	}

	/**
	 * 注销路径在安全过滤器之前摘掉 Authorization 头，保证重复注销固定 200。
	 */
	@Bean
	public FilterRegistrationBean<Filter> moldLogoutRequestFilter() {
		FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(new MoldLogoutRequestFilter());
		registration.addUrlPatterns("/auth/logout");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

	/**
	 * 接口文档一律 404：springdoc 已在 application-m2.yml 关闭，knife4j 的 doc.html
	 * 是 jar 内静态资源，需在此兜底。置于最高优先级，未登录也直接 404 而非先 401。
	 */
	@Bean
	public FilterRegistrationBean<Filter> moldDocsDisabledFilter() {
		FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(new MoldDocsDisabledFilter());
		registration.addUrlPatterns("/v3/api-docs", "/v3/api-docs/*", "/swagger-ui", "/swagger-ui/*", "/swagger-ui.html",
				"/swagger-resources", "/swagger-resources/*", "/doc.html");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

	/**
	 * 强制改密闸门：excludePathPatterns 中 /auth/login 是未登录入口，其余 4 条为声明白名单
	 * （/auth/refresh、/auth/me、/auth/logout、/auth/password）。
	 * /auth/refresh 放行理由：只换发令牌、不触业务；业务接口仍 403 PASSWORD_CHANGE_REQUIRED。
	 */
	/**
	 * 账号状态闸门（停用/删除）排在强制改密之前：凭证已失效时不再提示改密。
	 * 只放行 /auth/login（无身份）与 /auth/logout（只吊销 token）；
	 * /auth/refresh 不放行，避免停用账号用刷新令牌续命。
	 */
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(accountStatusInterceptor)
			.addPathPatterns("/mold/**", "/auth/**")
			.excludePathPatterns("/auth/login", "/auth/logout");
		registry.addInterceptor(mustChangePasswordInterceptor)
			.addPathPatterns("/mold/**", "/auth/**")
			.excludePathPatterns("/auth/login", "/auth/refresh", "/auth/me", "/auth/logout", "/auth/password");
	}

}
