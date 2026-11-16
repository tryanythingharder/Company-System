/*
 * 模具加工管理系统 M2 后端切片
 * 以 HandlerExceptionResolver 形式处理 M2 错误：
 * Pig 的 GlobalBizExceptionHandler 是 @Order(HIGHEST_PRECEDENCE) 的 @RestControllerAdvice，
 * 会先于普通 advice 捕获 Exception，导致真实 HTTP 状态码被改写成 500。
 * DispatcherServlet 会收集所有 HandlerExceptionResolver bean 并按 Order 排序，本解析器排在最前。
 */
package com.pig4cloud.pig.mold.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pig4cloud.pig.common.core.util.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MoldExceptionResolver implements HandlerExceptionResolver, Ordered {

	private final ObjectMapper objectMapper;

	@Override
	public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler,
			Exception ex) {
		if (ex instanceof MoldApiException mold) {
			return write(request, response, mold.getStatus(), mold.getMessage(), mold.getErrorCode(),
					mold.getDetails(), mold.getRetryAfterSeconds());
		}
		if (ex instanceof AccessDeniedException) {
			return write(request, response, 403, "无此操作权限", "FORBIDDEN", null, null);
		}
		if (ex instanceof HttpMessageNotReadableException) {
			// 空体或 JSON 无法解析：按契约 6.1 归为 422，而不是 Pig 默认的 400/500。
			MoldApiException validation = MoldApiException.validation("/body", "required", null,
					"请求体不能为空或格式不正确");
			return write(request, response, validation.getStatus(), validation.getMessage(),
					validation.getErrorCode(), validation.getDetails(), null);
		}
		if (ex instanceof NoResourceFoundException) {
			return write(request, response, 404, "资源不存在", "NOT_FOUND", null, null);
		}
		return null;
	}

	private ModelAndView write(HttpServletRequest request, HttpServletResponse response, int status, String msg,
			String errorCode, Object details, Long retryAfterSeconds) {
		try {
			response.setStatus(status);
			response.setCharacterEncoding("UTF-8");
			response.setContentType("application/json;charset=UTF-8");
			if (retryAfterSeconds != null) {
				response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
			}

			Map<String, Object> data = new LinkedHashMap<>();
			data.put("errorCode", errorCode);
			data.put("details", details);
			data.put("requestId", request.getAttribute(MoldRequestIdFilter.ATTRIBUTE));

			R<Map<String, Object>> body = new R<>();
			body.setCode(status);
			body.setMsg(msg);
			body.setData(data);
			response.getWriter().append(objectMapper.writeValueAsString(body));
			return new ModelAndView();
		}
		catch (Exception writeError) {
			log.error("写入错误响应失败", writeError);
			return null;
		}
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

}
