/*
 * 模具加工管理系统 M2 后端切片
 * 能力与数据范围标识（G1）。
 *
 * `permissions` 以 sys_menu.permission 为唯一事实来源；
 * `dataScopes` 说明该角色在各类资源上的对象级范围，服务端鉴权仍以逐接口检查为准，
 * 该字段只用于前端隐藏入口与提示，不能替代服务端校验。
 *
 * 03 正在把字段结构写入契约，本类字段名如与最终契约不同，由 03 统一后调整。
 */
package com.pig4cloud.pig.mold.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MoldCapabilities {

	public static final String ALL = "ALL";

	public static final String OWNED = "OWNED";

	public static final String OWNED_OR_ASSIGNED = "OWNED_OR_ASSIGNED";

	public static final String ASSIGNED = "ASSIGNED";

	public static final String NONE = "NONE";

	private MoldCapabilities() {
	}

	public static Map<String, Object> dataScopes(List<String> roles) {
		boolean owner = roles.contains("OWNER");
		Map<String, Object> scopes = new LinkedHashMap<>();
		// 公司进度与项目详情：02 矩阵为全角色可看（旧系统实测 6 角色均 total=8）。
		scopes.put("projectRead", ALL);
		// 写项目仅限该项目 owner_user_id 本人（02 脚注 ✓*2）。
		scopes.put("projectWrite", writeProject(roles));
		scopes.put("stageRead", ALL);
		scopes.put("stageWrite", writeStage(roles));
		scopes.put("accountManage", owner ? ALL : NONE);
		scopes.put("finance", roles.contains("FINANCE") || owner ? ALL : NONE);
		scopes.put("audit", owner ? ALL : NONE);
		return scopes;
	}

	private static String writeProject(List<String> roles) {
		if (roles.contains("OWNER") || roles.contains("PM")) {
			return OWNED;
		}
		return NONE;
	}

	private static String writeStage(List<String> roles) {
		if (roles.contains("OWNER") || roles.contains("PM")) {
			return OWNED_OR_ASSIGNED;
		}
		if (roles.contains("PROGRAMMER") || roles.contains("MACHINIST")) {
			return ASSIGNED;
		}
		return NONE;
	}

}
