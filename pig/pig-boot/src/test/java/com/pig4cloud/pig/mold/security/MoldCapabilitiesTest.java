/*
 * M2 单测：六角色数据范围标识（G1 capabilities / 02 角色矩阵）。
 *
 * 这里只验证纯函数；真实 403/200 矩阵由实测 curl 证据覆盖。
 */
package com.pig4cloud.pig.mold.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoldCapabilitiesTest {

	@Test
	void accountManagementIsOwnerOnly() {
		for (String role : List.of("OWNER", "PM", "PROGRAMMER", "MACHINIST", "FINANCE", "VIEWER")) {
			Map<String, Object> scopes = MoldCapabilities.dataScopes(List.of(role));
			assertEquals("OWNER".equals(role) ? MoldCapabilities.ALL : MoldCapabilities.NONE,
					scopes.get("accountManage"), role);
		}
	}

	@Test
	void projectReadIsOpenToAllRoles() {
		for (String role : List.of("OWNER", "PM", "PROGRAMMER", "MACHINIST", "FINANCE", "VIEWER")) {
			assertEquals(MoldCapabilities.ALL, MoldCapabilities.dataScopes(List.of(role)).get("projectRead"), role);
		}
	}

	@Test
	void projectWriteIsOwnedForOwnerAndPmOnly() {
		assertEquals(MoldCapabilities.OWNED, MoldCapabilities.dataScopes(List.of("OWNER")).get("projectWrite"));
		assertEquals(MoldCapabilities.OWNED, MoldCapabilities.dataScopes(List.of("PM")).get("projectWrite"));
		assertEquals(MoldCapabilities.NONE, MoldCapabilities.dataScopes(List.of("PROGRAMMER")).get("projectWrite"));
		assertEquals(MoldCapabilities.NONE, MoldCapabilities.dataScopes(List.of("MACHINIST")).get("projectWrite"));
		assertEquals(MoldCapabilities.NONE, MoldCapabilities.dataScopes(List.of("FINANCE")).get("projectWrite"));
		assertEquals(MoldCapabilities.NONE, MoldCapabilities.dataScopes(List.of("VIEWER")).get("projectWrite"));
	}

	@Test
	void stageWriteFollowsAssignedScope() {
		assertEquals(MoldCapabilities.OWNED_OR_ASSIGNED, MoldCapabilities.dataScopes(List.of("PM")).get("stageWrite"));
		assertEquals(MoldCapabilities.ASSIGNED, MoldCapabilities.dataScopes(List.of("PROGRAMMER")).get("stageWrite"));
		assertEquals(MoldCapabilities.ASSIGNED, MoldCapabilities.dataScopes(List.of("MACHINIST")).get("stageWrite"));
		assertEquals(MoldCapabilities.NONE, MoldCapabilities.dataScopes(List.of("FINANCE")).get("stageWrite"));
		assertEquals(MoldCapabilities.NONE, MoldCapabilities.dataScopes(List.of("VIEWER")).get("stageWrite"));
	}

	@Test
	void financeAndAuditScopes() {
		assertEquals(MoldCapabilities.ALL, MoldCapabilities.dataScopes(List.of("FINANCE")).get("finance"));
		assertEquals(MoldCapabilities.ALL, MoldCapabilities.dataScopes(List.of("OWNER")).get("finance"));
		assertEquals(MoldCapabilities.NONE, MoldCapabilities.dataScopes(List.of("PM")).get("finance"));
		assertEquals(MoldCapabilities.ALL, MoldCapabilities.dataScopes(List.of("OWNER")).get("audit"));
		assertEquals(MoldCapabilities.NONE, MoldCapabilities.dataScopes(List.of("FINANCE")).get("audit"));
	}

	@Test
	void multiRoleUnionKeepsStrongestScope() {
		Map<String, Object> scopes = MoldCapabilities.dataScopes(List.of("VIEWER", "OWNER"));
		assertEquals(MoldCapabilities.ALL, scopes.get("accountManage"));
		assertEquals(MoldCapabilities.OWNED_OR_ASSIGNED, scopes.get("stageWrite"));
	}

}
