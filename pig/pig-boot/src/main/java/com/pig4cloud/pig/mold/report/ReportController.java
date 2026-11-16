/*
 * 模具加工管理系统 M7-A / A3 报表接口（契约 6.9.3，2 操作）。
 */
package com.pig4cloud.pig.mold.report;

import com.pig4cloud.pig.common.core.util.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mold/reports")
public class ReportController {

	private final ReportService reportService;

	@GetMapping("/project-cash")
	@PreAuthorize("@pms.hasPermission('mold:cash:viewAll')")
	public R<Map<String, Object>> projectCash(@RequestParam(required = false) String projectId,
			@RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo) {
		return R.ok(reportService.projectCash(projectId, dateFrom, dateTo));
	}

	@GetMapping("/cash-summary")
	@PreAuthorize("@pms.hasPermission('mold:cash:viewAll')")
	public R<Map<String, Object>> cashSummary(@RequestParam(required = false) String projectId,
			@RequestParam(required = false) String allocationStatus, @RequestParam(required = false) String currency,
			@RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo) {
		return R.ok(reportService.cashSummary(projectId, allocationStatus, currency, dateFrom, dateTo));
	}

}
