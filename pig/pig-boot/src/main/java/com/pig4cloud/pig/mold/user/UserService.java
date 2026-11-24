/*
 * 模具加工管理系统 M2 后端切片
 * G2 账号与角色（契约 6.4）：
 *   列表 / 详情 / 新增 / 改名与启停 / 角色分配 / 密码重置 / 通讯录 / 角色能力。
 *
 * 约束：
 *  - 账号管理只授予 OWNER（mold:user:*），其余角色 403；
 *  - 密码只在创建与重置时写入，任何出参不回显密码或哈希；
 *  - 不可停用自己、不可移除自己的 OWNER、不可让系统失去最后一个有效 OWNER；
 *  - 角色写 user_roles（业务事实来源）同时同步 sys_user_role（Pig 权限来源），
 *    否则角色改了但接口权限不变；
 *  - 所有写操作在业务事务内调用 AuditWriter，写失败整体回滚。
 */
package com.pig4cloud.pig.mold.user;

import com.pig4cloud.pig.common.core.constant.CacheConstants;
import com.pig4cloud.pig.mold.audit.AuditWriter;
import com.pig4cloud.pig.mold.security.MoldActor;
import com.pig4cloud.pig.mold.security.MoldActorResolver;
import com.pig4cloud.pig.mold.web.MoldApiException;
import com.pig4cloud.pig.mold.web.MoldResponses;
import com.pig4cloud.pig.mold.web.MoldTime;
import com.pig4cloud.pig.mold.web.PageQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UserService {

	public static final Set<String> ROLE_CODES = new LinkedHashSet<>(
			List.of("OWNER", "PM", "PROGRAMMER", "MACHINIST", "FINANCE", "VIEWER"));

	public static final Set<String> SORTABLE = Set.of("loginName", "displayName", "status");

	private static final Map<String, String> SORT_COLUMNS = Map.of("loginName", "u.login_name", "displayName",
			"u.display_name", "status", "u.status");

	private static final Map<String, String> ROLE_LABELS = Map.of("OWNER", "老板/管理员", "PM", "项目负责人", "PROGRAMMER",
			"编程", "MACHINIST", "操机", "FINANCE", "财务", "VIEWER", "只读");

	private static final Pattern LOGIN_NAME = Pattern.compile("[A-Za-z0-9._-]{3,64}");

	private static final Set<String> COMMON_PASSWORDS = Set.of("password1234", "password12345", "password123456",
			"password!234", "p@ssw0rd1234", "123456789012", "1234567890123", "12345678901234", "111111111111",
			"000000000000", "666666666666", "888888888888", "123123123123", "abcabcabcabc", "qwertyuiop12",
			"qwertyuiop123", "qwerty123456", "qazwsxedcrfv", "1qaz2wsx3edc", "asdfghjkl123", "zxcvbnm12345",
			"qwe123456789", "abc123456789", "abcd12345678", "admin123456", "admin1234567", "administrator",
			"woaini1314520", "a123456789012", "iloveyou1234", "letmein12345", "welcome12345", "monkey123456",
			"dragon123456", "sunshine1234", "princess1234", "football1234", "baseball1234", "master123456");

	private static final String TEMP_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(10);

	private final JdbcTemplate jdbcTemplate;

	private final AuditWriter auditWriter;

	private final MoldActorResolver actorResolver;

	private final CacheManager cacheManager;

	public Map<String, Object> list(PageQuery page, String status, String keyword) {
		List<String> conditions = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		conditions.add("u.status IS NOT NULL");
		if (StringUtils.hasText(status)) {
			conditions.add("u.status = ?");
			args.add(status);
		}
		if (StringUtils.hasText(keyword)) {
			conditions.add("(u.login_name LIKE ? OR u.display_name LIKE ?)");
			String like = "%" + keyword.trim() + "%";
			args.add(like);
			args.add(like);
		}
		String where = " WHERE " + String.join(" AND ", conditions);

		Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users u" + where, Long.class, args.toArray());
		String direction = "desc".equals(page.order()) ? "DESC" : "ASC";
		String sql = """
				SELECT u.id, u.login_name, u.display_name, u.phone, u.status, u.version,
				       u.created_at, u.updated_at, u.last_login_at,
				       (SELECT GROUP_CONCAT(ur.role_code ORDER BY ur.role_code)
				          FROM user_roles ur WHERE ur.user_id = u.id) AS roles
				FROM users u
				""" + where + " ORDER BY " + SORT_COLUMNS.get(page.sort()) + " " + direction + ", u.id " + direction
				+ " LIMIT ? OFFSET ?";

		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(page.pageSize());
		pageArgs.add((long) (page.pageNum() - 1) * page.pageSize());

		List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, pageArgs.toArray())
			.stream()
			.map(this::dto)
			.toList();
		return MoldResponses.page(rows, total == null ? 0L : total, page.pageNum(), page.pageSize(), dataVersion());
	}

	public Map<String, Object> get(String userId) {
		return dto(loadRow(userId, false));
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> create(Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		rejectUnknownFields(body, Set.of("loginName", "displayName", "phone", "password", "roles"));

		String loginName = text(body.get("loginName"), "loginName", 64);
		if (!LOGIN_NAME.matcher(loginName).matches()) {
			throw MoldApiException.validation("/body/loginName", "pattern", Map.of("format", "3-64 位字母数字._-"),
					"loginName 只能包含字母、数字、点、下划线和短划线，长度 3-64");
		}
		String displayName = text(body.get("displayName"), "displayName", 128);
		String phone = optionalText(body.get("phone"), "phone", 20);
		String password = passwordPolicy(body.get("password"), "password", loginName, displayName);
		List<String> roles = roleList(body.get("roles"));

		Long existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE login_name = ?", Long.class,
				loginName);
		if (existing != null && existing > 0) {
			throw new MoldApiException(409, "LOGIN_NAME_DUPLICATE", "登录名已存在");
		}

		Long sysUserId = jdbcTemplate.queryForObject(
				"SELECT COALESCE(MAX(user_id), 0) + 1 FROM sys_user FOR UPDATE", Long.class);
		if (sysUserId == null) {
			throw new MoldApiException(500, "INTERNAL_ERROR", "无法分配登录账号 ID");
		}
		String hash = PASSWORD_ENCODER.encode(password);
		String userId = UUID.randomUUID().toString();

		try {
			jdbcTemplate.update("""
					INSERT INTO sys_user
						(user_id, username, password, phone, nickname, name, create_by, create_time,
						 lock_flag, password_expire_flag, del_flag, dept_id)
					VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(), '0', '0', '0', 1)
					""", sysUserId, loginName, hash, phone, displayName, displayName, actor.loginName());

			jdbcTemplate.update("""
					INSERT INTO users
						(id, sys_user_id, login_name, display_name, phone, password_hash, status,
						 created_at, updated_at, version, must_change_password)
					VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 1, 1)
					""", userId, sysUserId, loginName, displayName, phone, hash);

			applyRoles(userId, sysUserId, roles, actor.userId());
		}
		catch (DuplicateKeyException ex) {
			throw new MoldApiException(409, "LOGIN_NAME_DUPLICATE", "登录名已存在");
		}

		Map<String, Object> after = dto(loadRow(userId, false));
		auditWriter.record("USER", userId, "CREATE", null, after);
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> update(String userId, Integer expectedVersion, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> beforeRow = loadRow(userId, true);
		checkVersion(expectedVersion, number(beforeRow.get("version")));
		rejectUnknownFields(body, Set.of("displayName", "status", "phone"));

		String oldStatus = (String) beforeRow.get("status");
		List<String> oldRoles = rolesOf(userId);
		String displayName = body.containsKey("displayName") ? text(body.get("displayName"), "displayName", 128)
				: (String) beforeRow.get("display_name");
		String phone = body.containsKey("phone") ? optionalText(body.get("phone"), "phone", 20)
				: (String) beforeRow.get("phone");
		String status = body.containsKey("status") ? status(body.get("status")) : oldStatus;

		if (userId.equals(actor.userId()) && "DISABLED".equals(status) && !"DISABLED".equals(oldStatus)) {
			throw new MoldApiException(409, "SELF_DISABLE_DENIED", "不能停用自己");
		}
		assertLastOwner(userId, oldStatus, oldRoles, status, oldRoles);

		jdbcTemplate.update("""
				UPDATE users
				   SET display_name = ?, phone = ?, status = ?, version = version + 1,
				       updated_at = UTC_TIMESTAMP(3)
				 WHERE id = ?
				""", displayName, phone, status, userId);
		jdbcTemplate.update("""
				UPDATE sys_user SET nickname = ?, name = ?, lock_flag = ? WHERE user_id = ?
				""", displayName, displayName, "DISABLED".equals(status) ? "9" : "0",
				beforeRow.get("sys_user_id"));
		// 启停必须同时清 Pig 的登录缓存：UserInfo 是永久缓存（无 TTL），
		// 不清就会出现「停用后仍能登录」和「重新启用后仍登不进」两种反向故障。
		evictUserDetails((String) beforeRow.get("login_name"));

		Map<String, Object> after = dto(loadRow(userId, false));
		auditWriter.record("USER", userId, "UPDATE", dto(beforeRow), after);
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> setRoles(String userId, Integer expectedVersion, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		Map<String, Object> beforeRow = loadRow(userId, true);
		checkVersion(expectedVersion, number(beforeRow.get("version")));
		rejectUnknownFields(body, Set.of("roles"));

		List<String> oldRoles = rolesOf(userId);
		List<String> nextRoles = roleList(body.get("roles"));
		String status = (String) beforeRow.get("status");

		if (userId.equals(actor.userId()) && !nextRoles.contains("OWNER") && oldRoles.contains("OWNER")) {
			throw new MoldApiException(409, "SELF_OWNER_REMOVAL_DENIED", "不能移除自己的 OWNER 角色");
		}
		assertLastOwner(userId, status, oldRoles, status, nextRoles);

		applyRoles(userId, ((Number) beforeRow.get("sys_user_id")).longValue(), nextRoles, actor.userId());
		jdbcTemplate.update("UPDATE users SET version = version + 1, updated_at = UTC_TIMESTAMP(3) WHERE id = ?",
				userId);
		// 改角色必须清 Pig 的登录缓存：UserInfo 里带着按角色算出来的 permissions 数组，
		// 且资源服务器每个请求都从该缓存重建 authorities。不清缓存时，改完角色在界面上
		// 显示成功，但对方（含已登录会话）的权限一直沿用旧值，直到缓存被别的原因清掉。
		evictUserDetails((String) beforeRow.get("login_name"));

		Map<String, Object> after = dto(loadRow(userId, false));
		auditWriter.record("USER", userId, "SET_ROLES", dto(beforeRow), after);
		return after;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> resetPassword(String userId, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		if (userId.equals(actor.userId())) {
			throw new MoldApiException(409, "SELF_PASSWORD_RESET_DENIED", "不能重置自己的密码，请使用自助改密");
		}
		Map<String, Object> beforeRow = loadRow(userId, true);
		boolean generated = body == null || body.isEmpty() || Boolean.TRUE.equals(body.get("generate"));
		String password;
		if (generated) {
			password = temporaryPassword();
		}
		else {
			rejectUnknownFields(body, Set.of("password", "generate"));
			password = passwordPolicy(body.get("password"), "password", (String) beforeRow.get("login_name"),
					(String) beforeRow.get("display_name"));
		}

		String hash = PASSWORD_ENCODER.encode(password);
		jdbcTemplate.update("""
				UPDATE users
				   SET password_hash = ?, must_change_password = 1, password_changed_at = UTC_TIMESTAMP(3),
				       version = version + 1, updated_at = UTC_TIMESTAMP(3)
				 WHERE id = ?
				""", hash, userId);
		jdbcTemplate.update("""
				UPDATE sys_user
				   SET password = ?, password_expire_flag = '0',
				       password_modify_time = UTC_TIMESTAMP()
				 WHERE user_id = ?
				""", hash, beforeRow.get("sys_user_id"));
		evictUserDetails((String) beforeRow.get("login_name"));

		Map<String, Object> after = dto(loadRow(userId, false));
		Map<String, Object> auditAfter = new LinkedHashMap<>();
		auditAfter.put("targetUserId", userId);
		auditAfter.put("generated", generated);
		auditAfter.put("mustChangePassword", true);
		auditAfter.put("version", after.get("version"));
		auditWriter.record("USER", userId, "RESET_PASSWORD", dto(beforeRow), auditAfter);

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("userId", userId);
		data.put("mustChangePassword", true);
		if (generated) {
			data.put("temporaryPassword", password);
		}
		return data;
	}

	/**
	 * 自助改密（G-04）：校验旧口令 + 复用账号密码策略；成功后 mustChangePassword=false。
	 * 同一 access token 不吊销（Redis token 与密码无关），因此改密后可直接继续调用业务接口。
	 */
	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> changeOwnPassword(String oldPassword, String newPassword) {
		MoldActor actor = actorResolver.current();
		if (!StringUtils.hasText(oldPassword)) {
			throw MoldApiException.validation("/body/oldPassword", "required", null, "oldPassword 不能为空");
		}
		if (!StringUtils.hasText(newPassword)) {
			throw MoldApiException.validation("/body/newPassword", "required", null, "newPassword 不能为空");
		}

		Map<String, Object> beforeRow = loadRow(actor.userId(), true);
		String currentHash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class,
				actor.userId());
		if (!StringUtils.hasText(currentHash) || !PASSWORD_ENCODER.matches(oldPassword, currentHash)) {
			throw MoldApiException.validation("/body/oldPassword", "invalid", null, "旧密码不正确");
		}

		String next = passwordPolicy(newPassword, "newPassword", (String) beforeRow.get("login_name"),
				(String) beforeRow.get("display_name"));
		if (PASSWORD_ENCODER.matches(next, currentHash)) {
			throw MoldApiException.validation("/body/newPassword", "not", Map.of("not", "oldPassword"),
					"新密码不能与旧密码相同");
		}

		String hash = PASSWORD_ENCODER.encode(next);
		jdbcTemplate.update("""
				UPDATE users
				   SET password_hash = ?, must_change_password = 0, password_changed_at = UTC_TIMESTAMP(3),
				       version = version + 1, updated_at = UTC_TIMESTAMP(3)
				 WHERE id = ?
				""", hash, actor.userId());
		jdbcTemplate.update("""
				UPDATE sys_user
				   SET password = ?, password_expire_flag = '0',
				       password_modify_time = UTC_TIMESTAMP()
				 WHERE user_id = ?
				""", hash, beforeRow.get("sys_user_id"));
		evictUserDetails((String) beforeRow.get("login_name"));

		Map<String, Object> after = dto(loadRow(actor.userId(), false));
		after.put("mustChangePassword", false);
		auditWriter.record("USER", actor.userId(), "CHANGE_PASSWORD", dto(beforeRow), after);

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("userId", actor.userId());
		data.put("mustChangePassword", false);
		data.put("changedAt", MoldTime.nowIso());
		return data;
	}

	/**
	 * Pig 的登录主表 sys_user 走 Redis 缓存（CacheConstants.USER_DETAILS，key 为登录名）。
	 * 改密/重置后必须失效缓存，否则旧口令仍能登录、新口令反而失败。
	 */
	private void evictUserDetails(String loginName) {
		if (!StringUtils.hasText(loginName)) {
			return;
		}
		Cache cache = cacheManager.getCache(CacheConstants.USER_DETAILS);
		if (cache != null) {
			cache.evict(loginName);
		}
	}

	public Map<String, Object> directory(String keyword) {
		List<Object> args = new ArrayList<>();
		String sql = "SELECT id, display_name FROM users WHERE status = 'ACTIVE'";
		if (StringUtils.hasText(keyword)) {
			sql += " AND (login_name LIKE ? OR display_name LIKE ?)";
			String like = "%" + keyword.trim() + "%";
			args.add(like);
			args.add(like);
		}
		sql += " ORDER BY display_name, id";
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray()).stream().map(row -> {
			Map<String, Object> dto = new LinkedHashMap<>();
			dto.put("id", row.get("id"));
			dto.put("displayName", row.get("display_name"));
			return dto;
		}).toList();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("rows", rows);
		data.put("total", rows.size());
		return data;
	}

	public Map<String, Object> roleCapabilities() {
		MoldActor actor = actorResolver.current();
		List<Map<String, Object>> roles = new ArrayList<>();
		for (String code : actor.roles()) {
			List<String> capabilities = jdbcTemplate.queryForList("""
					SELECT DISTINCT m.permission
					FROM sys_role r
					JOIN sys_role_menu rm ON rm.role_id = r.role_id
					JOIN sys_menu m ON m.menu_id = rm.menu_id
					WHERE r.role_code = ? AND m.permission IS NOT NULL AND m.permission <> '' AND m.del_flag = '0'
					ORDER BY m.permission
					""", String.class, code);
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("code", code);
			item.put("label", ROLE_LABELS.getOrDefault(code, code));
			item.put("capabilities", capabilities);
			item.put("canManageUsers", capabilities.contains("mold:user:list"));
			item.put("hasFinanceAccess", capabilities.contains("mold:cash:list"));
			item.put("highRisk", "OWNER".equals(code) || "FINANCE".equals(code));
			roles.add(item);
		}
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("roles", roles);
		data.put("fixed", true);
		data.put("combine", "UNION");
		return data;
	}

	private void applyRoles(String userId, Long sysUserId, List<String> roles, String grantedBy) {
		jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", sysUserId);
		for (String role : roles) {
			Long roleId = jdbcTemplate.queryForObject("SELECT role_id FROM sys_role WHERE role_code = ?", Long.class,
					role);
			if (roleId == null) {
				throw new MoldApiException(500, "INTERNAL_ERROR", "角色未初始化: " + role);
			}
			jdbcTemplate.update("INSERT INTO user_roles (user_id, role_code, granted_by) VALUES (?, ?, ?)", userId,
					role, grantedBy);
			jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", sysUserId, roleId);
		}
	}

	private void assertLastOwner(String userId, String oldStatus, List<String> oldRoles, String nextStatus,
			List<String> nextRoles) {
		boolean wasActiveOwner = "ACTIVE".equals(oldStatus) && oldRoles.contains("OWNER");
		if (!wasActiveOwner) {
			return;
		}
		boolean stillActiveOwner = "ACTIVE".equals(nextStatus) && nextRoles.contains("OWNER");
		if (stillActiveOwner) {
			return;
		}
		Long others = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM users u
				JOIN user_roles r ON r.user_id = u.id
				WHERE u.status = 'ACTIVE' AND r.role_code = 'OWNER' AND u.id <> ?
				""", Long.class, userId);
		if (others == null || others == 0) {
			throw new MoldApiException(409, "LAST_OWNER_PROTECTED", "不能移除或停用最后一个有效 OWNER");
		}
	}

	private List<String> roleList(Object value) {
		if (!(value instanceof List<?> raw) || raw.isEmpty()) {
			throw MoldApiException.validation("/body/roles", "required", null, "至少分配一个角色");
		}
		List<String> roles = new ArrayList<>();
		for (Object item : raw) {
			String code = item == null ? null : item.toString().trim();
			if (code == null || !ROLE_CODES.contains(code)) {
				throw MoldApiException.validation("/body/roles", "enum",
						Map.of("allowedValues", List.copyOf(ROLE_CODES)), "角色无效");
			}
			if (roles.contains(code)) {
				throw MoldApiException.validation("/body/roles", "uniqueItems", null, "角色不能重复");
			}
			roles.add(code);
		}
		return roles;
	}

	private String passwordPolicy(Object value, String field, String loginName, String displayName) {
		if (!(value instanceof String password) || password.length() < 12 || password.length() > 128) {
			throw MoldApiException.validation("/body/" + field, "minLength", Map.of("min", 12, "max", 128),
					"密码需 12 至 128 字符");
		}
		String lower = password.toLowerCase();
		if (loginName != null && lower.equals(loginName.toLowerCase())) {
			throw MoldApiException.validation("/body/" + field, "not", Map.of("not", "loginName"),
					"密码不得与登录名相同");
		}
		if (displayName != null && lower.equals(displayName.toLowerCase())) {
			throw MoldApiException.validation("/body/" + field, "not", Map.of("not", "displayName"),
					"密码不得与显示名相同");
		}
		if (COMMON_PASSWORDS.contains(lower)) {
			throw MoldApiException.validation("/body/" + field, "notCommon", null, "该密码过于常见，请更换");
		}
		if (password.chars().distinct().count() == 1) {
			throw MoldApiException.validation("/body/" + field, "pattern", null, "密码不能是单一字符重复");
		}
		if (hasLongRun(password)) {
			throw MoldApiException.validation("/body/" + field, "pattern", null, "密码不能是连续字符序列");
		}
		return password;
	}

	private static boolean hasLongRun(String password) {
		int run = 1;
		for (int i = 1; i < password.length(); i++) {
			int delta = password.charAt(i) - password.charAt(i - 1);
			run = (delta == 1 || delta == -1) ? run + 1 : 1;
			if (run >= 8) {
				return true;
			}
		}
		return false;
	}

	private static String temporaryPassword() {
		StringBuilder builder = new StringBuilder(20);
		for (int i = 0; i < 20; i++) {
			builder.append(TEMP_ALPHABET.charAt(RANDOM.nextInt(TEMP_ALPHABET.length())));
		}
		return builder.toString();
	}

	private Map<String, Object> loadRow(String userId, boolean forUpdate) {
		String sql = """
				SELECT u.id, u.sys_user_id, u.login_name, u.display_name, u.phone, u.status, u.version,
				       u.created_at, u.updated_at, u.last_login_at,
				       (SELECT GROUP_CONCAT(ur.role_code ORDER BY ur.role_code)
				          FROM user_roles ur WHERE ur.user_id = u.id) AS roles
				FROM users u WHERE u.id = ?
				""" + (forUpdate ? " FOR UPDATE" : "");
		try {
			return jdbcTemplate.queryForMap(sql, userId);
		}
		catch (EmptyResultDataAccessException ex) {
			throw new MoldApiException(404, "NOT_FOUND", "账号不存在");
		}
	}

	private List<String> rolesOf(String userId) {
		return jdbcTemplate.queryForList("SELECT role_code FROM user_roles WHERE user_id = ? ORDER BY role_code",
				String.class, userId);
	}

	private Map<String, Object> dto(Map<String, Object> row) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", row.get("id"));
		dto.put("loginName", row.get("login_name"));
		dto.put("displayName", row.get("display_name"));
		dto.put("phone", row.get("phone"));
		String roles = (String) row.get("roles");
		dto.put("roles", roles == null || roles.isEmpty() ? List.of() : List.of(roles.split(",")));
		dto.put("status", row.get("status"));
		dto.put("version", number(row.get("version")));
		dto.put("createdAt", MoldTime.iso(row.get("created_at")));
		dto.put("updatedAt", MoldTime.iso(row.get("updated_at")));
		dto.put("lastLoginAt", MoldTime.iso(row.get("last_login_at")));
		return dto;
	}

	private void checkVersion(Integer expectedVersion, int currentVersion) {
		if (expectedVersion == null) {
			throw MoldApiException.validation("/header/If-Match", "required", null, "缺少 If-Match 版本号");
		}
		if (expectedVersion != currentVersion) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("current", currentVersion);
			throw new MoldApiException(409, "VERSION_CONFLICT", "版本冲突，请刷新后重试", details);
		}
	}

	private void rejectUnknownFields(Map<String, Object> body, Set<String> allowed) {
		if (body == null) {
			return;
		}
		for (String key : body.keySet()) {
			if (!allowed.contains(key)) {
				throw MoldApiException.validation("/body/" + key, "additionalProperties", null,
						"不允许通过该接口修改字段 " + key);
			}
		}
	}

	private static String status(Object value) {
		String status = value == null ? null : value.toString().trim();
		if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
			throw MoldApiException.validation("/body/status", "enum", Map.of("allowedValues", List.of("ACTIVE", "DISABLED")),
					"账号状态只能是 ACTIVE 或 DISABLED");
		}
		return status;
	}

	private static String text(Object value, String field, int maxLength) {
		String text = value == null ? null : value.toString().trim();
		if (!StringUtils.hasText(text)) {
			throw MoldApiException.validation("/body/" + field, "required", null, field + " 不能为空");
		}
		if (text.length() > maxLength) {
			throw MoldApiException.validation("/body/" + field, "maxLength", Map.of("limit", maxLength),
					field + " 长度不能超过 " + maxLength);
		}
		return text;
	}

	private static String optionalText(Object value, String field, int maxLength) {
		if (value == null || !StringUtils.hasText(value.toString())) {
			return null;
		}
		String text = value.toString().trim();
		if (text.length() > maxLength) {
			throw MoldApiException.validation("/body/" + field, "maxLength", Map.of("limit", maxLength),
					field + " 长度不能超过 " + maxLength);
		}
		return text;
	}

	private String dataVersion() {
		Long version = jdbcTemplate.queryForObject("SELECT version FROM event_clock WHERE singleton = 1", Long.class);
		return String.valueOf(version == null ? 0L : version);
	}

	private static int number(Object value) {
		return value == null ? 0 : ((Number) value).intValue();
	}

}
