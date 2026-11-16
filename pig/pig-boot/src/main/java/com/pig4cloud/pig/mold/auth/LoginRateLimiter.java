/*
 * 模具加工管理系统 M2 后端切片
 * 登录限流（用户决策 ④）：
 *  - 账号 + IP 维度 30 次/分钟；
 *  - 同一账号连续失败 5 次锁定 15 分钟；
 *  - 超限返回 429 LOGIN_RATE_LIMITED 并带 Retry-After。
 * 说明：M1 契约 6.3 写的是“账号 10 次/分 + IP 60 次/分”，与用户决策 ④ 的 30 次/分不一致，
 * 本切片按用户决策实现，阈值可由 mold.auth.* 配置覆盖，待 01/03 核签时统一。
 */
package com.pig4cloud.pig.mold.auth;

import com.pig4cloud.pig.mold.web.MoldApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

	private final StringRedisTemplate redisTemplate;

	@Value("${mold.auth.rate-limit-per-minute:30}")
	private int rateLimitPerMinute;

	@Value("${mold.auth.max-failures:5}")
	private int maxFailures;

	@Value("${mold.auth.lock-minutes:15}")
	private int lockMinutes;

	public void check(String loginName, String ip) {
		String lockKey = "mold:login:lock:" + loginName;
		if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
			throw new MoldApiException(429, "LOGIN_RATE_LIMITED", "账号已锁定，请稍后再试",
					Map.of("reason", "ACCOUNT_LOCKED"), ttlSeconds(lockKey));
		}

		String rateKey = "mold:login:rate:" + loginName + ":" + ip;
		Long count = redisTemplate.opsForValue().increment(rateKey);
		if (count != null && count == 1L) {
			redisTemplate.expire(rateKey, Duration.ofMinutes(1));
		}
		if (count != null && count > rateLimitPerMinute) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("reason", "TOO_MANY_ATTEMPTS");
			details.put("limit", rateLimitPerMinute);
			throw new MoldApiException(429, "LOGIN_RATE_LIMITED", "登录过于频繁，请稍后再试", details,
					ttlSeconds(rateKey));
		}
	}

	public void onFailure(String loginName) {
		String failKey = "mold:login:fail:" + loginName;
		Long count = redisTemplate.opsForValue().increment(failKey);
		if (count != null && count == 1L) {
			redisTemplate.expire(failKey, Duration.ofMinutes(lockMinutes));
		}
		if (count != null && count >= maxFailures) {
			redisTemplate.opsForValue()
				.set("mold:login:lock:" + loginName, "1", Duration.ofMinutes(lockMinutes));
			redisTemplate.delete(failKey);
		}
	}

	public void onSuccess(String loginName) {
		redisTemplate.delete("mold:login:fail:" + loginName);
	}

	private Long ttlSeconds(String key) {
		Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
		return ttl == null || ttl < 1 ? 60L : ttl;
	}

}
