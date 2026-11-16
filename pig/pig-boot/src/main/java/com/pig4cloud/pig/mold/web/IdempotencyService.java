/*
 * 模具加工管理系统 M2 后端切片
 * 写接口幂等键（契约 6.1）：Idempotency-Key 8–128 字符，窗口 24 小时。
 *
 *  - 同 key + 同请求体：不重复执行业务，直接返回首次响应并标记 Idempotent-Replayed: true；
 *  - 同 key + 不同请求体：409 IDEMPOTENCY_KEY_REUSED；
 *  - key 已过期：409 IDEMPOTENCY_EXPIRED；
 *  - 业务失败时整事务回滚，PENDING 记录一并回滚，失败请求不占用 key。
 *
 * 业务动作、idempotency_keys 与 audit_logs 处于同一事务，保证“重复提交不产生第二条审计”。
 */
package com.pig4cloud.pig.mold.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pig4cloud.pig.mold.security.MoldActor;
import com.pig4cloud.pig.mold.security.MoldActorResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

	public static final int MIN_KEY_LENGTH = 8;

	public static final int MAX_KEY_LENGTH = 128;

	private static final int EXPIRY_HOURS = 24;

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	private final MoldActorResolver actorResolver;

	/**
	 * 一次写操作的结果：data 为首次响应体（重放时来自 idempotency_keys.response_body）。
	 */
	public record Outcome(Map<String, Object> data, boolean replayed) {
	}

	/**
	 * 契约标记“幂等必填”的写接口使用：缺少 Idempotency-Key 直接 422。
	 */
	@Transactional(rollbackFor = Exception.class)
	public Outcome executeRequired(String scope, String key, String variant, Map<String, Object> body,
			int successStatus, Supplier<Map<String, Object>> action) {
		if (!StringUtils.hasText(key)) {
			throw MoldApiException.validation("/header/Idempotency-Key", "required", null, "缺少 Idempotency-Key");
		}
		return execute(scope, key, variant, body, successStatus, action);
	}

	@Transactional(rollbackFor = Exception.class)
	public Outcome execute(String scope, String key, String variant, Map<String, Object> body, int successStatus,
			Supplier<Map<String, Object>> action) {
		if (!StringUtils.hasText(key)) {
			return new Outcome(action.get(), false);
		}
		validateKey(key);

		MoldActor actor = actorResolver.current();
		String hash = sha256(scope + "\n" + (variant == null ? "" : variant) + "\n" + canonicalJson(body));

		boolean inserted;
		try {
			jdbcTemplate.update("""
					INSERT INTO idempotency_keys (user_id, scope, `key`, request_hash, state)
					VALUES (?, ?, ?, ?, 'PENDING')
					""", actor.userId(), scope, key, hash);
			inserted = true;
		}
		catch (DuplicateKeyException ex) {
			inserted = false;
		}

		if (inserted) {
			Map<String, Object> data = action.get();
			String completeSql = "UPDATE idempotency_keys SET state = 'COMPLETED', response_status = ?, "
					+ "response_body = ?, target_resource_id = ?, completed_at = UTC_TIMESTAMP(3), "
					+ "response_expires_at = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL " + EXPIRY_HOURS + " HOUR) "
					+ "WHERE user_id = ? AND scope = ? AND `key` = ?";
			jdbcTemplate.update(completeSql, successStatus, canonicalJson(data), resourceId(data), actor.userId(),
					scope, key);
			return new Outcome(data, false);
		}

		Map<String, Object> existing = loadExisting(actor.userId(), scope, key);
		if (existing == null) {
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "同一幂等键的首次请求仍在处理中，请稍后重试");
		}
		if (!hash.equals(existing.get("request_hash"))) {
			throw new MoldApiException(409, "IDEMPOTENCY_KEY_REUSED", "同一 Idempotency-Key 不能用于不同请求体");
		}
		if (isExpired(existing.get("response_expires_at"))) {
			throw new MoldApiException(409, "IDEMPOTENCY_EXPIRED", "幂等键已过期，请使用新的 Idempotency-Key");
		}
		if (!"COMPLETED".equals(existing.get("state"))) {
			throw new MoldApiException(409, "INVALID_STATE_TRANSITION", "同一幂等键的首次请求仍在处理中，请稍后重试");
		}
		return new Outcome(parseBody(existing.get("response_body")), true);
	}

	private Map<String, Object> loadExisting(String userId, String scope, String key) {
		// 不加 FOR UPDATE：失败的 INSERT 在 MySQL 会留下索引锁，再取排他锁会与并发事务成环死锁。
		// 状态只会 PENDING → COMPLETED 单向推进；读到 PENDING 时由调用方返回 409，不需要行锁。
		List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
				SELECT request_hash, state, response_status, response_body, response_expires_at
				FROM idempotency_keys
				WHERE user_id = ? AND scope = ? AND `key` = ?
				""", userId, scope, key);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private static boolean isExpired(Object value) {
		if (!(value instanceof Timestamp timestamp)) {
			return false;
		}
		return timestamp.toInstant().isBefore(Instant.now());
	}

	private Map<String, Object> parseBody(Object raw) {
		if (raw == null) {
			throw new IllegalStateException("幂等记录缺少响应体");
		}
		try {
			return objectMapper.readValue(raw.toString(), new TypeReference<LinkedHashMap<String, Object>>() {
			});
		}
		catch (Exception ex) {
			throw new IllegalStateException("幂等响应体解析失败", ex);
		}
	}

	private static String resourceId(Map<String, Object> data) {
		if (data == null) {
			return null;
		}
		Object id = data.get("id");
		return id == null ? null : id.toString();
	}

	private String canonicalJson(Map<String, Object> body) {
		try {
			return objectMapper.writer()
				.with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
				.writeValueAsString(body);
		}
		catch (Exception ex) {
			throw new IllegalStateException("幂等请求体序列化失败", ex);
		}
	}

	static void validateKey(String key) {
		String trimmed = key.trim();
		if (trimmed.length() < MIN_KEY_LENGTH || trimmed.length() > MAX_KEY_LENGTH) {
			throw MoldApiException.validation("/header/Idempotency-Key", "length",
					Map.of("min", MIN_KEY_LENGTH, "max", MAX_KEY_LENGTH), "Idempotency-Key 长度必须为 8–128 字符");
		}
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(bytes.length * 2);
			for (byte b : bytes) {
				builder.append(Character.forDigit((b >> 4) & 0xF, 16));
				builder.append(Character.forDigit(b & 0xF, 16));
			}
			return builder.toString();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 不可用", ex);
		}
	}

}
