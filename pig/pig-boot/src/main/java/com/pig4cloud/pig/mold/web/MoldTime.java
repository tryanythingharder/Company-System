/*
 * 模具加工管理系统 M2 后端切片
 * 时间统一以 UTC ISO-8601 输出（契约 6.1 / 7.3）。
 */
package com.pig4cloud.pig.mold.web;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class MoldTime {

	private MoldTime() {
	}

	public static String iso(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Timestamp timestamp) {
			return timestamp.toInstant().toString();
		}
		if (value instanceof LocalDateTime localDateTime) {
			return localDateTime.toInstant(ZoneOffset.UTC).toString();
		}
		if (value instanceof Instant instant) {
			return instant.toString();
		}
		if (value instanceof LocalDate localDate) {
			return localDate.toString();
		}
		if (value instanceof java.sql.Date sqlDate) {
			return sqlDate.toLocalDate().toString();
		}
		if (value instanceof java.util.Date date) {
			return date.toInstant().toString();
		}
		return value.toString();
	}

	public static String nowIso() {
		return Instant.now().toString();
	}

}
