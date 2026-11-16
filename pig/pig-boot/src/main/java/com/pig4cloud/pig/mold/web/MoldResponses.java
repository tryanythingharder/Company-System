/*
 * 模具加工管理系统 M2 后端切片
 * 列表响应体：rows / total / pageNum / pageSize / pages / asOf / dataVersion。
 */
package com.pig4cloud.pig.mold.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MoldResponses {

	/**
	 * 契约 6.1：GET 单资源返回 ETag: "&lt;version&gt;"。
	 */
	public static void etag(jakarta.servlet.http.HttpServletResponse response, Object version) {
		if (version != null) {
			response.setHeader(org.springframework.http.HttpHeaders.ETAG, "\"" + version + "\"");
		}
	}


	private MoldResponses() {
	}

	public static Map<String, Object> page(List<Map<String, Object>> rows, long total, int pageNum, int pageSize,
			String dataVersion) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("rows", rows);
		// Pig 的 Jackson 全局把 Long 序列化成字符串（防 JS 精度丢失），契约要求 total/pages 为数字。
		data.put("total", total > Integer.MAX_VALUE ? (Object) total : (Object) (int) total);
		data.put("pageNum", pageNum);
		data.put("pageSize", pageSize);
		long pages = pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize;
		data.put("pages", pages > Integer.MAX_VALUE ? (Object) pages : (Object) (int) pages);
		data.put("asOf", MoldTime.nowIso());
		data.put("dataVersion", dataVersion);
		return data;
	}

	/**
	 * 不分页的集合（采购收货 / 发票 / 付款 / 附件 / 成员等）：仍使用 rows + total 形状，
	 * 与契约 6.1 的列表口径一致，前端无需再区分数组与分页对象。
	 */
	public static Map<String, Object> collection(List<Map<String, Object>> rows, String dataVersion) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("rows", rows);
		data.put("total", rows.size());
		data.put("asOf", MoldTime.nowIso());
		data.put("dataVersion", dataVersion);
		return data;
	}

}
