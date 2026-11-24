/*
 * 模具加工管理系统 M2 修复轮
 * 账号停用/删除的按请求闸门：已签发的 token 在下一个请求即被拒。
 *
 * 为什么放拦截器而不是只改登录：token 一旦签发，Pig 的资源服务器只校验签名与 Redis 会话，
 * 不查 sys_user；停用后旧 token 仍能用到过期。本拦截器在 @PreAuthorize 之前查一次
 * sys_user.lock_flag / del_flag，命中即 401 UNAUTHENTICATED。
 *
 * 选 401 而不是 403：这是「凭证已失效」语义（账号被停用，token 不应再代表任何身份）。
 *
 * 生产加固：
 *  - 覆盖全部 MVC 路径（含 pig-boot 内置的 upms 接口 /user/**、/sys-file/** 等），
 *    排除项见 MoldWebConfig；/auth/refresh 由 AuthService 自行校验账号状态。
 *  - 状态查询异常时拒绝（fail-closed，503）：权限闸门失效时放行等于停用账号可继续访问；
 *    数据库不可用时业务本身也不可用，拒绝不会额外放大影响。
 */
package com.pig4cloud.pig.mold.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pig4cloud.pig.common.core.util.R;
import com.pig4cloud.pig.common.security.service.PigUser;
import com.pig4cloud.pig.common.security.util.SecurityUtils;
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
public class AccountStatusInterceptor implements HandlerInterceptor {

	public static final String ERROR_CODE = "UNAUTHENTICATED";

	public static final String UNAVAILABLE_CODE = "SERVICE_UNAVAILABLE";

	private final ObjectMapper objectMapper;

	private final AccountStatusGate accountStatusGate;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {
		PigUser pigUser = SecurityUtils.getUser();
		if (pigUser == null) {
			// 未登录交给 Spring Security 的 401 入口点，不在这里改写。
			return true;
		}

		AccountStatusGate.State state;
		try {
			state = accountStatusGate.bySysUserId(pigUser.getId());
		}
		catch (RuntimeException ex) {
			log.error("账号状态查询失败，按拒绝处理 userId={} err={}", pigUser.getId(), ex.toString());
			write(response, request, HttpServletResponse.SC_SERVICE_UNAVAILABLE, UNAVAILABLE_CODE,
					"服务暂不可用，请稍后重试");
			return false;
		}
		if (state != AccountStatusGate.State.LOCKED && state != AccountStatusGate.State.DELETED) {
			return true;
		}

		log.info("拒绝已停用/删除账号的请求 userId={} state={} path={}", pigUser.getId(), state,
				request.getRequestURI());
		write(response, request, HttpServletResponse.SC_UNAUTHORIZED, ERROR_CODE,
				state == AccountStatusGate.State.DELETED ? "账号已删除，请联系管理员" : "账号已停用，请联系管理员");
		return false;
	}

	private void write(HttpServletResponse response, HttpServletRequest request, int status, String errorCode,
			String msg) throws Exception {
		response.setStatus(status);
		response.setCharacterEncoding("UTF-8");
		response.setContentType("application/json;charset=UTF-8");

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("errorCode", errorCode);
		data.put("details", null);
		data.put("requestId", request.getAttribute(MoldRequestIdFilter.ATTRIBUTE));

		R<Map<String, Object>> body = new R<>();
		body.setCode(status);
		body.setMsg(msg);
		body.setData(data);
		response.getWriter().append(objectMapper.writeValueAsString(body));
	}

}
