/*
 * 模具加工管理系统 M7-A / A4 SSE 事件流（契约 6.10.4）
 *
 *  - 游标 = event_outbox.id；Last-Event-ID 头优先，其次 ?lastEventId=；
 *  - 游标格式非法 → 422 VALIDATION_FAILED，details 指向 lastEventId（G-8）；
 *  - 越界/过期 → HTTP 409，先发 resync_required 再关闭；
 *  - 信封不带 name/code，payload 只 3 键（G-10）；
 *  - 每用户最多 3 条并发流，超出 429 TOO_MANY_STREAMS；
 *  - 心跳 20s 一行 `: heartbeat`，轮询 250ms。
 *
 * 连接回收（2026-11-15 修复「3 次之后永久 429」）：
 *  - 全局定时清理：每 5s 扫全部 userId，不依赖是否有人重新建连；
 *  - 真实存活判据：名额满时对现有连接「写一行 SSE 注释 + flushBuffer」，flush 是阻塞写，
 *    客户端已断开时当场抛 IOException / AsyncRequestNotUsableException，立即回收；
 *    只靠 lastWriteAt 不行——几字节注释会先留在 Tomcat 输出缓冲里，写成功但没上 socket；
 *  - emitter 的 completion/timeout/error 回调保留为第一判据，定时清理兜底；
 *  - close 幂等：重复调用不抛，任务只取消一次，连接只移除一次。
 */
