/*
 * 模具加工管理系统 M2 后端切片
 * If-Match 版本号解析：接受 "3"、W/"3"、3，其它格式 422。
 */
package com.pig4cloud.pig.mold.web;

import java.util.Map;

public final class MoldIfMatch {

	private MoldIfMatch() {
	}

	public static Integer parse(String ifMatch) {
		if (ifMatch == null || ifMatch.trim().isEmpty()) {
			return null;
		}
		String value = ifMatch.trim();
		if (value.startsWith("W/")) {
			value = value.substring(2);
		}
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			value = value.substring(1, value.length() - 1);
		}
		try {
			int version = Integer.parseInt(value);
			if (version < 1) {
				throw new NumberFormatException("version must be >= 1");
			}
			return version;
		}
		catch (NumberFormatException ex) {
			throw MoldApiException.validation("/header/If-Match", "format", Map.of("format", "\"<version>\""),
					"If-Match 必须是带引号的版本号");
		}
	}

}
