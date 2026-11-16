/*
 * 模具加工管理系统 M2 修复轮
 * 账号停用/删除的按请求闸门：已签发的 token 在下一个请求即被拒。
 *
 * 为什么放拦截器而不是只改登录：token 一旦签发，Pig 的资源服务器只校验签名与 Redis 会话，
 * 不查 sys_user；停用后旧 token 仍能用到过期。本拦截器在 @PreAuthorize 之前查一次
 * sys_user.lock_flag / del_flag，命中即 401 UNAUTHENTICATED。
 *
 * 选 401 而不是 403：这是「凭证已失效」语义（账号被停用，token 不应再代表任何身份），
 * 与 MoldAuthEntryPoint 的未认证口径一致。已知 UX 限制：前端 request.ts 只在 424 强制登出，
 * 401 仅提示错误、不自动清会话，用户需手动退出——前端不改是本轮边界。
 *
 * 放行 /auth/login（无身份）与 /auth/logout（只吊销 token，不读业务数据），
 * 因此停用账号仍能自行退出；/auth/refresh 不放行，避免用刷新令牌续命。
 *
 * 数据库异常时放行（fail-open）：此时业务查询本身也会失败，不把一次抖动放大成全员掉线；
 * 异常会以 WARN 记录，便于发现。
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
			log.warn("账号状态查询失败，按放行处理 userId={} err={}", pigUser.getId(), ex.toString());
			return true;
		}
		if (state != AccountStatusGate.State.LOCKED && state != AccountStatusGate.State.DELETED) {
			return true;
		}

		log.info("拒绝已停用/删除账号的请求 userId={} state={} path={}", pigUser.getId(), state,
				request.getRequestURI());
		write(response, request, state);
		return false;
	}

	private void write(HttpServletResponse response, HttpServletRequest request, AccountStatusGate.State state)
			throws Exception {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setCharacterEncoding("UTF-8");
		response.setContentType("application/json;charset=UTF-8");

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("errorCode", ERROR_CODE);
		data.put("details", null);
		data.put("requestId", request.getAttribute(MoldRequestIdFilter.ATTRIBUTE));

		R<Map<String, Object>> body = new R<>();
		body.setCode(401);
		body.setMsg(state == AccountStatusGate.State.DELETED ? "账号已删除，请联系管理员" : "账号已停用，请联系管理员");
		body.setData(data);
		response.getWriter().append(objectMapper.writeValueAsString(body));
	}

}
