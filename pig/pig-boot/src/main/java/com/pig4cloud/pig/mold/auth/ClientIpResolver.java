/*
 * 模具加工管理系统 M7-A7
 * 客户端 IP 解析：只有来自可信反代的请求才采信 X-Forwarded-For。
 *
 * 取值规则（生产加固）：从 X-Forwarded-For 最右侧往左跳过可信代理，取第一个不可信地址。
 * 反代（nginx $proxy_add_x_forwarded_for）只会在右侧追加真实对端，最左侧由客户端任意伪造，
 * 取最左侧会让攻击者伪造 IP 绕过按 IP 限流、污染审计。
 *
 * 可信代理支持精确 IP 与 IPv4 CIDR（如 172.30.57.0/24，容器网段内的反代 IP 不固定）。
 * 只解析字面 IPv4，不做 DNS 解析。
 */
package com.pig4cloud.pig.mold.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class ClientIpResolver {

	private static final String FORWARDED_FOR = "X-Forwarded-For";

	private final Set<String> trustedExact;

	private final List<long[]> trustedCidrs;

	public ClientIpResolver(
			@Value("${mold.auth.trusted-proxy-ips:127.0.0.1,::1,0:0:0:0:0:0:0:1}") String trustedProxies) {
		Set<String> exact = new LinkedHashSet<>();
		List<long[]> cidrs = new ArrayList<>();
		for (String entry : parse(trustedProxies)) {
			long[] cidr = cidr(entry);
			if (cidr != null) {
				cidrs.add(cidr);
			}
			else {
				exact.add(entry);
			}
		}
		this.trustedExact = exact;
		this.trustedCidrs = cidrs;
	}

	public String resolve(HttpServletRequest request) {
		return resolve(request.getRemoteAddr(), request.getHeader(FORWARDED_FOR));
	}

	String resolve(String remoteAddr, String forwardedFor) {
		if (remoteAddr == null || !isTrusted(remoteAddr.trim()) || !StringUtils.hasText(forwardedFor)) {
			return remoteAddr;
		}
		String[] hops = forwardedFor.split(",");
		for (int i = hops.length - 1; i >= 0; i--) {
			String hop = hops[i].trim();
			if (StringUtils.hasText(hop) && !isTrusted(hop)) {
				return hop;
			}
		}
		// 整条链都是可信代理（如内网直连）：取最左侧非空值
		for (String hop : hops) {
			if (StringUtils.hasText(hop)) {
				return hop.trim();
			}
		}
		return remoteAddr;
	}

	boolean isTrusted(String address) {
		if (trustedExact.contains(address)) {
			return true;
		}
		long ip = ipv4(address);
		if (ip < 0) {
			return false;
		}
		for (long[] cidr : trustedCidrs) {
			if ((ip & cidr[1]) == cidr[0]) {
				return true;
			}
		}
		return false;
	}

	static Set<String> parse(String csv) {
		Set<String> result = new LinkedHashSet<>();
		if (csv != null) {
			for (String part : csv.split(",")) {
				if (StringUtils.hasText(part)) {
					result.add(part.trim());
				}
			}
		}
		return result;
	}

	/** "a.b.c.d/n" → {network, mask}；非 IPv4 CIDR 返回 null。 */
	private static long[] cidr(String entry) {
		int slash = entry.indexOf('/');
		if (slash < 0) {
			return null;
		}
		long base = ipv4(entry.substring(0, slash));
		int bits;
		try {
			bits = Integer.parseInt(entry.substring(slash + 1));
		}
		catch (NumberFormatException ex) {
			return null;
		}
		if (base < 0 || bits < 0 || bits > 32) {
			return null;
		}
		long mask = bits == 0 ? 0L : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
		return new long[] { base & mask, mask };
	}

	/** 字面 IPv4 → 无符号 32 位值；非法返回 -1（不做 DNS 解析）。 */
	private static long ipv4(String text) {
		String[] parts = text.split("\\.", -1);
		if (parts.length != 4) {
			return -1;
		}
		long value = 0;
		for (String part : parts) {
			if (part.isEmpty() || part.length() > 3) {
				return -1;
			}
			for (int i = 0; i < part.length(); i++) {
				if (!Character.isDigit(part.charAt(i))) {
					return -1;
				}
			}
			int octet = Integer.parseInt(part);
			if (octet > 255) {
				return -1;
			}
			value = (value << 8) | octet;
		}
		return value;
	}

}
