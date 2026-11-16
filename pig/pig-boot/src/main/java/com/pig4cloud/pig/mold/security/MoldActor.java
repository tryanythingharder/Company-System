/*
 * 模具加工管理系统 M2 后端切片
 * 业务身份视图对象。
 */
package com.pig4cloud.pig.mold.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MoldActor(String userId, Long sysUserId, String loginName, String displayName, String status,
		int version, boolean mustChangePassword, List<String> roles) {

	public MoldActor withRoles(List<String> nextRoles) {
		return new MoldActor(userId, sysUserId, loginName, displayName, status, version, mustChangePassword,
				List.copyOf(nextRoles));
	}

	/**
	 * 契约 userDto 形状：id / loginName / displayName / roles / status / version。
	 */
	public Map<String, Object> toUserDto() {
		Map<String, Object> user = new LinkedHashMap<>();
		user.put("id", userId);
		user.put("loginName", loginName);
		user.put("displayName", displayName);
		user.put("roles", roles);
		user.put("status", status);
		user.put("version", version);
		return user;
	}

}
