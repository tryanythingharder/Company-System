/*
 * M2 单测：offset 分页越界与排序白名单（契约 6.1）。
 */
package com.pig4cloud.pig.mold.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageQueryTest {

	private static final Set<String> SORTS = Set.of("code", "name", "updatedAt");

	@Test
	void defaultsToConfiguredSort() {
		PageQuery page = PageQuery.parse(null, null, null, null, SORTS, "code");
		assertEquals(1, page.pageNum());
		assertEquals(20, page.pageSize());
		assertEquals("code", page.sort());
		assertEquals("asc", page.order());
	}

	@Test
	void rejectsPageNumBelowOne() {
		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> PageQuery.parse("0", "20", null, null, SORTS, "code"));
		assertEquals(422, ex.getStatus());
		assertEquals("VALIDATION_FAILED", ex.getErrorCode());
		assertEquals("/query/pageNum", error(ex).get("instancePath"));
	}

	@Test
	void rejectsPageSizeAboveOneHundred() {
		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> PageQuery.parse("1", "101", null, null, SORTS, "code"));
		assertEquals(422, ex.getStatus());
		assertEquals("/query/pageSize", error(ex).get("instancePath"));
		assertEquals(100, params(ex).get("limit"));
	}

	@Test
	void rejectsNonIntegerPaging() {
		assertThrows(MoldApiException.class, () -> PageQuery.parse("abc", "20", null, null, SORTS, "code"));
		assertThrows(MoldApiException.class, () -> PageQuery.parse("1", "20.5", null, null, SORTS, "code"));
	}

	@Test
	void rejectsSortOutsideWhitelistWithAllowedValues() {
		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> PageQuery.parse("1", "20", "password", null, SORTS, "code"));
		assertEquals(422, ex.getStatus());
		assertEquals("/query/sort", error(ex).get("instancePath"));
		@SuppressWarnings("unchecked")
		List<String> allowed = (List<String>) params(ex).get("allowedValues");
		assertTrue(allowed.containsAll(List.of("code", "name", "updatedAt")));
	}

	@Test
	void rejectsOrderWithoutSort() {
		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> PageQuery.parse("1", "20", null, "desc", SORTS, "code"));
		assertEquals("/query/order", error(ex).get("instancePath"));
		assertEquals("required", error(ex).get("keyword"));
	}

	@Test
	void rejectsUnknownOrder() {
		MoldApiException ex = assertThrows(MoldApiException.class,
				() -> PageQuery.parse("1", "20", "name", "sideways", SORTS, "code"));
		assertEquals("/query/order", error(ex).get("instancePath"));
	}

	@Test
	void acceptsWhitelistedSortAndOrder() {
		PageQuery page = PageQuery.parse("3", "50", "name", "desc", SORTS, "code");
		assertEquals(3, page.pageNum());
		assertEquals(50, page.pageSize());
		assertEquals("name", page.sort());
		assertEquals("desc", page.order());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> error(MoldApiException ex) {
		Map<String, Object> details = (Map<String, Object>) ex.getDetails();
		return ((List<Map<String, Object>>) details.get("errors")).get(0);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> params(MoldApiException ex) {
		return (Map<String, Object>) error(ex).get("params");
	}

}
