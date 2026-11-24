/*
 * 模具加工管理系统 M2 后端切片
 * 登录限流（用户决策 ④）：
 *  - 账号 + IP 维度 30 次/分钟；
 *  - 单 IP 跨账号总尝试 60 次/分钟（生产加固：防口令喷洒——每个账号只试几次绕过账号锁定）；
 *  - 同一账号连续失败 5 次锁定 15 分钟；
 *  - 超限返回 429 LOGIN_RATE_LIMITED 并带 Retry-After。
 * 计数用 Lua 原子完成 INCR + 设置过期，避免 INCR 成功、EXPIRE 失败留下无 TTL 的永久计数。
 * 登录名统一按小写计数（users.login_name 为大小写不敏感排序规则，避免换大小写绕过锁定）。
 * 说明：M1 契约 6.3 写的是“账号 10 次/分 + IP 60 次/分”，阈值可由 mold.auth.* 配置覆盖。
 */
package com.pig4cloud.pig.mold.auth;

import com.pig4cloud.pig.mold.web.MoldApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

	private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
			"local c = redis.call('INCR', KEYS[1]) "
					+ "if c == 1 or redis.call('PTTL', KEYS[1]) < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
					+ "return c",
			Long.class);

	private final StringRedisTemplate redisTemplate;

	@Value("${mold.auth.rate-limit-per-minute:30}")
	private int rateLimitPerMinute;

	@Value("${mold.auth.ip-rate-limit-per-minute:60}")
	private int ipRateLimitPerMinute;

	@Value("${mold.auth.max-failures:5}")
	private int maxFailures;

	@Value("${mold.auth.lock-minutes:15}")
	private int lockMinutes;

	public void check(String loginName, String ip) {
		String name = normalize(loginName);
		String lockKey = "mold:login:lock:" + name;
		if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
			throw new MoldApiException(429, "LOGIN_RATE_LIMITED", "账号已锁定，请稍后再试",
					Map.of("reason", "ACCOUNT_LOCKED"), ttlSeconds(lockKey));
		}

		String ipKey = "mold:login:ip:" + ip;
		if (increment(ipKey, Duration.ofMinutes(1)) > ipRateLimitPerMinute) {
			throw tooMany(ipKey, ipRateLimitPerMinute);
		}

		String rateKey = "mold:login:rate:" + name + ":" + ip;
		if (increment(rateKey, Duration.ofMinutes(1)) > rateLimitPerMinute) {
			throw tooMany(rateKey, rateLimitPerMinute);
		}
	}

	public void onFailure(String loginName) {
		String name = normalize(loginName);
		String failKey = "mold:login:fail:" + name;
		if (increment(failKey, Duration.ofMinutes(lockMinutes)) >= maxFailures) {
			redisTemplate.opsForValue().set("mold:login:lock:" + name, "1", Duration.ofMinutes(lockMinutes));
			redisTemplate.delete(failKey);
		}
	}

	public void onSuccess(String loginName) {
		redisTemplate.delete("mold:login:fail:" + normalize(loginName));
	}

	static String normalize(String loginName) {
		return loginName == null ? "" : loginName.trim().toLowerCase(Locale.ROOT);
	}

	private long increment(String key, Duration ttl) {
		Long count = redisTemplate.execute(INCR_WITH_TTL, List.of(key), String.valueOf(ttl.toMillis()));
		return count == null ? 0L : count;
	}

	private MoldApiException tooMany(String key, int limit) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("reason", "TOO_MANY_ATTEMPTS");
		details.put("limit", limit);
		return new MoldApiException(429, "LOGIN_RATE_LIMITED", "登录过于频繁，请稍后再试", details, ttlSeconds(key));
	}

	private Long ttlSeconds(String key) {
		Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
		return ttl == null || ttl < 1 ? 60L : ttl;
	}

}
