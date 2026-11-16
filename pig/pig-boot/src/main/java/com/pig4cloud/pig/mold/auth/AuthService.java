/*
 * 模具加工管理系统 M2 后端切片
 * /auth/* 薄适配层（契约 6.11 方案 A）：
 *  - 登录：内部调用本机 /admin/oauth2/token（password grant，client mold:mold）；
 *  - me：当前 Bearer token 的用户 + 能力串；
 *  - logout：调用 Pig 的 /admin/token/logout 吊销 Redis 中的 token；
 *  - refresh：refresh_token grant。
 * 登录出参不含 csrfToken（G-01：Bearer token，无 cookie 会话）。
 * Pig 原生 /auth/oauth2/token 是内部路径（本服务通过 /oauth2/token 调用），UI 不再使用。
 * sys_user 为登录主表，users 为业务身份表（users.sys_user_id 关联）。
 */
package com.pig4cloud.pig.mold.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pig4cloud.pig.mold.security.MoldActor;
import com.pig4cloud.pig.mold.security.MoldActorResolver;
import com.pig4cloud.pig.mold.security.MoldCapabilities;
import com.pig4cloud.pig.mold.web.MoldApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AuthService {

	private final ObjectMapper objectMapper;

	private final MoldActorResolver actorResolver;

	private final LoginRateLimiter rateLimiter;

	private final RestClient restClient;

	/** OAuth 客户端 ID（默认 mold）；生产由配置覆盖。 */
	private final String clientId;

	/** OAuth 客户端密钥；开发为 mold，生产必须由 MOLD_AUTH_CLIENT_SECRET 注入，不落仓库。 */
	private final String clientSecret;

	public AuthService(ObjectMapper objectMapper, MoldActorResolver actorResolver, LoginRateLimiter rateLimiter,
			@Value("${server.port:9999}") int serverPort,
			@Value("${server.servlet.context-path:}") String contextPath,
			@Value("${mold.auth.client-id:mold}") String clientId,
			@Value("${mold.auth.client-secret}") String clientSecret) {
		this.objectMapper = objectMapper;
		this.actorResolver = actorResolver;
		this.rateLimiter = rateLimiter;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.restClient = RestClient.builder().baseUrl("http://127.0.0.1:" + serverPort + contextPath).build();
	}

	public Map<String, Object> login(String loginName, String password, String ip) {
		String username = required(loginName, "loginName");
		String secret = required(password, "password");

		rateLimiter.check(username, ip);

		RawResponse response;
		try {
			response = requestToken(form -> {
				form.add("grant_type", "password");
				form.add("username", username);
				form.add("password", secret);
				form.add("scope", "server");
			});
		}
		catch (RuntimeException ex) {
			rateLimiter.onFailure(username);
			throw new MoldApiException(401, "INVALID_CREDENTIALS", "账号或密码错误");
		}

		if (response.status() < 200 || response.status() >= 300) {
			rateLimiter.onFailure(username);
			throw new MoldApiException(401, "INVALID_CREDENTIALS", "账号或密码错误");
		}

		rateLimiter.onSuccess(username);

		JsonNode token = parse(response.body());
		MoldActor actor = actorResolver.byLoginName(username);
		if (!"ACTIVE".equals(actor.status())) {
			throw new MoldApiException(403, "FORBIDDEN", "账号已停用");
		}

		return loginData(token, actor, capabilityPayload(actor));
	}

	/**
	 * 登录出参组装（G-01）：Bearer token 模式，无 cookie 会话，因此不返回 csrfToken。
	 * `token` 为兼容字段，与 `accessToken` 同源，保留不动。
	 * 抽出静态方法便于单测直接断言出参键集合（见 AuthServiceLoginDataTest）。
	 */
	static Map<String, Object> loginData(JsonNode token, MoldActor actor, Map<String, Object> capabilities) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("token", tokenDto(token));
		data.put("accessToken", token.path("access_token").asText(null));
		data.put("user", actor.toUserDto());
		data.putAll(capabilities);
		data.put("mustChangePassword", actor.mustChangePassword());
		return data;
	}

	public Map<String, Object> refresh(String refreshToken) {
		String token = required(refreshToken, "refreshToken");
		RawResponse response = requestToken(form -> {
			form.add("grant_type", "refresh_token");
			form.add("refresh_token", token);
			form.add("scope", "server");
		});
		if (response.status() < 200 || response.status() >= 300) {
			throw new MoldApiException(401, "UNAUTHENTICATED", "刷新令牌无效或已过期");
		}
		JsonNode node = parse(response.body());
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("token", tokenDto(node));
		data.put("accessToken", node.path("access_token").asText(null));
		return data;
	}

	public Map<String, Object> me() {
		MoldActor actor = actorResolver.current();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("user", actor.toUserDto());
		data.putAll(capabilityPayload(actor));
		data.put("mustChangePassword", actor.mustChangePassword());
		return data;
	}

	/**
	 * G1：roles / permissions / dataScopes。
	 * `capabilities` 保留为 permissions 的数组别名，避免第一切片调用方立刻返工。
	 */
	private Map<String, Object> capabilityPayload(MoldActor actor) {
		List<String> permissions = actorResolver.capabilities(actor.sysUserId());
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("roles", actor.roles());
		payload.put("permissions", permissions);
		payload.put("capabilities", permissions);
		payload.put("dataScopes", MoldCapabilities.dataScopes(actor.roles()));
		return payload;
	}

	public boolean logout(String token) {
		if (!StringUtils.hasText(token)) {
			return true;
		}
		try {
			restClient.delete()
				.uri("/token/logout")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.retrieve()
				.toBodilessEntity();
		}
		catch (RuntimeException ex) {
			// 吊销幂等：token 已失效时仍视为成功
			log.warn("注销时吊销 token 失败（按幂等处理）: {}", ex.getMessage());
		}
		return true;
	}

	private RawResponse requestToken(java.util.function.Consumer<MultiValueMap<String, String>> customizer) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		customizer.accept(form);
		return restClient.post()
			.uri("/oauth2/token")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.headers(headers -> headers.setBasicAuth(clientId, clientSecret))
			.body(form)
			.exchange((request, response) -> new RawResponse(response.getStatusCode().value(),
					StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8)));
	}

	private static Map<String, Object> tokenDto(JsonNode node) {
		Map<String, Object> token = new LinkedHashMap<>();
		token.put("accessToken", node.path("access_token").asText(null));
		token.put("refreshToken", node.path("refresh_token").asText(null));
		token.put("tokenType", node.path("token_type").asText("Bearer"));
		token.put("expiresIn", node.path("expires_in").asLong(0));
		token.put("scope", node.path("scope").asText(null));
		return token;
	}

	private JsonNode parse(String body) {
		try {
			return objectMapper.readTree(body);
		}
		catch (Exception ex) {
			throw new MoldApiException(500, "INTERNAL_ERROR", "认证服务返回无法解析");
		}
	}

	private String required(String value, String field) {
		if (!StringUtils.hasText(value)) {
			throw MoldApiException.validation("/body/" + field, "required", null, field + " 不能为空");
		}
		return value.trim();
	}

	private record RawResponse(int status, String body) {
	}

}
