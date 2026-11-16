/*
 * 模具加工管理系统 M2 后端切片
 * 统一业务异常：携带真实 HTTP 状态码、契约错误码与可选 details。
 */
package com.pig4cloud.pig.mold.web;

import lombok.Getter;

import java.util.Map;

@Getter
public class MoldApiException extends RuntimeException {

	private final int status;

	private final String errorCode;

	private final Object details;

	private final Long retryAfterSeconds;

	public MoldApiException(int status, String errorCode, String message) {
		this(status, errorCode, message, null, null);
	}

	public MoldApiException(int status, String errorCode, String message, Object details) {
		this(status, errorCode, message, details, null);
	}

	public MoldApiException(int status, String errorCode, String message, Object details, Long retryAfterSeconds) {
		super(message);
		this.status = status;
		this.errorCode = errorCode;
		this.details = details;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	/**
	 * 422 参数校验失败，错误路径与关键字按契约 6.1 组织。
	 */
	public static MoldApiException validation(String instancePath, String keyword, Map<String, Object> params,
			String message) {
		Map<String, Object> error = new java.util.LinkedHashMap<>();
		error.put("instancePath", instancePath);
		error.put("keyword", keyword);
		error.put("params", params == null ? Map.of() : params);
		error.put("message", message);
		Map<String, Object> details = new java.util.LinkedHashMap<>();
		details.put("errors", java.util.List.of(error));
		return new MoldApiException(422, "VALIDATION_FAILED", message, details);
	}

}
