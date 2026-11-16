/*
 * 模具加工管理系统 M7 后端补齐
 * 请求体字段校验的公共工具：错误路径与 keyword 按契约 6.1 组织（422 VALIDATION_FAILED）。
 *
 * 只做“形状与范围”校验；业务不变量（超收、超付、状态机等）由各 Service 负责。
 */
package com.pig4cloud.pig.mold.web;

import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MoldFields {

	/** 单笔金额上限：decimal(14,2) 可安全容纳的最大值（不含）。 */
	public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");

	private MoldFields() {
	}

	public static void rejectUnknownFields(Map<String, Object> body, Set<String> allowed) {
		if (body == null) {
			return;
		}
		for (String key : body.keySet()) {
			if (!allowed.contains(key)) {
				throw MoldApiException.validation("/body/" + key, "additionalProperties", null,
						"不允许通过该接口提交字段 " + key);
			}
		}
	}

	/**
	 * PATCH 至少包含一个可改字段；{} 或只含不可改键 → 422（契约 6.1）。
	 */
	public static void requireAtLeastOneField(Map<String, Object> body, Set<String> allowed) {
		if (body == null || body.keySet().stream().noneMatch(allowed::contains)) {
			throw MoldApiException.validation("/body", "required", null,
					"空对象不构成有效修改，至少提供一个字段：" + String.join("、", allowed));
		}
	}

	public static String text(Object value, String field, int maxLength) {
		String text = value == null ? null : value.toString().trim();
		if (!StringUtils.hasText(text)) {
			throw MoldApiException.validation("/body/" + field, "required", null, field + " 不能为空");
		}
		if (text.length() > maxLength) {
			throw MoldApiException.validation("/body/" + field, "maxLength", Map.of("limit", maxLength),
					field + " 长度不能超过 " + maxLength);
		}
		return text;
	}

	public static String optionalText(Object value, String field, int maxLength) {
		if (value == null || !StringUtils.hasText(value.toString())) {
			return null;
		}
		String text = value.toString().trim();
		if (text.length() > maxLength) {
			throw MoldApiException.validation("/body/" + field, "maxLength", Map.of("limit", maxLength),
					field + " 长度不能超过 " + maxLength);
		}
		return text;
	}

	public static String enumValue(Object value, String field, Set<String> allowed) {
		String text = value == null ? null : value.toString().trim();
		if (text == null || !allowed.contains(text)) {
			throw MoldApiException.validation("/body/" + field, "enum", Map.of("allowedValues", List.copyOf(allowed)),
					field + " 取值无效");
		}
		return text;
	}

	public static String optionalEnum(Object value, String field, Set<String> allowed) {
		if (value == null || !StringUtils.hasText(value.toString())) {
			return null;
		}
		return enumValue(value, field, allowed);
	}

	public static LocalDate date(Object value, String field) {
		if (value == null || !StringUtils.hasText(value.toString())) {
			throw MoldApiException.validation("/body/" + field, "required", null, field + " 不能为空");
		}
		return parseDate(value.toString().trim(), field);
	}

	public static LocalDate optionalDate(Object value, String field) {
		if (value == null || !StringUtils.hasText(value.toString())) {
			return null;
		}
		return parseDate(value.toString().trim(), field);
	}

	private static LocalDate parseDate(String raw, String field) {
		try {
			return LocalDate.parse(raw);
		}
		catch (DateTimeParseException ex) {
			throw MoldApiException.validation("/body/" + field, "format", Map.of("format", "YYYY-MM-DD"),
					field + " 必须是 YYYY-MM-DD");
		}
	}

	public static Integer integer(Object value, String field) {
		if (value == null || !StringUtils.hasText(value.toString())) {
			throw MoldApiException.validation("/body/" + field, "required", null, field + " 不能为空");
		}
		try {
			return new BigDecimal(value.toString().trim()).intValueExact();
		}
		catch (ArithmeticException | NumberFormatException ex) {
			throw MoldApiException.validation("/body/" + field, "type", null, field + " 必须是整数");
		}
	}

	public static BigDecimal decimal(Object value, String field, int scale, boolean positive) {
		if (value == null || !StringUtils.hasText(value.toString())) {
			throw MoldApiException.validation("/body/" + field, "required", null, field + " 不能为空");
		}
		return parseDecimal(value.toString().trim(), field, scale, positive);
	}

	public static BigDecimal optionalDecimal(Object value, String field, int scale, boolean positive) {
		if (value == null || !StringUtils.hasText(value.toString())) {
			return null;
		}
		return parseDecimal(value.toString().trim(), field, scale, positive);
	}

	private static BigDecimal parseDecimal(String raw, String field, int scale, boolean positive) {
		BigDecimal value;
		try {
			value = new BigDecimal(raw);
		}
		catch (NumberFormatException ex) {
			throw MoldApiException.validation("/body/" + field, "type", null, field + " 必须是数字");
		}
		if (value.scale() > scale) {
			throw MoldApiException.validation("/body/" + field, "scale", Map.of("scale", scale),
					field + " 最多 " + scale + " 位小数");
		}
		if (positive && value.signum() <= 0) {
			throw MoldApiException.validation("/body/" + field, "exclusiveMinimum", Map.of("limit", 0),
					field + " 必须大于 0");
		}
		if (!positive && value.signum() < 0) {
			throw MoldApiException.validation("/body/" + field, "minimum", Map.of("limit", 0), field + " 不能为负");
		}
		if (value.abs().compareTo(MAX_AMOUNT) >= 0) {
			throw MoldApiException.validation("/body/" + field, "maximum", Map.of("limit", MAX_AMOUNT.toPlainString()),
					field + " 超出支持范围");
		}
		return value.setScale(scale, RoundingMode.HALF_UP);
	}

	public static Boolean bool(Object value, String field) {
		if (value == null) {
			return null;
		}
		if (value instanceof Boolean bool) {
			return bool;
		}
		String text = value.toString().trim();
		if ("true".equalsIgnoreCase(text)) {
			return Boolean.TRUE;
		}
		if ("false".equalsIgnoreCase(text)) {
			return Boolean.FALSE;
		}
		throw MoldApiException.validation("/body/" + field, "type", null, field + " 必须是布尔值");
	}

	public static String money(BigDecimal value) {
		if (value == null) {
			return null;
		}
		return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

	public static String quantity(BigDecimal value) {
		if (value == null) {
			return null;
		}
		return value.setScale(4, RoundingMode.HALF_UP).toPlainString();
	}

	public static String unitPrice(BigDecimal value) {
		if (value == null) {
			return null;
		}
		return value.setScale(6, RoundingMode.HALF_UP).toPlainString();
	}

}