package com.pig4cloud.pig.mold.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pig4cloud.pig.mold.security.MoldActor;
import com.pig4cloud.pig.mold.web.MoldApiException;
import com.pig4cloud.pig.mold.web.MoldTime;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventStreamService {

	public static final int MAX_CONNECTIONS_PER_USER = 3;

	public static final long POLL_MS = 250L;

	public static final long HEARTBEAT_MS = 20_000L;

	/** 全局清理周期。 */
	public static final long SWEEP_MS = 5_000L;

	/** 兜底判据：连续 3 个心跳周期没有一次成功写入才判定为僵尸。 */
	public static final long STALE_MS = HEARTBEAT_MS * 3;

	/** 探测两阶段之间的间隔：第一次写入触发对端 RST，留出回程时间再写第二次。 */
	public static final long PROBE_SETTLE_MS = 150L;

	/**
	 * 名额满时的准入等待上限。客户端刚断开时，容器要 ~0.3–0.5s 才把这条连接标成不可用，
	 * 所以第 4 条请求先探测/等待一小段时间再判 429，避免「刚关掉立刻重连」被误拒。
	 */
	public static final long ADMIT_WAIT_MS = 2_000L;

	/** 探测注释行文本：以 `:` 开头的 SSE 注释，客户端解析时直接忽略。 */
	public static final String PROBE_COMMENT = "probe";

	public static final int EVENT_RETENTION_DAYS = 7;

	public static final int EVENT_MAX_COUNT = 10_000;

	public static final int BATCH = 200;

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8, runnable -> {
		Thread thread = new Thread(runnable, "mold-sse");
		thread.setDaemon(true);
		return thread;
	});

	private final Map<String, Set<Connection>> connections = new ConcurrentHashMap<>();

	private volatile ScheduledFuture<?> sweeper;

	@PostConstruct
	void startSweeper() {
		sweeper = scheduler.scheduleWithFixedDelay(this::sweepAll, SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS);
		log.info("SSE 连接清理已启动 sweep={}ms stale={}ms maxPerUser={}", SWEEP_MS, STALE_MS, MAX_CONNECTIONS_PER_USER);
	}

	@PreDestroy
	void shutdown() {
		ScheduledFuture<?> current = sweeper;
		if (current != null) {
			current.cancel(false);
		}
		for (String userId : new ArrayList<>(connections.keySet())) {
			Set<Connection> set = connections.get(userId);
			if (set != null) {
				for (Connection connection : new ArrayList<>(set)) {
					close(connection, "shutdown");
				}
			}
		}
		scheduler.shutdownNow();
	}

	public Window window() {
		Map<String, Object> clock = jdbcTemplate.queryForMap(
				"SELECT version, retained_from FROM event_clock WHERE singleton = 1");
		long version = ((Number) clock.get("version")).longValue();
		long retainedFrom = ((Number) clock.get("retained_from")).longValue();
		Long aged = jdbcTemplate.queryForObject(
				"SELECT COALESCE(MAX(id), 0) FROM event_outbox WHERE created_at < DATE_SUB(UTC_TIMESTAMP(3), INTERVAL ? DAY)",
				Long.class, EVENT_RETENTION_DAYS);
		long low = Math.max(Math.max(retainedFrom, aged == null ? 0L : aged), Math.max(version - EVENT_MAX_COUNT, 0L));
		return new Window(low, version);
	}

	public SseEmitter open(MoldActor actor, String scope, long cursor, long high) {
		return open(actor, scope, cursor, high, null);
	}

	public SseEmitter open(MoldActor actor, String scope, long cursor, long high, HttpServletResponse response) {
		String userId = actor.userId();
		// 1) 名额满时反复「清理 + 探测」，最多等 ADMIT_WAIT_MS，把刚断开的连接回收掉再判。
		long deadline = System.currentTimeMillis() + ADMIT_WAIT_MS;
		while (true) {
			sweep(userId, System.currentTimeMillis());
			Set<Connection> set = connections.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet());
			if (set.size() < MAX_CONNECTIONS_PER_USER) {
				break;
			}
			if (System.currentTimeMillis() >= deadline) {
				break;
			}
			probeAll(set);
		}
		SseEmitter emitter = createEmitter();
		Connection connection = new Connection(userId, scope, cursor, emitter, response);
		boolean accepted = false;
		synchronized (connections) {
			Set<Connection> current = connections.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet());
			if (current.size() < MAX_CONNECTIONS_PER_USER) {
				current.add(connection);
				accepted = true;
			}
		}
		if (!accepted) {
			Set<Connection> current = connections.get(userId);
			log.info("SSE 拒绝 user={} active={} ids={}", userId, activeCount(userId), describe(current));
			throw new MoldApiException(429, "TOO_MANY_STREAMS", "同一账号最多 3 条并发事件流");
		}
		emitter.onCompletion(() -> close(connection, "completion"));
		emitter.onTimeout(() -> close(connection, "timeout"));
		emitter.onError(error -> close(connection, "error"));
		if (!send(connection, "ready", Map.of("dataVersion", String.valueOf(high), "cursor", String.valueOf(cursor),
				"scope", scope))) {
			close(connection, "ready_failed");
			return emitter;
		}
		connection.poll = scheduler.scheduleWithFixedDelay(() -> tick(connection), 0, POLL_MS, TimeUnit.MILLISECONDS);
		connection.heartbeat = scheduler.scheduleWithFixedDelay(() -> heartbeat(connection), HEARTBEAT_MS,
				HEARTBEAT_MS, TimeUnit.MILLISECONDS);
		log.info("SSE 建连 user={} id={} scope={} cursor={} active={}", userId, connection.id, scope, cursor, activeCount(userId));
		return emitter;
	}

	public record Window(long low, long high) {
	}

	/** 测试与诊断用：当前登记在册的连接数。 */
	int activeCount(String userId) {
		Set<Connection> set = connections.get(userId);
		return set == null ? 0 : set.size();
	}

	/** 可被测试替换的 emitter 工厂。 */
	SseEmitter createEmitter() {
		return new SseEmitter(0L);
	}

	/** 全局清理入口：定时任务与测试共用，now 便于单测把时钟推到未来。 */
	void sweepAll() {
		sweepAll(System.currentTimeMillis());
	}

	void sweepAll(long now) {
		for (String userId : new ArrayList<>(connections.keySet())) {
			try {
				sweep(userId, now);
				// 名额已满：主动探测，把客户端已断开的连接回收掉（不依赖是否有人重新建连）。
				// 探测写的是 SSE 注释行 `: probe`，前端解析时忽略。
				Set<Connection> set = connections.get(userId);
				if (set != null && set.size() >= MAX_CONNECTIONS_PER_USER) {
					probeAll(set);
				}
			}
			catch (RuntimeException ex) {
				log.warn("SSE 全局清理失败 user={}", userId, ex);
			}
		}
	}

	// ------------------------------------------------------------------ 轮询

	private void tick(Connection connection) {
		if (connection.closed) {
			return;
		}
		try {
			Window window = window();
			if (connection.cursor < window.low()) {
				send(connection, "resync_required", Map.of("reason", "CURSOR_EXPIRED",
						"dataVersion", String.valueOf(window.high())));
				close(connection, "cursor_expired");
				return;
			}
			List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
					SELECT * FROM event_outbox WHERE id > ? AND id <= ? ORDER BY id LIMIT ?
					""", connection.cursor, window.high(), BATCH);
			for (Map<String, Object> row : rows) {
				long id = ((Number) row.get("id")).longValue();
				connection.cursor = id;
				if ("FINANCE".equals(row.get("visibility")) && !"FINANCE".equals(connection.scope)) {
					continue;
				}
				if (!send(connection, (String) row.get("event_type"), envelope(row))) {
					close(connection, "event_write_failed");
					return;
				}
			}
			if (rows.size() == BATCH) {
				connection.morePending = true;
			}
			else if (connection.firstTick || !rows.isEmpty() || connection.morePending) {
				send(connection, "caught_up", Map.of("dataVersion", String.valueOf(window.high()),
						"cursor", String.valueOf(connection.cursor)));
				connection.morePending = false;
			}
			connection.firstTick = false;
		}
		catch (RuntimeException ex) {
			log.warn("SSE 轮询失败 user={}", connection.userId, ex);
			send(connection, "stream_error", Map.of("code", "STREAM_ERROR"));
			close(connection, "poll_failed");
		}
	}

	private void heartbeat(Connection connection) {
		if (connection.closed) {
			return;
		}
		try {
			List<Map<String, Object>> users = jdbcTemplate.queryForList(
					"SELECT status, must_change_password FROM users WHERE id = ?", connection.userId);
			if (users.isEmpty() || !"ACTIVE".equals(users.get(0).get("status"))
					|| Boolean.TRUE.equals(users.get(0).get("must_change_password"))) {
				send(connection, "session_expired", Map.of("code", "SESSION_EXPIRED"));
				close(connection, "session_expired");
				return;
			}
			if (!sendComment(connection, "heartbeat", true)) {
				close(connection, "heartbeat_failed");
			}
		}
		catch (RuntimeException ex) {
			log.info("SSE 心跳失败 user={} err={}", connection.userId, ex.toString());
			close(connection, "heartbeat_error");
		}
	}

	private Map<String, Object> envelope(Map<String, Object> row) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("eventId", String.valueOf(row.get("id")));
		data.put("eventType", row.get("event_type"));
		data.put("resourceType", row.get("resource_type"));
		data.put("resourceId", row.get("resource_id"));
		data.put("projectId", row.get("project_id"));
		data.put("stageId", row.get("stage_id"));
		data.put("resourceVersion", row.get("resource_version"));
		data.put("visibility", row.get("visibility"));
		data.put("actorId", row.get("actor_id"));
		data.put("occurredAt", MoldTime.iso(row.get("created_at")));
		Object payload = row.get("payload");
		if (payload != null) {
			try {
				Map<String, Object> parsed = objectMapper.readValue(payload.toString(), Map.class);
				// payload 只允许 status / previousStatus / changedFields（DB CHECK 已保证）。
				data.putAll(parsed);
			}
			catch (Exception ignored) {
				// 历史行异常时只发信封，不阻断流。
			}
		}
		return data;
	}

	private boolean send(Connection connection, String name, Map<String, Object> data) {
		synchronized (connection) {
			if (connection.closed) {
				return false;
			}
			try {
				connection.emitter.send(SseEmitter.event().name(name).data(data));
				connection.lastWriteAt = System.currentTimeMillis();
				return true;
			}
			catch (IOException | IllegalStateException ex) {
				return false;
			}
		}
	}

	private boolean sendComment(Connection connection, String comment) {
		return sendComment(connection, comment, false);
	}

	/**
	 * 写一行 SSE 注释；flush=true 时再调 {@code flushBuffer()}，把 NIO 缓冲真正推上 socket。
	 *
	 * 只写不 flush 时，Tomcat 会先把几字节注释留在输出缓冲里，客户端已 RST 也看不出来；
	 * flushBuffer 是阻塞写，死连接会直接抛 IOException / AsyncRequestNotUsableException。
	 */
	private boolean sendComment(Connection connection, String comment, boolean flush) {
		synchronized (connection) {
			if (connection.closed) {
				return false;
			}
			try {
				connection.emitter.send(SseEmitter.event().comment(comment));
				connection.lastWriteAt = System.currentTimeMillis();
				if (flush && connection.response != null) {
					connection.response.flushBuffer();
				}
				return true;
			}
			catch (IOException | IllegalStateException ex) {
				log.info("SSE 注释写入失败 user={} id={} comment={} kind={} err={}", connection.userId, connection.id,
						comment, ex.getClass().getSimpleName(), ex.getMessage());
				return false;
			}
		}
	}

	// ------------------------------------------------------------------ 回收

	private void sweep(String userId, long now) {
		Set<Connection> set = connections.get(userId);
		if (set == null) {
			return;
		}
		if (set.isEmpty()) {
			connections.remove(userId, set);
			return;
		}
		List<Connection> stale = new ArrayList<>();
		for (Connection connection : set) {
			if (connection.closed || now - connection.lastWriteAt > STALE_MS) {
				stale.add(connection);
			}
		}
		stale.forEach(connection -> close(connection, "stale"));
	}

	/**
	 * 主动探测，分两阶段：
	 *  1) 每个连接写一行注释并 flush（真的上 socket）；客户端若已半关闭，这会把对端推到 RST；
	 *  2) 等 {@link #PROBE_SETTLE_MS} 后再写一次并 flush，此时死连接会抛 IOException /
	 *     AsyncRequestNotUsableException，当场回收。
	 *
	 * 为什么必须两次：客户端 close() 只发 FIN 时，本端 socket 仍可写，第一次写入是成功的；
	 * 只有对端回 RST 之后的第二次写入才会失败。只靠 lastWriteAt 或单次写入都判不出来。
	 */
	private void probeAll(Set<Connection> set) {
		List<Connection> targets = new ArrayList<>();
		for (Connection connection : new ArrayList<>(set)) {
			if (connection.closed) {
				continue;
			}
			if (sendComment(connection, PROBE_COMMENT, true)) {
				targets.add(connection);
			}
			else {
				close(connection, "probe_failed");
			}
		}
		if (targets.isEmpty()) {
			return;
		}
		try {
			Thread.sleep(PROBE_SETTLE_MS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return;
		}
		int closed = 0;
		for (Connection connection : targets) {
			if (connection.closed) {
				continue;
			}
			if (!sendComment(connection, PROBE_COMMENT, true)) {
				close(connection, "probe_failed");
				closed++;
			}
		}
		log.info("SSE 探测 user={} 存活={} 回收={}", targets.get(0).userId, targets.size() - closed, closed);
	}

	private static String describe(Set<Connection> set) {
		if (set == null || set.isEmpty()) {
			return "[]";
		}
		List<String> ids = new ArrayList<>();
		for (Connection connection : set) {
			ids.add(connection.id + ":" + (System.currentTimeMillis() - connection.lastWriteAt) + "ms");
		}
		return ids.toString();
	}

	/** 幂等关闭：重复调用不抛，任务只取消一次，连接只移除一次。 */
	private void close(Connection connection, String reason) {
		synchronized (connection) {
			if (connection.closed) {
				return;
			}
			connection.closed = true;
		}
		if (connection.poll != null) {
			connection.poll.cancel(false);
		}
		if (connection.heartbeat != null) {
			connection.heartbeat.cancel(false);
		}
		Set<Connection> set = connections.get(connection.userId);
		int remaining = 0;
		if (set != null) {
			set.remove(connection);
			remaining = set.size();
			if (set.isEmpty()) {
				connections.remove(connection.userId, set);
			}
		}
		try {
			connection.emitter.complete();
		}
		catch (RuntimeException ignored) {
			// 客户端已断开。
		}
		log.info("SSE 断开 user={} id={} scope={} reason={} remaining={}", connection.userId, connection.id, connection.scope,
				reason, remaining);
	}

	private static final class Connection {

		private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();

		private final long id = SEQ.incrementAndGet();

		private final String userId;

		private final String scope;

		private long cursor;

		private final SseEmitter emitter;

		/** 用于 flush 探测；单测直接构造 Connection 时为 null。 */
		private final HttpServletResponse response;

		private volatile long lastWriteAt = System.currentTimeMillis();

		private volatile boolean closed;

		private ScheduledFuture<?> poll;

		private ScheduledFuture<?> heartbeat;

		private boolean firstTick = true;

		private boolean morePending;

		private Connection(String userId, String scope, long cursor, SseEmitter emitter,
				HttpServletResponse response) {
			this.userId = userId;
			this.scope = scope;
			this.cursor = cursor;
			this.emitter = emitter;
			this.response = response;
		}

	}

}
