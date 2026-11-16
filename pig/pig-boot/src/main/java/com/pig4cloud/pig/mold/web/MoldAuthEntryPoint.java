/*
 * 模具加工管理系统 M2 后端切片
 * 未认证统一返回 401 + 契约错误信封。
 * Pig 原生入口把“无 token / token 过期”映射为 424，M2 完成标准要求 401，
 * 因此以 @Primary 覆盖资源服务器的 AuthenticationEntryPoint。
 */
package com.pig4cloud.pig.mold.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pig4cloud.pig.common.core.util.R;
import com.pig4cloud.pig.common.security.component.ResourceAuthExceptionEntryPoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import org.springframework.context.MessageSource;

import java.util.LinkedHashMap;
import java.util.Map;

public class MoldAuthEntryPoint extends ResourceAuthExceptionEntryPoint {

	private final ObjectMapper objectMapper;

	public MoldAuthEntryPoint(ObjectMapper objectMapper, MessageSource messageSource) {
		super(objectMapper, messageSource);
		this.objectMapper = objectMapper;
	}

	@Override
	@SneakyThrows
	public void commence(HttpServletRequest request, HttpServletResponse response,
			org.springframework.security.core.AuthenticationException authException) {
		response.setCharacterEncoding("UTF-8");
		response.setContentType("application/json;charset=UTF-8");
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("errorCode", "UNAUTHENTICATED");
		data.put("details", null);
		data.put("requestId", request.getAttribute(MoldRequestIdFilter.ATTRIBUTE));

		R<Map<String, Object>> body = new R<>();
		body.setCode(401);
		body.setMsg("未登录或登录已过期");
		body.setData(data);
		response.getWriter().append(objectMapper.writeValueAsString(body));
	}

}
