/*
 * 生产加固单测：prod 启动自检拒绝开发默认值与弱配置。
 */
package com.pig4cloud.pig.mold.system;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MoldProdSecretsGuardTest {

	private static final String GOOD_SECRET = "Q2xpZW50U2VjcmV0X2Zvcl90ZXN0c19vbmx5XzEyMzQ1Njc4OTA";

	@Test
	void acceptsStrongConfig() {
		assertThat(MoldProdSecretsGuard.check(GOOD_SECRET, "AbCdEfGh12345678", "mold_app", "jasypt-strong-value-1234"))
			.isEmpty();
	}

	@Test
	void rejectsDevDefaultsAndRoot() {
		assertThat(MoldProdSecretsGuard.check("mold", "thanks,pig4cloud", "root", "pig")).hasSize(4);
	}

	@Test
	void rejectsNonBase64UrlSecret() {
		// '+' 在客户端认证链路里会被当作空格解码，实测恒 invalid_client
		String withPlus = GOOD_SECRET.substring(0, 40) + "+abc";
		assertThat(MoldProdSecretsGuard.check(withPlus, "AbCdEfGh12345678", "mold_app", "jasypt-strong-value-1234"))
			.hasSize(1);
	}

}
