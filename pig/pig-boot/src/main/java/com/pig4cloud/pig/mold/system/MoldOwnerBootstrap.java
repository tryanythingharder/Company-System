/*
 * 模具加工管理系统 生产部署
 * 首个 OWNER 账号引导：库里没有任何在职 OWNER 时，按 MOLD_BOOTSTRAP_OWNER_* 创建一次。
 *
 * 背景：pig.sql 自带的 admin 只有 Pig 登录凭据、没有业务身份（users 表无绑定行），
 * 登录会 403「该登录账号未绑定业务身份」；且默认口令公开，生产初始化时已被锁定。
 * 新账号与「账号管理」创建的账号同构（sys_user + users + 角色双写），口令 BCrypt，首登强制改密。
 * 已存在在职 OWNER 或同名账号时不做任何事，可安全重复启动。
 */
package com.pig4cloud.pig.mold.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Component
public class MoldOwnerBootstrap implements ApplicationRunner {

	private static final Pattern LOGIN_NAME = Pattern.compile("[A-Za-z0-9._-]{3,64}");

	private final JdbcTemplate jdbcTemplate;

	private final TransactionTemplate tx;

	private final String loginName;

	private final String password;

	public MoldOwnerBootstrap(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
			@Value("${mold.bootstrap.owner-login:${MOLD_BOOTSTRAP_OWNER_LOGIN:}}") String loginName,
			@Value("${mold.bootstrap.owner-password:${MOLD_BOOTSTRAP_OWNER_PASSWORD:}}") String password) {
		this.jdbcTemplate = jdbcTemplate;
		this.tx = new TransactionTemplate(transactionManager);
		this.loginName = loginName == null ? "" : loginName.trim();
		this.password = password;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!StringUtils.hasText(loginName)) {
			return;
		}
		if (!LOGIN_NAME.matcher(loginName).matches() || password == null || password.length() < 12) {
			throw new IllegalStateException("MOLD_BOOTSTRAP_OWNER_LOGIN/PASSWORD 不合法：登录名 3-64 位 [A-Za-z0-9._-]，口令至少 12 位");
		}
		Long owners = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM users u JOIN user_roles r ON r.user_id = u.id
				WHERE u.status = 'ACTIVE' AND r.role_code = 'OWNER'
				""", Long.class);
		if (owners != null && owners > 0) {
			log.info("已存在在职 OWNER，跳过首个 OWNER 引导（可从 .env 删除 MOLD_BOOTSTRAP_OWNER_*）");
			return;
		}
		tx.executeWithoutResult(status -> create());
	}

	private void create() {
		Long sameName = jdbcTemplate.queryForObject(
				"SELECT (SELECT COUNT(*) FROM users WHERE login_name = ?) + (SELECT COUNT(*) FROM sys_user WHERE username = ?)",
				Long.class, loginName, loginName);
		if (sameName != null && sameName > 0) {
			log.warn("首个 OWNER 引导跳过：登录名 {} 已存在，请用其它登录名或手工授予 OWNER", loginName);
			return;
		}
		Long sysUserId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(user_id), 0) + 1 FROM sys_user FOR UPDATE",
				Long.class);
		Long roleId = jdbcTemplate.queryForObject("SELECT role_id FROM sys_role WHERE role_code = 'OWNER'", Long.class);
		String hash = new BCryptPasswordEncoder(10).encode(password);
		String userId = UUID.randomUUID().toString();

		jdbcTemplate.update("""
				INSERT INTO sys_user
					(user_id, username, password, phone, nickname, name, create_by, create_time,
					 lock_flag, password_expire_flag, del_flag, dept_id)
				VALUES (?, ?, ?, NULL, '老板', '老板', 'bootstrap', NOW(), '0', '0', '0', 1)
				""", sysUserId, loginName, hash);
		jdbcTemplate.update("""
				INSERT INTO users
					(id, sys_user_id, login_name, display_name, phone, password_hash, status,
					 created_at, updated_at, version, must_change_password)
				VALUES (?, ?, ?, '老板', NULL, ?, 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 1, 1)
				""", userId, sysUserId, loginName, hash);
		jdbcTemplate.update("INSERT INTO user_roles (user_id, role_code, granted_by) VALUES (?, 'OWNER', NULL)", userId);
		jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", sysUserId, roleId);
		jdbcTemplate.update("""
				INSERT INTO audit_logs (entity_type, entity_id, action, actor_id, after_data)
				VALUES ('USER', ?, 'BOOTSTRAP_OWNER', NULL, JSON_OBJECT('loginName', ?, 'roles', JSON_ARRAY('OWNER')))
				""", userId, loginName);
		log.warn("已创建首个 OWNER 账号 {}（首次登录强制改密）。改密后请从 .env 删除 MOLD_BOOTSTRAP_OWNER_PASSWORD", loginName);
	}

}
