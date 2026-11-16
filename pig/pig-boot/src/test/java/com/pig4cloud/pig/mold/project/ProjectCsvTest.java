/*
 * 模具加工管理系统 M2 第三切片
 * 全量导出 CSV 字段转义：逗号、引号、换行必须被正确包裹，避免破坏列结构。
 */
package com.pig4cloud.pig.mold.project;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectCsvTest {

	@Test
	void plainValuesPassThrough() {
		assertThat(ProjectService.csv("DEMO-2026-001")).isEqualTo("DEMO-2026-001");
	}

	@Test
	void nullBecomesEmpty() {
		assertThat(ProjectService.csv(null)).isEmpty();
	}

	@Test
	void commaAndQuoteAreEscaped() {
		assertThat(ProjectService.csv("a,b")).isEqualTo("\"a,b\"");
		assertThat(ProjectService.csv("a\"b")).isEqualTo("\"a\"\"b\"");
	}

	@Test
	void newlineIsQuoted() {
		assertThat(ProjectService.csv("a\nb")).isEqualTo("\"a\nb\"");
	}

}
