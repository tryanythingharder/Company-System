/*
 * 模具加工管理系统 M7-A / A3 审计日志接口（契约 6.10.2，1 操作）。
 */
package com.pig4cloud.pig.mold.audit;

import com.pig4cloud.pig.common.core.util.R;
import com.pig4cloud.pig.mold.web.PageQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mold/audit-logs")
public class AuditLogController {

	private final AuditLogService auditLogService;

	@GetMapping
	@PreAuthorize("@pms.hasPermission('mold:audit:list')")
	public R<Map<String, Object>> list(@RequestParam(required = false) String pageNum,
			@RequestParam(required = false) String pageSize, @RequestParam(required = false) String sort,
			@RequestParam(required = false) String order, @RequestParam(required = false) String entityType,
			@RequestParam(required = false) String entityId, @RequestParam(required = false) String actorId,
			@RequestParam(required = false) String action, @RequestParam(required = false) String dateFrom,
			@RequestParam(required = false) String dateTo) {
		PageQuery page = PageQuery.parse(pageNum, pageSize, sort, order, AuditLogService.SORTABLE, "createdAt");
		if (!StringUtils.hasText(sort) && !StringUtils.hasText(order)) {
			page = new PageQuery(page.pageNum(), page.pageSize(), page.sort(), "desc");
		}
		return R.ok(auditLogService.list(page, entityType, entityId, actorId, action, dateFrom, dateTo));
	}

}
