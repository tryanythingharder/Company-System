/*
 * 模具加工管理系统 M7-A7
 * 生产 profile 不暴露 actuator / OpenAPI / Swagger UI。
 */
package com.pig4cloud.pig.mold.security;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProdSecurityConfigTest {

	@Test
	void prodExposesOnlyHealthEndpointAndDisablesSwagger() throws Exception {
		Map<String, Object> root;
		try (InputStream stream = getClass().getClassLoader().getResourceAsStream("application-prod.yml")) {
			assertThat(stream).as("application-prod.yml 应在 classpath").isNotNull();
			root = new Yaml().load(stream);
		}
		Map<String, Object> management = map(root, "management");
		Map<String, Object> endpoints = map(management, "endpoints");
		Map<String, Object> web = map(endpoints, "web");
		Map<String, Object> exposure = map(web, "exposure");
		assertThat(exposure.get("include")).isEqualTo("health");
		assertThat(map(web, "discovery").get("enabled")).isEqualTo(false);

		Map<String, Object> springdoc = map(root, "springdoc");
		assertThat(map(springdoc, "api-docs").get("enabled")).isEqualTo(false);
		assertThat(map(springdoc, "swagger-ui").get("enabled")).isEqualTo(false);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Map<String, Object> parent, String key) {
		Object value = parent == null ? null : parent.get(key);
		assertThat(value).as("配置项 %s", key).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}

}
