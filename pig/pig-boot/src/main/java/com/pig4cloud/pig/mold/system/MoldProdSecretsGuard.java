/*
 * 模具加工管理系统 生产加固
 * prod profile 启动自检：拒绝开发默认值、弱密钥与 root 数据库账号（fail-fast）。
 *
 * 占位符缺失时 Spring 已经会启动失败；本类再挡住「变量给了，但填的是开发值/示例值」这一类，
 * 例如把 MOLD_AUTH_CLIENT_SECRET 填成开发默认值 mold，或沿用仓库里的 encodeKey。
 */
package com.pig4cloud.pig.mold.system;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@Profile("prod")
public class MoldProdSecretsGuard implements InitializingBean {

	private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");

	private static final Set<String> KNOWN_WEAK = Set.of("mold", "pig", "root", "change-me", "changeme", "password",
			"123456", "admin", "secret", "thanks,pig4cloud");

	private final Environment env;

	public MoldProdSecretsGuard(Environment env) {
		this.env = env;
	}

	@Override
	public void afterPropertiesSet() {
		List<String> problems = check(env.getProperty("mold.auth.client-secret"), env.getProperty("security.encodeKey"),
				env.getProperty("spring.datasource.druid.username"), env.getProperty("jasypt.encryptor.password"));
		if (!problems.isEmpty()) {
			throw new IllegalStateException("生产配置自检失败（不回显密钥值）：" + String.join("；", problems));
		}
	}

	static List<String> check(String clientSecret, String encodeKey, String dbUser, String jasyptPassword) {
		List<String> problems = new ArrayList<>();
		if (clientSecret == null || clientSecret.length() < 32 || KNOWN_WEAK.contains(clientSecret)
				|| !BASE64URL.matcher(clientSecret).matches()) {
			problems.add("MOLD_AUTH_CLIENT_SECRET 必须是 >=32 位 base64url 随机串");
		}
		if (encodeKey == null || encodeKey.length() != 16 || KNOWN_WEAK.contains(encodeKey)) {
			problems.add("MOLD_ENCODE_KEY 必须是 16 位且不能沿用仓库默认值");
		}
		if (dbUser == null || "root".equals(dbUser.toLowerCase(Locale.ROOT))) {
			problems.add("MOLD_DB_USER 不能是 root，请使用最小权限账号");
		}
		if (jasyptPassword == null || jasyptPassword.length() < 16 || KNOWN_WEAK.contains(jasyptPassword)) {
			problems.add("MOLD_JASYPT_PASSWORD 必须是 >=16 位随机串");
		}
		return problems;
	}

}
