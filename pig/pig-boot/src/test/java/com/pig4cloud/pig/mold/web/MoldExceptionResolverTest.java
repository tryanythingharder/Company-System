/*
 * M2 修复轮单测：统一错误信封。
 *  - 空体 / 非法 JSON → 422 VALIDATION_FAILED（不再落到 Pig 默认 400/500）；
 *  - 普通 403 仍是 FORBIDDEN，与 PASSWORD_CHANGE_REQUIRED 区分；
 *  - 业务 409 的 details 原样保留（field/value、allowedActions）。
 */
package com.pig4cloud.pig.mold.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoldExceptionResolverTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final MoldExceptionResolver resolver = new MoldExceptionResolver(objectMapper);

	@Test
	void emptyOrMalformedBodyBecomes422() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		resolver.resolveException(new MockHttpServletRequest(), response, new Object(),
				new HttpMessageNotReadableException("Required request body is missing",
						(org.springframework.http.HttpInputMessage) null));

		assertEquals(422, response.getStatus());
		JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
		assertEquals(422, body.path("code").asInt());
		assertEquals("VALIDATION_FAILED", body.path("data").path("errorCode").asText());
		assertEquals("/body", body.path("data").path("details").path("errors").path(0).path("instancePath").asText());
	}

	@Test
	void accessDeniedStaysGenericForbidden() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		resolver.resolveException(new MockHttpServletRequest(), response, new Object(),
				new AccessDeniedException("denied"));

		assertEquals(403, response.getStatus());
		JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
		assertEquals("FORBIDDEN", body.path("data").path("errorCode").asText());
	}

	@Test
	void conflictDetailsArePreserved() throws Exception {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("field", "code");
		details.put("value", "M2-S4-DUP-001");
		details.put("allowedActions", List.of());

		MockHttpServletResponse response = new MockHttpServletResponse();
		resolver.resolveException(new MockHttpServletRequest(), response, new Object(),
				new MoldApiException(409, "PROJECT_CODE_DUPLICATE", "项目编号已存在", details));

		assertEquals(409, response.getStatus());
		JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
		assertEquals("code", body.path("data").path("details").path("field").asText());
		assertEquals("M2-S4-DUP-001", body.path("data").path("details").path("value").asText());
		assertEquals(0, body.path("data").path("details").path("allowedActions").size());
	}

}
