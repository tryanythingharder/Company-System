/*
 * 模具加工管理系统 M2 后端切片
 * offset 分页与排序白名单校验（契约 6.1 / 用户决策 ③）。
 */
package com.pig4cloud.pig.mold.web;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record PageQuery(int pageNum, int pageSize, String sort, String order) {

	public static final int DEFAULT_PAGE_SIZE = 20;

	public static final int MAX_PAGE_SIZE = 100;

	public static PageQuery parse(String pageNumRaw, String pageSizeRaw, String sortRaw, String orderRaw,
			Set<String> allowedSorts, String defaultSort) {
		int pageNum = parsePageNum(pageNumRaw);
		int pageSize = parsePageSize(pageSizeRaw);

		String sort = blankToNull(sortRaw);
		String order = blankToNull(orderRaw);

		if (sort != null && !allowedSorts.contains(sort)) {
			throw MoldApiException.validation("/query/sort", "enum",
					Map.of("allowedValues", List.copyOf(allowedSorts)), "参数无效 sort");
		}
		if (order != null && sort == null) {
			throw MoldApiException.validation("/query/order", "required", null, "order 必须与 sort 成对使用");
		}
		if (order != null && !"asc".equals(order) && !"desc".equals(order)) {
			throw MoldApiException.validation("/query/order", "enum", Map.of("allowedValues", List.of("asc", "desc")),
					"参数无效 order");
		}
		if (sort == null) {
			sort = defaultSort;
			order = "asc";
		}
		if (order == null) {
			order = "asc";
		}
		return new PageQuery(pageNum, pageSize, sort, order);
	}

	private static int parsePageNum(String raw) {
		if (blankToNull(raw) == null) {
			return 1;
		}
		int value;
		try {
			value = Integer.parseInt(raw.trim());
		}
		catch (NumberFormatException ex) {
			throw MoldApiException.validation("/query/pageNum", "type", null, "pageNum 必须是整数");
		}
		if (value < 1) {
			throw MoldApiException.validation("/query/pageNum", "minimum", Map.of("limit", 1), "pageNum 必须大于等于 1");
		}
		return value;
	}

	private static int parsePageSize(String raw) {
		if (blankToNull(raw) == null) {
			return DEFAULT_PAGE_SIZE;
		}
		int value;
		try {
			value = Integer.parseInt(raw.trim());
		}
		catch (NumberFormatException ex) {
			throw MoldApiException.validation("/query/pageSize", "type", null, "pageSize 必须是整数");
		}
		if (value < 1) {
			throw MoldApiException.validation("/query/pageSize", "minimum", Map.of("limit", 1),
					"pageSize 必须大于等于 1");
		}
		if (value > MAX_PAGE_SIZE) {
			throw MoldApiException.validation("/query/pageSize", "maximum", Map.of("limit", MAX_PAGE_SIZE),
					"pageSize 不能超过 100");
		}
		return value;
	}

	private static String blankToNull(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		return value.trim();
	}

}
