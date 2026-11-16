/*
 * 模具加工管理系统 M2 后端切片
 * 业务审计写入器（契约 3.1 / 7.4）：
 *  - 必须处于真实事务内，否则直接抛错（不允许脱离业务事务写审计）；
 *  - before/after 由调用方显式传入，不接受“猜”；
 *  - 插入失败向上抛出，由业务事务整体回滚。
 * 不使用 Pig 的 sys_log：它没有 before/after，且业务回滚后仍留日志。
 */
package com.pig4cloud.pig.mold.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pig4cloud.pig.common.security.service.PigUser;
import com.pig4cloud.pig.common.security.util.SecurityUtils;
import com.pig4cloud.pig.mold.web.MoldRequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditWriter {

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	private final com.pig4cloud.pig.mold.security.MoldActorResolver actorResolver;

	public void record(String entityType, String entityId, String action, Map<String, Object> before,
			Map<String, Object> after) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("AuditWriter 必须在业务事务内调用");
		}

		String actorId = resolveActorId();
		HttpServletRequest request = currentRequest();
		String requestId = request == null ? null : (String) request.getAttribute(MoldRequestIdFilter.ATTRIBUTE);
		String ip = request == null ? null : clientIp(request);
		String userAgent = request == null ? null : request.getHeader("User-Agent");

		try {
			jdbcTemplate.update("""
					INSERT INTO audit_logs
						(entity_type, entity_id, action, actor_id, request_id, before_data, after_data, diff, ip, user_agent)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""", entityType, entityId, action, actorId, requestId, toJson(before), toJson(after),
					toJson(diff(before, after)), ip, userAgent);
		}
		catch (RuntimeException ex) {
			log.error("审计写入失败，业务事务将回滚 entity={} id={} action={}", entityType, entityId, action, ex);
			throw ex;
		}
	}

	private String resolveActorId() {
		try {
			PigUser pigUser = SecurityUtils.getUser();
			if (pigUser == null || pigUser.getId() == null) {
				return null;
			}
			return actorResolver.bySysUserId(pigUser.getId()).userId();
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private HttpServletRequest currentRequest() {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
			return attributes.getRequest();
		}
		return null;
	}

	private String clientIp(HttpServletRequest request) {
		String forwarded = request.getHeader("X-Forwarded-For");
		if (StringUtils.hasText(forwarded)) {
			return forwarded.split(",")[0].trim();
		}
		return request.getRemoteAddr();
	}

	private String toJson(Object value) {
		if (value == null) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException("审计数据序列化失败", ex);
		}
	}

	private Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
		Map<String, Object> diff = new LinkedHashMap<>();
		List<String> changed = new ArrayList<>();
		if (before != null && after != null) {
			for (String key : after.keySet()) {
				if (!Objects.equals(before.get(key), after.get(key))) {
					changed.add(key);
					Map<String, Object> pair = new LinkedHashMap<>();
					pair.put("before", before.get(key));
					pair.put("after", after.get(key));
					diff.put(key, pair);
				}
			}
		}
		diff.put("changedFields", changed);
		return diff;
	}

}
