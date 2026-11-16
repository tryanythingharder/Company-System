/*
 * SSE 连接回收单测（2026-11-15 修「3 次之后永久 429」）。
 *
 * 覆盖三条路径：
 *   1) 关闭后名额释放：客户端断开（completion 回调）后，同账号可以重新建连；
 *   2) 全局清理：定时任务按 userId 全量扫，不只清当前建连的那个账号；
 *   3) 真实存活判据：客户端已断开但 lastWriteAt 刚被心跳刷新时，
 *      名额满的主动探测能靠写入失败识别并回收（不依赖 lastWriteAt 超时）。
 *
 * 说明：用真实的 EventStreamService，只替换 JdbcTemplate 与 SseEmitter 两个协作者，
 * 不引入 mock 框架；真实 HTTP 证据另见 docs/verify/sse-reclaim-verify.py。
 */
package com.pig4cloud.pig.mold.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pig4cloud.pig.mold.security.MoldActor;
import com.pig4cloud.pig.mold.web.MoldApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventStreamLivenessTest {

	private static final String USER_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

	private static final String USER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

	/** 只回固定窗口，不连数据库。 */
	static class FakeJdbc extends JdbcTemplate {

		@Override
		public Map<String, Object> queryForMap(String sql) {
			return Map.of("version", 100L, "retained_from", 0L);
		}

		@Override
		public Map<String, Object> queryForMap(String sql, Object... args) {
			return Map.of("version", 100L, "retained_from", 0L);
		}

		@Override
		public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
			@SuppressWarnings("unchecked")
			T zero = (T) Long.valueOf(0L);
			return zero;
		}

		@Override
		public List<Map<String, Object>> queryForList(String sql, Object... args) {
			return List.of();
		}

	}

	/** 可被单测控制成败的 emitter：模拟客户端断开后写入抛 IOException。 */
	static class TestEmitter extends SseEmitter {

		volatile boolean fail;

		private Runnable completion;

		TestEmitter() {
			super(0L);
		}

		@Override
		public void send(SseEventBuilder builder) throws IOException {
			if (fail) {
				throw new IOException("client gone");
			}
			super.send(builder);
		}

		@Override
		public void onCompletion(Runnable callback) {
			this.completion = callback;
			super.onCompletion(callback);
		}

		/** 模拟容器/浏览器关闭连接时触发的 completion 回调。 */
		void clientClosed() {
			if (completion != null) {
				completion.run();
			}
		}

	}

	static class TestService extends EventStreamService {

		final List<TestEmitter> emitters = new ArrayList<>();

		TestService() {
			super(new FakeJdbc(), new ObjectMapper());
		}

		@Override
		SseEmitter createEmitter() {
			TestEmitter emitter = new TestEmitter();
			emitters.add(emitter);
			return emitter;
		}

	}

	private static MoldActor actor(String userId) {
		return new MoldActor(userId, 1L, "demo", "demo", "ACTIVE", 1, false, List.of("PROGRAMMER"));
	}

	private static SseEmitter open(TestService service, String userId) {
		return service.open(actor(userId), "BASIC", 100L, 100L);
	}

	@Test
	void closingAConnectionFreesItsSlot() {
		TestService service = new TestService();
		for (int i = 0; i < EventStreamService.MAX_CONNECTIONS_PER_USER; i++) {
			open(service, USER_A);
		}
		assertEquals(3, service.activeCount(USER_A));

		// 三条都活着 -> 第 4 条仍是 429（设计保留）
		MoldApiException tooMany = assertThrows(MoldApiException.class, () -> open(service, USER_A));
		assertEquals(429, tooMany.getStatus());

		// 客户端关闭一条 -> 名额立即释放
		service.emitters.get(0).clientClosed();
		assertEquals(2, service.activeCount(USER_A));

		open(service, USER_A);
		assertEquals(3, service.activeCount(USER_A));

		// 幂等：同一个连接重复关闭不抛，也不影响计数
		service.emitters.get(0).clientClosed();
		service.emitters.get(0).clientClosed();
		assertEquals(3, service.activeCount(USER_A));
	}

	@Test
	void globalSweepReclaimsEveryUser() {
		TestService service = new TestService();
		open(service, USER_A);
		open(service, USER_A);
		open(service, USER_B);
		assertEquals(2, service.activeCount(USER_A));
		assertEquals(1, service.activeCount(USER_B));

		// 定时任务用未来时刻扫一遍：两个账号都被清掉，而不是只清建连的那个
		service.sweepAll(System.currentTimeMillis() + EventStreamService.STALE_MS + 5_000L);
		assertEquals(0, service.activeCount(USER_A));
		assertEquals(0, service.activeCount(USER_B));

		// 回收后名额恢复：同一账号可重新建满 3 条
		for (int i = 0; i < EventStreamService.MAX_CONNECTIONS_PER_USER; i++) {
			open(service, USER_A);
		}
		assertEquals(3, service.activeCount(USER_A));
	}

	@Test
	void deadConnectionIsDetectedByProbeEvenWithFreshLastWrite() throws InterruptedException {
		TestService service = new TestService();
		for (int i = 0; i < EventStreamService.MAX_CONNECTIONS_PER_USER; i++) {
			open(service, USER_A);
		}
		// 让首轮 caught_up 写完，避免轮询线程与探测相互干扰
		Thread.sleep(400L);
		assertEquals(3, service.activeCount(USER_A));

		// 客户端已断开，但 lastWriteAt 刚被心跳刷新过 —— 只靠超时判据永远清不掉
		service.emitters.get(0).fail = true;

		// 名额满时的主动探测写入失败 -> 回收 -> 新连接 200
		SseEmitter accepted = open(service, USER_A);
		assertTrue(accepted != null);
		assertEquals(3, service.activeCount(USER_A));
	}

}
