/*
 * 模具加工管理系统 M7-A / A4 SSE 接口（契约 6.10.4，1 操作）。
 *
 * GET /mold/events/stream?scope=BASIC|FINANCE，Authorization: Bearer。
 */
package com.pig4cloud.pig.mold.event;

import com.pig4cloud.pig.common.security.component.PermissionService;
import com.pig4cloud.pig.mold.security.MoldActor;
import com.pig4cloud.pig.mold.security.MoldActorResolver;
import com.pig4cloud.pig.mold.web.MoldApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class EventStreamController {

	private final EventStreamService eventStreamService;

	private final MoldActorResolver actorResolver;

	private final PermissionService permissionService;

	@GetMapping("/mold/events/stream")
	public ResponseEntity<?> stream(@RequestParam(required = false) String scope,
			@RequestParam(required = false) String lastEventId,
			@RequestHeader(value = "Last-Event-ID", required = false) String headerCursor,
			jakarta.servlet.http.HttpServletResponse response) {
		MoldActor actor = actorResolver.current();
		String resolvedScope = StringUtils.hasText(scope) ? scope.trim() : "BASIC";
		if (!"BASIC".equals(resolvedScope) && !"FINANCE".equals(resolvedScope)) {
			throw MoldApiException.validation("/query/scope", "enum", Map.of("allowedValues", java.util.List.of("BASIC",
					"FINANCE")), "参数无效 scope");
		}
		if ("FINANCE".equals(resolvedScope) && !permissionService.hasPermission("mold:cash:viewAll")) {
			throw new MoldApiException(403, "FORBIDDEN", "无此操作权限");
		}

		String rawCursor = StringUtils.hasText(headerCursor) ? headerCursor.trim()
				: (StringUtils.hasText(lastEventId) ? lastEventId.trim() : null);
		Long cursor = null;
		if (rawCursor != null) {
			if (!rawCursor.matches("\\d{1,19}")) {
				throw MoldApiException.validation("lastEventId", "format", Map.of("format", "^\\d{1,19}$"),
						"lastEventId 必须是十进制字符串");
			}
			try {
				cursor = Long.parseLong(rawCursor);
			}
			catch (NumberFormatException ex) {
				throw MoldApiException.validation("lastEventId", "format", Map.of("format", "int64"),
						"lastEventId 超出范围");
			}
		}

		EventStreamService.Window window = eventStreamService.window();
		long effective = cursor == null ? window.high() : cursor;
		if (effective < window.low() || effective > window.high()) {
			String reason = effective > window.high() ? "CURSOR_OUTSIDE_WINDOW" : "CURSOR_EXPIRED";
			String frame = "event: resync_required\ndata: {\"reason\":\"" + reason + "\",\"dataVersion\":\""
					+ window.high() + "\"}\n\n";
			return ResponseEntity.status(409)
				.contentType(MediaType.TEXT_EVENT_STREAM)
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.header("X-Accel-Buffering", "no")
				.header("X-Content-Type-Options", "nosniff")
				.body(frame);
		}

		SseEmitter emitter = eventStreamService.open(actor, resolvedScope, effective, window.high(), response);
		return ResponseEntity.ok()
			.contentType(MediaType.TEXT_EVENT_STREAM)
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.header("X-Accel-Buffering", "no")
			.header("X-Content-Type-Options", "nosniff")
			.body(emitter);
	}

}
