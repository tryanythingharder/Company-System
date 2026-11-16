/*
 * 模具加工管理系统 M2 第三切片
 * Idempotency-Key 长度校验（契约 6.1：8–128 字符）。
 */
package com.pig4cloud.pig.mold.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTest {

	@Test
	void acceptsEightTo128Characters() {
		IdempotencyService.validateKey("12345678");
		IdempotencyService.validateKey("x".repeat(128));
	}

	@Test
	void rejectsTooShort() {
		assertThatThrownBy(() -> IdempotencyService.validateKey("1234567"))
			.isInstanceOf(MoldApiException.class)
			.satisfies(ex -> {
				MoldApiException error = (MoldApiException) ex;
				assertThat(error.getStatus()).isEqualTo(422);
				assertThat(error.getErrorCode()).isEqualTo("VALIDATION_FAILED");
			});
	}

	@Test
	void rejectsTooLong() {
		assertThatThrownBy(() -> IdempotencyService.validateKey("x".repeat(129)))
			.isInstanceOf(MoldApiException.class)
			.satisfies(ex -> assertThat(((MoldApiException) ex).getStatus()).isEqualTo(422));
	}

}
