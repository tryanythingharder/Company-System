/*
 * M2 收尾单测（G-01）：登录出参不含 csrfToken。
 * Bearer token 模式、无 cookie 会话，因此 /auth/login 不得回显 csrfToken；
 * token 作为 accessToken 的兼容别名保留。
 */
package com.pig4cloud.pig.mold.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pig4cloud.pig.mold.security.MoldActor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceLoginDataTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void loginDataDoesNotExposeCsrfToken() throws Exception {
		JsonNode token = objectMapper.readTree("""
				{"access_token":"at-1","refresh_token":"rt-1","token_type":"bearer",
				 "expires_in":43199,"scope":"server"}
				""");

		Map<String, Object> data = AuthService.loginData(token, actor(), capabilities());

		assertFalse(data.containsKey("csrfToken"), "登录出参不得包含 csrfToken");
		assertEquals(Set.of("token", "accessToken", "user", "roles", "permissions", "capabilities", "dataScopes",
				"mustChangePassword"), data.keySet());
	}

	@Test
	void keepsAccessTokenAliasAndMustChangeFlag() throws Exception {
		JsonNode token = objectMapper.readTree("{\"access_token\":\"at-2\",\"refresh_token\":\"rt-2\"}");

		Map<String, Object> data = AuthService.loginData(token, actor(), capabilities());

		assertEquals("at-2", data.get("accessToken"));
		assertTrue(data.containsKey("token"), "token 为 accessToken 的兼容别名，保留");
		assertEquals(Boolean.FALSE, data.get("mustChangePassword"));
		@SuppressWarnings("unchecked")
		Map<String, Object> tokenDto = (Map<String, Object>) data.get("token");
		assertEquals("at-2", tokenDto.get("accessToken"));
		assertEquals("Bearer", tokenDto.get("tokenType"));
	}

	@Test
	void loginDataKeysMatchDocumentedContract() throws Exception {
		JsonNode token = objectMapper.readTree("{\"access_token\":\"at-3\"}");

		Map<String, Object> data = AuthService.loginData(token, actor(), capabilities());

		assertTrue(data.keySet().stream().noneMatch(key -> key.toLowerCase().contains("csrf")),
				"任何 csrf 相关键都不应出现在登录出参中");
	}

	private MoldActor actor() {
		return new MoldActor("u-1", 7L, "m2fix_unit", "临时账号", "ACTIVE", 1, false, List.of("OWNER"));
	}

	private Map<String, Object> capabilities() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("roles", List.of("OWNER"));
		payload.put("permissions", List.of("mold:project:list"));
		payload.put("capabilities", List.of("mold:project:list"));
		payload.put("dataScopes", Map.of("projects", "ALL"));
		return payload;
	}

}
