/*
 * M2 单测：审计必须与业务同事务（契约 3.1 / 7.4）。
 *
 * 真库集成用例：在真实事务中写一条 audit_logs，然后抛出异常回滚；
 * 断言 audit_logs 行数不变，证明“业务回滚时审计也回滚”。
 * 连不上 MySQL 时按 JUnit assumption 跳过（不伪装通过）。
 */
package com.pig4cloud.pig.mold.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pig4cloud.pig.mold.security.MoldActorResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditWriterRollbackTest {

	@Test
	void rejectsCallsOutsideTransaction() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate();
		AuditWriter writer = new AuditWriter(jdbcTemplate, new ObjectMapper(), new MoldActorResolver(jdbcTemplate));
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> writer.record("VERIFY", "outside", "CREATE", null, Map.of("a", 1)));
		assertTrue(ex.getMessage().contains("事务"));
	}

	@Test
	void auditRollsBackWithBusinessTransaction() {
		JdbcTemplate jdbcTemplate = connect();
		DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(
				jdbcTemplate.getDataSource());
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		AuditWriter writer = new AuditWriter(jdbcTemplate, new ObjectMapper(), new MoldActorResolver(jdbcTemplate));

		long before = count(jdbcTemplate);
		boolean rolledBack = false;
		try {
			template.executeWithoutResult(status -> {
				Map<String, Object> after = new LinkedHashMap<>();
				after.put("a", 1);
				writer.record("VERIFY", "rollback-case", "CREATE", null, after);
				throw new ForcedRollback();
			});
		}
		catch (ForcedRollback expected) {
			rolledBack = true;
		}

		assertTrue(rolledBack, "事务应被强制回滚");
		assertEquals(before, count(jdbcTemplate), "audit_logs 不应留下回滚用例的行");
	}

	private static long count(JdbcTemplate jdbcTemplate) {
		Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs", Long.class);
		return value == null ? 0L : value;
	}

	/**
	 * 依次尝试本机 3307 与 compose 网络内的 pig-mysql；都连不上则跳过整条用例。
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
				JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
				jdbcTemplate.queryForObject("SELECT 1", Integer.class);
				return jdbcTemplate;
			}
			catch (RuntimeException ignored) {
				// try next URL
			}
		}
		Assumptions.assumeTrue(false, "MySQL 不可达，跳过审计回滚集成测试");
		throw new IllegalStateException("unreachable");
	}

	private static class ForcedRollback extends RuntimeException {

	}

}
