package com.pig4cloud.pig.mold.audit;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 审计日志按北京自然日筛选：created_at 存 UTC，需换算成 UTC 区间 */
class AuditLogDateRangeTest {

	@Test
	void beijingDayStartMapsToPreviousUtcEvening() {
		assertThat(AuditLogService.beijingDayStartUtc(LocalDate.of(2026, 11, 23))).isEqualTo("2026-11-22 16:00:00");
	}

	@Test
	void yearBoundaryIsHandled() {
		assertThat(AuditLogService.beijingDayStartUtc(LocalDate.of(2026, 1, 1))).isEqualTo("2025-12-31 16:00:00");
	}

}
