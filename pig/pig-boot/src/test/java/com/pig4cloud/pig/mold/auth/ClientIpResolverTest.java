/*
 * 模具加工管理系统 M7-A7
 * X-Forwarded-For 只在可信反代来源时采信；取最右侧第一个不可信地址（防伪造）。
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
		assertThat(resolver.resolve("127.0.0.1", "203.0.113.9")).isEqualTo("203.0.113.9");
	}

	@Test
	void takesRightmostUntrustedHopSoClientCannotSpoof() {
		// 客户端自带伪造头 "1.1.1.1"，nginx 在右侧追加真实对端 203.0.113.9
		assertThat(resolver.resolve("127.0.0.1", "1.1.1.1, 203.0.113.9")).isEqualTo("203.0.113.9");
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

	@Test
	void supportsCidrTrustedProxiesAndSkipsTrustedHops() {
		ClientIpResolver cidr = new ClientIpResolver("172.30.57.0/24,127.0.0.1");
		assertThat(cidr.resolve("172.30.57.9", "203.0.113.9")).isEqualTo("203.0.113.9");
		// 链上多级可信代理都跳过
		assertThat(cidr.resolve("172.30.57.9", "1.1.1.1, 203.0.113.9, 172.30.57.4")).isEqualTo("203.0.113.9");
		// 网段外来源不采信
		assertThat(cidr.resolve("172.30.58.9", "203.0.113.9")).isEqualTo("172.30.58.9");
	}

	@Test
	void nonIpHopsAreNeverTrustedAndNeverResolved() {
		ClientIpResolver cidr = new ClientIpResolver("10.0.0.0/8");
		assertThat(cidr.isTrusted("example.com")).isFalse();
		assertThat(cidr.isTrusted("10.1.2.3")).isTrue();
		assertThat(cidr.isTrusted("10.1.2")).isFalse();
	}

}
