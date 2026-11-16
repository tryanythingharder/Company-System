/*
 * M2 修复轮单测：/mold/me/stages 的权限口径与空集语义。
 *  - 权限串从 mold:stage:mine 放宽到 mold:project:list（FINANCE/VIEWER 不再 403）；
 *  - 无阶段数据时返回 200 + rows=[] total=0，而不是 403。
 */
package com.pig4cloud.pig.mold.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pig4cloud.pig.mold.audit.AuditWriter;
import com.pig4cloud.pig.mold.event.EventWriter;
import com.pig4cloud.pig.mold.security.MoldActor;
import com.pig4cloud.pig.mold.security.MoldActorResolver;
import com.pig4cloud.pig.mold.web.PageQuery;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.prepost.PreAuthorize;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageMineTest {

	@Test
	void mineEndpointUsesProjectListPermission() throws Exception {
		PreAuthorize preAuthorize = StageController.class
			.getMethod("mine", String.class, String.class, String.class, String.class, String.class)
			.getAnnotation(PreAuthorize.class);

		assertTrue(preAuthorize.value().contains("mold:project:list"));
		assertFalse(preAuthorize.value().contains("mold:stage:mine"));
	}

	@Test
	void financeActorWithoutStagesGetsEmptyPageNot403() {
		JdbcTemplate jdbcTemplate = connect();
		MoldActorResolver resolver = new MoldActorResolver(jdbcTemplate) {
			@Override
			public MoldActor current() {
				return new MoldActor("m2-s4-no-such-user", 0L, "m2_slice4_finance", "财务空集用例", "ACTIVE", 1,
						false, List.of("FINANCE"));
			}
		};
		ObjectMapper objectMapper = new ObjectMapper();
		StageService service = new StageService(jdbcTemplate, resolver,
				new AuditWriter(jdbcTemplate, objectMapper, resolver), new EventWriter(jdbcTemplate, objectMapper));

		PageQuery page = PageQuery.parse(null, null, null, null, StageService.SORTABLE, "id");
		Map<String, Object> data = service.myStages(page, null);

		assertEquals(0, data.get("total"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
		assertTrue(rows.isEmpty());
	}

	/**
	 * 连不上本机 MySQL 时跳过用例，不伪装通过（与 AuditWriterRollbackTest 一致）。
	 */
	private static JdbcTemplate connect() {
		String explicit = System.getenv("MOLD_TEST_JDBC_URL");
		List<String> urls = explicit != null && !explicit.isBlank() ? List.of(explicit)
				: List.of(
						"jdbc:mysql://127.0.0.1:3307/mold_dev?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC",
						"jdbc:mysql://pig-mysql:3306/mold_dev?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC");
		for (String url : urls) {
			try {
				DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "root", "root");
				try (Connection ignored = dataSource.getConnection()) {
					return new JdbcTemplate(dataSource);
				}
			}
			catch (Exception ex) {
				// 尝试下一个地址
			}
		}
		Assumptions.abort("本机与 compose 网络均无 mold_dev，跳过真库用例");
		throw new IllegalStateException("unreachable");
	}

}
