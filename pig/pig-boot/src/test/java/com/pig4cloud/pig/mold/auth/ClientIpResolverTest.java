/*
 * 模具加工管理系统 M7-A7
 * X-Forwarded-For 只在可信反代来源时采信。
 */
package com.pig4cloud.pig.mold.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

	private final ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,::1");

	@Test
	void ignoresForwardedForFromUntrustedRemote() {
		assertThat(resolver.resolve("192.168.1.50", "203.0.113.9")).isEqualTo("192.168.1.50");
	}

	@Test
	void trustsForwardedForFromLoopbackProxy() {
		assertThat(resolver.resolve("127.0.0.1", "203.0.113.9, 10.0.0.1")).isEqualTo("203.0.113.9");
	}

	@Test
	void trustsForwardedForFromIpv6LoopbackProxy() {
		assertThat(resolver.resolve("::1", "198.51.100.7")).isEqualTo("198.51.100.7");
	}

	@Test
	void fallsBackToRemoteWhenHeaderMissing() {
		assertThat(resolver.resolve("127.0.0.1", null)).isEqualTo("127.0.0.1");
		assertThat(resolver.resolve("127.0.0.1", "  ")).isEqualTo("127.0.0.1");
	}

	@Test
	void skipsBlankForwardedEntries() {
		assertThat(resolver.resolve("127.0.0.1", " , 203.0.113.9")).isEqualTo("203.0.113.9");
	}

}
