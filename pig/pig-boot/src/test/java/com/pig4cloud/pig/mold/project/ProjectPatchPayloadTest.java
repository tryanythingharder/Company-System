/*
 * M6 前置单测：PATCH 项目空对象 {} 返回 422。
 * 空对象不构成有效修改，不能 version+1 并写一条无意义的 UPDATE 审计。
 */
package com.pig4cloud.pig.mold.project;

import com.pig4cloud.pig.mold.web.MoldApiException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectPatchPayloadTest {

	@Test
	void emptyObjectIsRejectedWith422() {
		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> ProjectService.requirePatchField(new LinkedHashMap<>()));

		assertEquals(422, ex.getStatus());
		assertEquals("VALIDATION_FAILED", ex.getErrorCode());
		@SuppressWarnings("unchecked")
		Map<String, Object> details = (Map<String, Object>) ex.getDetails();
		@SuppressWarnings("unchecked")
		java.util.List<Map<String, Object>> errors = (java.util.List<Map<String, Object>>) details.get("errors");
		assertEquals(1, errors.size());
		assertEquals("/body", errors.get(0).get("instancePath"));
		assertEquals("required", errors.get(0).get("keyword"));
	}

	@Test
	void nullBodyIsRejectedWith422() {
		MoldApiException ex = assertThrows(MoldApiException.class, () -> ProjectService.requirePatchField(null));
		assertEquals(422, ex.getStatus());
	}

	@Test
	void anyAllowedFieldPasses() {
		for (String field : ProjectService.PATCH_FIELDS) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put(field, "value");
			assertDoesNotThrow(() -> ProjectService.requirePatchField(body), field + " 应被接受");
		}
		assertTrue(ProjectService.PATCH_FIELDS.containsAll(
				java.util.Set.of("name", "dueDate", "customerId", "note")));
	}

}
