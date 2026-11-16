/*
 * 模具加工管理系统 M2 修复轮
 * 账号状态权威判定：sys_user.lock_flag / del_flag。
 *
 * 为什么需要它：账号管理接口停用账号时写的是 sys_user.lock_flag='9'
 * （UserService.update），而登录与业务身份解析读的是 users.status，
 * 两处没有任何同步，导致「停用」只改了一张没人看的表。
 * 这里以 sys_user 为唯一权威：lock_flag='9' 视为停用，del_flag='1' 视为已删除。
 *
 * 只读判定，不改 lock_flag 语义、不新增权限串；调用方决定返回 401 还是 403。
 */
package com.pig4cloud.pig.mold.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AccountStatusGate {

	/** lock_flag 的停用取值（Pig 原生语义，未改）。 */
	public static final String LOCK_FLAG_LOCKED = "9";

	/** del_flag 的删除取值（Pig 原生语义，未改）。 */
	public static final String DEL_FLAG_DELETED = "1";

	public enum State {

		/** 未停用、未删除。 */
		ACTIVE,
		/** lock_flag='9'。 */
		LOCKED,
		/** del_flag='1'。 */
		DELETED,
		/** sys_user 无此行（正常 token 不应出现，交正常错误链路）。 */
		MISSING

	}

	private final JdbcTemplate jdbcTemplate;

	/** 按登录主表主键判定（token 里的 PigUser.id 即 sys_user.user_id）。 */
	public State bySysUserId(Long sysUserId) {
		if (sysUserId == null) {
			return State.MISSING;
		}
		return decide(jdbcTemplate.queryForList("SELECT lock_flag, del_flag FROM sys_user WHERE user_id = ?",
				sysUserId));
	}

	/** 按登录名判定（登录链在口令校验通过后调用，避免账号枚举）。 */
	public State byUsername(String username) {
		if (username == null || username.isBlank()) {
			return State.MISSING;
		}
		return decide(jdbcTemplate.queryForList("SELECT lock_flag, del_flag FROM sys_user WHERE username = ?",
				username));
	}

	/**
	 * 纯判定，便于单测：任一取值异常（NULL/空串）都按未停用处理，保持既有数据可登录。
	 * 删除优先于停用：两者同时成立时报已删除。
	 */
	static State evaluate(Object lockFlag, Object delFlag) {
		if (isFlag(delFlag, DEL_FLAG_DELETED)) {
			return State.DELETED;
		}
		if (isFlag(lockFlag, LOCK_FLAG_LOCKED)) {
			return State.LOCKED;
		}
		return State.ACTIVE;
	}

	private static State decide(List<Map<String, Object>> rows) {
		if (rows == null || rows.isEmpty()) {
			return State.MISSING;
		}
		State result = State.DELETED;
		for (Map<String, Object> row : rows) {
			State state = evaluate(row.get("lock_flag"), row.get("del_flag"));
			if (state == State.ACTIVE) {
				return State.ACTIVE;
			}
			if (state == State.LOCKED) {
				result = State.LOCKED;
			}
		}
		return result;
	}

	private static boolean isFlag(Object value, String expected) {
		return value != null && expected.equals(String.valueOf(value).trim());
	}

}
