/*
 * 决策 B 单测：流水可见性（非财务只看自己录的，财务/OWNER 看全部）。
 *
 * 覆盖两条路径：
 *   1) 无 mold:cash:viewAll  -> 列表 SQL 追加 created_by = 本人，COUNT 与分页共用；
 *   2) 有 mold:cash:viewAll  -> 列表不带按人过滤；
 * 另覆盖单条详情：非本人按不存在处理（404），不泄漏存在性。
 *
 * 说明：这里用真实的 CashEntryService，只替换 JdbcTemplate / 权限 / 身份三个协作者，
 * 不引入 mock 框架；真实 200/403/404 矩阵另有实测证据（docs/verify/m7b-verify.py）。
 */
package com.pig4cloud.pig.mold.finance;

import com.pig4cloud.pig.common.security.component.PermissionService;
import com.pig4cloud.pig.mold.security.MoldActor;
import com.pig4cloud.pig.mold.security.MoldActorResolver;
import com.pig4cloud.pig.mold.web.MoldApiException;
import com.pig4cloud.pig.mold.web.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CashEntryVisibilityTest {

	private static final String ME = "11111111-1111-1111-1111-111111111111";

	private static final String OTHER = "22222222-2222-2222-2222-222222222222";

	/** 记录 SQL 与参数，不做真实数据库访问。 */
	static class RecordingJdbc extends JdbcTemplate {

		final List<String> sqls = new ArrayList<>();

		final List<Object[]> args = new ArrayList<>();

		List<Map<String, Object>> rows = List.of();

		@Override
		public <T> T queryForObject(String sql, Class<T> requiredType, Object... queryArgs) {
			return record(sql, queryArgs);
		}

		@Override
		public <T> T queryForObject(String sql, Class<T> requiredType) {
			return record(sql, new Object[0]);
		}

		private <T> T record(String sql, Object[] queryArgs) {
			sqls.add(sql);
			args.add(queryArgs);
			@SuppressWarnings("unchecked")
			T zero = (T) Long.valueOf(0L);
			return zero;
		}

		@Override
		public List<Map<String, Object>> queryForList(String sql, Object... queryArgs) {
			sqls.add(sql);
			args.add(queryArgs);
			return rows;
		}

	}

	/** 固定返回某个权限判定结果。 */
	static class FixedPermission extends PermissionService {

		private final boolean viewAll;

		FixedPermission(boolean viewAll) {
			this.viewAll = viewAll;
		}

		@Override
		public boolean hasPermission(String... permissions) {
			for (String permission : permissions) {
				if ("mold:cash:viewAll".equals(permission)) {
					return viewAll;
				}
			}
			return false;
		}

	}

	/** 固定返回当前业务身份。 */
	static class FixedActorResolver extends MoldActorResolver {

		FixedActorResolver() {
			super(null);
		}

		@Override
		public MoldActor current() {
			return new MoldActor(ME, 1L, "demo_prog1", "苏泽宇", "ACTIVE", 1, false, List.of("PROGRAMMER"));
		}

	}

	private static CashEntryService service(RecordingJdbc jdbc, boolean viewAll) {
		return new CashEntryService(jdbc, new FixedActorResolver(), null, null, new FixedPermission(viewAll));
	}

	private static PageQuery page() {
		return new PageQuery(1, 20, "occurredOn", "desc");
	}

	@Test
	void listFiltersToOwnRowsWithoutViewAll() {
		RecordingJdbc jdbc = new RecordingJdbc();

		service(jdbc, false).list(page(), null, null, null, null, null, null, null, null, null, null, null);

		assertTrue(jdbc.sqls.get(0).contains("c.created_by = ?"), "COUNT 必须与分页同过滤：" + jdbc.sqls.get(0));
		assertTrue(jdbc.sqls.get(1).contains("c.created_by = ?"), "分页必须按 created_by 过滤：" + jdbc.sqls.get(1));
		assertTrue(List.of(jdbc.args.get(1)).contains(ME), "过滤值必须是当前用户");
		assertFalse(List.of(jdbc.args.get(1)).contains(OTHER), "不得出现他人 id");
	}

	@Test
	void listIsNotFilteredForViewAll() {
		RecordingJdbc jdbc = new RecordingJdbc();

		service(jdbc, true).list(page(), null, null, null, null, null, null, null, null, null, null, null);

		assertFalse(jdbc.sqls.get(0).contains("created_by"), "财务 COUNT 不得按人过滤：" + jdbc.sqls.get(0));
		assertFalse(jdbc.sqls.get(1).contains("created_by"), "财务分页不得按人过滤：" + jdbc.sqls.get(1));
	}

	@Test
	void detailOfForeignEntryIsNotFound() {
		RecordingJdbc jdbc = new RecordingJdbc();
		jdbc.rows = List.of(row(OTHER));

		MoldApiException ex = assertThrows(MoldApiException.class, () -> service(jdbc, false).get("entry-1"));

		assertEquals(404, ex.getStatus());
		assertEquals("NOT_FOUND", ex.getErrorCode());
	}

	@Test
	void detailOfOwnEntryIsReturned() {
		RecordingJdbc jdbc = new RecordingJdbc();
		jdbc.rows = List.of(row(ME));

		Map<String, Object> dto = service(jdbc, false).get("entry-1");

		assertEquals("entry-1", dto.get("id"));
		assertEquals("10.00", dto.get("amount"));
	}

	@Test
	void detailOfForeignEntryIsVisibleToFinance() {
		RecordingJdbc jdbc = new RecordingJdbc();
		jdbc.rows = List.of(row(OTHER));

		Map<String, Object> dto = service(jdbc, true).get("entry-1");

		assertEquals("entry-1", dto.get("id"));
	}

	private static Map<String, Object> row(String createdBy) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", "entry-1");
		row.put("code", "B-1");
		row.put("direction", "OUT");
		row.put("category", "DAILY_EXPENSE");
		row.put("occurred_on", java.sql.Date.valueOf("2026-11-15"));
		row.put("amount", new java.math.BigDecimal("10.00"));
		row.put("currency", "CNY");
		row.put("allocation_status", "PUBLIC");
		row.put("method", "CASH");
		row.put("status", "CONFIRMED");
		row.put("version", 1);
		row.put("created_by", createdBy);
		return row;
	}

}
