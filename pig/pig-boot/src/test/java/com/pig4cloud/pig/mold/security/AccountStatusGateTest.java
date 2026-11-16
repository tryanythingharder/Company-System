/*
 * M2 修复轮单测：账号状态权威判定（sys_user.lock_flag / del_flag）。
 *
 * 覆盖：
 *   1) 纯判定表：正常、停用、删除、删除优先、历史 NULL 值；
 *   2) 查库判定：按 user_id / username 命中停用行、查无此行（MISSING）、空参数。
 *
 * 用匿名 JdbcTemplate 只回固定行，不连数据库（与既有单测同一风格）。
 */
package com.pig4cloud.pig.mold.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccountStatusGateTest {

	@Test
	void activeWhenFlagsAreNormal() {
		assertEquals(AccountStatusGate.State.ACTIVE, AccountStatusGate.evaluate("0", "0"));
	}

	@Test
	void lockedWhenLockFlagIsNine() {
		assertEquals(AccountStatusGate.State.LOCKED, AccountStatusGate.evaluate("9", "0"));
	}

	@Test
	void deletedWhenDelFlagIsOne() {
		assertEquals(AccountStatusGate.State.DELETED, AccountStatusGate.evaluate("0", "1"));
	}

	@Test
	void deletedWinsOverLocked() {
		assertEquals(AccountStatusGate.State.DELETED, AccountStatusGate.evaluate("9", "1"));
	}

	@Test
	void nullFlagsAreTreatedAsActiveForLegacyRows() {
		assertEquals(AccountStatusGate.State.ACTIVE, AccountStatusGate.evaluate(null, null));
		assertEquals(AccountStatusGate.State.ACTIVE, AccountStatusGate.evaluate("", ""));
	}

	@Test
	void bySysUserIdFindsLockedRow() {
		AccountStatusGate gate = gate(List.of(Map.of("lock_flag", "9", "del_flag", "0")));

		assertEquals(AccountStatusGate.State.LOCKED, gate.bySysUserId(120L));
	}

	@Test
	void byUsernameFindsDeletedRow() {
		AccountStatusGate gate = gate(List.of(Map.of("lock_flag", "0", "del_flag", "1")));

		assertEquals(AccountStatusGate.State.DELETED, gate.byUsername("tmp_deleted"));
	}

	@Test
	void missingRowIsReportedAsMissing() {
		AccountStatusGate gate = gate(List.of());

		assertEquals(AccountStatusGate.State.MISSING, gate.bySysUserId(999L));
		assertEquals(AccountStatusGate.State.MISSING, gate.byUsername("nobody"));
	}

	@Test
	void nullInputsAreMissing() {
		AccountStatusGate gate = gate(List.of());

		assertEquals(AccountStatusGate.State.MISSING, gate.bySysUserId(null));
		assertEquals(AccountStatusGate.State.MISSING, gate.byUsername(null));
		assertEquals(AccountStatusGate.State.MISSING, gate.byUsername("  "));
	}

	private AccountStatusGate gate(List<Map<String, Object>> rows) {
		return new AccountStatusGate(new JdbcTemplate() {
			@Override
			public List<Map<String, Object>> queryForList(String sql, Object... args) {
				return rows;
			}
		});
	}

}
