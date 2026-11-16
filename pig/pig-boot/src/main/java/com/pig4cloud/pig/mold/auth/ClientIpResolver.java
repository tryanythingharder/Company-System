/*
 * 模具加工管理系统 M7-A7
 * 客户端 IP 解析：只有来自可信反代（默认本机回环）的请求才采信 X-Forwarded-For。
 */
package com.pig4cloud.pig.mold.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class ClientIpResolver {

	private static final String FORWARDED_FOR = "X-Forwarded-For";

	private final Set<String> trustedProxies;

	public ClientIpResolver(
			@Value("${mold.auth.trusted-proxy-ips:127.0.0.1,::1,0:0:0:0:0:0:0:1}") String trustedProxies) {
		this.trustedProxies = parse(trustedProxies);
	}

	public String resolve(HttpServletRequest request) {
		return resolve(request.getRemoteAddr(), request.getHeader(FORWARDED_FOR));
	}

	String resolve(String remoteAddr, String forwardedFor) {
		if (remoteAddr != null && trustedProxies.contains(remoteAddr.trim()) && StringUtils.hasText(forwardedFor)) {
			for (String part : forwardedFor.split(",")) {
				if (StringUtils.hasText(part)) {
					return part.trim();
				}
			}
		}
		return remoteAddr;
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

}
