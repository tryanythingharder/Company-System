/*
 * 模具加工管理系统 M2 修复轮
 * 强制改密服务端闸门：
 *   白名单 4 条：/auth/refresh、/auth/me、/auth/logout、/auth/password；
 *   这 4 条之外，mustChangePassword=true 的账号调用任何业务接口一律 403 PASSWORD_CHANGE_REQUIRED，
 *   避免与普通 403 混用。
 *
 * 为什么放行 /auth/refresh：刷新令牌只换发 access/refresh token，不读也不写业务数据；
 * 业务接口仍由本拦截器返回 403 PASSWORD_CHANGE_REQUIRED，闸门不会被绕过。
 * 因此“声明 4 条”与实际 excludePathPatterns（MoldWebConfig，另含未登录入口 /auth/login）保持一致。
 *
 * 拦截器在 @PreAuthorize 方法级鉴权之前执行，因此不会被 Pig 授权过滤器先返回普通 403。
 */
package com.pig4cloud.pig.mold.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pig4cloud.pig.common.core.util.R;
import com.pig4cloud.pig.common.security.service.PigUser;
import com.pig4cloud.pig.common.security.util.SecurityUtils;
import com.pig4cloud.pig.mold.web.MoldApiException;
import com.pig4cloud.pig.mold.web.MoldRequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MustChangePasswordInterceptor implements HandlerInterceptor {

	public static final String ERROR_CODE = "PASSWORD_CHANGE_REQUIRED";

	private final ObjectMapper objectMapper;

	private final MoldActorResolver actorResolver;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {
		PigUser pigUser = SecurityUtils.getUser();
		if (pigUser == null) {
			// 未登录交给 Spring Security / 业务层返回 401，不在这里改写。
			return true;
		}

		MoldActor actor;
		try {
			actor = actorResolver.bySysUserId(pigUser.getId());
		}
		catch (MoldApiException ex) {
			// 未绑定业务身份等异常交给正常错误链路处理。
			return true;
		}
		if (!actor.mustChangePassword()) {
			return true;
		}

		write(response, request);
		return false;
	}

	private void write(HttpServletResponse response, HttpServletRequest request) throws Exception {
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setCharacterEncoding("UTF-8");
		response.setContentType("application/json;charset=UTF-8");

		Map<String, Object> details = new LinkedHashMap<>();
		details.put("changePasswordPath", "/auth/password");
		details.put("allowedPaths",
				java.util.List.of("/auth/refresh", "/auth/me", "/auth/logout", "/auth/password"));

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("errorCode", ERROR_CODE);
		data.put("details", details);
		data.put("requestId", request.getAttribute(MoldRequestIdFilter.ATTRIBUTE));

		R<Map<String, Object>> body = new R<>();
		body.setCode(403);
		body.setMsg("请先修改初始密码");
		body.setData(data);
		response.getWriter().append(objectMapper.writeValueAsString(body));
	}

}
