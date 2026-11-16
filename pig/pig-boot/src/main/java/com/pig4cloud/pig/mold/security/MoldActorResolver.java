/*
 * 模具加工管理系统 M2 后端切片
 * 登录身份（sys_user）→ 业务身份（users）的解析。
 * 角色以 user_roles 为事实来源，能力串以 sys_menu.permission 为准。
 */
package com.pig4cloud.pig.mold.security;

import com.pig4cloud.pig.common.security.service.PigUser;
import com.pig4cloud.pig.common.security.util.SecurityUtils;
import com.pig4cloud.pig.mold.web.MoldApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MoldActorResolver {

	private final JdbcTemplate jdbcTemplate;

	public MoldActor current() {
		PigUser pigUser = SecurityUtils.getUser();
		if (pigUser == null) {
			throw new MoldApiException(401, "UNAUTHENTICATED", "未登录或登录已过期");
		}
		return bySysUserId(pigUser.getId());
	}

	public MoldActor bySysUserId(Long sysUserId) {
		if (sysUserId == null) {
			throw new MoldApiException(401, "UNAUTHENTICATED", "未登录或登录已过期");
		}
		MoldActor actor;
		try {
			actor = jdbcTemplate.queryForObject(
					"SELECT id, sys_user_id, login_name, display_name, status, version, must_change_password "
							+ "FROM users WHERE sys_user_id = ?",
					(rs, rowNum) -> new MoldActor(rs.getString("id"), rs.getLong("sys_user_id"),
							rs.getString("login_name"), rs.getString("display_name"), rs.getString("status"),
							rs.getInt("version"), rs.getBoolean("must_change_password"), List.of()),
					sysUserId);
		}
		catch (EmptyResultDataAccessException ex) {
			throw new MoldApiException(403, "FORBIDDEN", "该登录账号未绑定业务身份");
		}
		return actor.withRoles(roles(actor.userId()));
	}

	public MoldActor byLoginName(String loginName) {
		MoldActor actor;
		try {
			actor = jdbcTemplate.queryForObject(
					"SELECT id, sys_user_id, login_name, display_name, status, version, must_change_password "
							+ "FROM users WHERE login_name = ?",
					(rs, rowNum) -> new MoldActor(rs.getString("id"), rs.getLong("sys_user_id"),
							rs.getString("login_name"), rs.getString("display_name"), rs.getString("status"),
							rs.getInt("version"), rs.getBoolean("must_change_password"), List.of()),
					loginName);
		}
		catch (EmptyResultDataAccessException ex) {
			throw new MoldApiException(403, "FORBIDDEN", "该登录账号未绑定业务身份");
		}
		return actor.withRoles(roles(actor.userId()));
	}

	public List<String> roles(String userId) {
		return jdbcTemplate.queryForList("SELECT role_code FROM user_roles WHERE user_id = ? ORDER BY role_code",
				String.class, userId);
	}

	/**
	 * 能力串来自 Pig 的菜单授权，保证与 @pms.hasPermission 判定同源。
	 */
	public List<String> capabilities(Long sysUserId) {
		return jdbcTemplate.queryForList("""
				SELECT DISTINCT m.permission
				FROM sys_menu m
				JOIN sys_role_menu rm ON rm.menu_id = m.menu_id
				JOIN sys_user_role ur ON ur.role_id = rm.role_id
				WHERE ur.user_id = ? AND m.permission IS NOT NULL AND m.permission <> '' AND m.del_flag = '0'
				ORDER BY m.permission
				""", String.class, sysUserId);
	}

}
