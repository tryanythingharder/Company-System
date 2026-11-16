/*
 * 模具加工管理系统 M2 后端切片
 * 认证与会话（契约 6.3）。
 */
package com.pig4cloud.pig.mold.auth;

import com.pig4cloud.pig.common.core.util.R;
import com.pig4cloud.pig.mold.security.MoldLogoutRequestFilter;
import com.pig4cloud.pig.mold.user.UserService;
import com.pig4cloud.pig.mold.web.MoldApiException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

	private final AuthService authService;

	private final UserService userService;

	private final ClientIpResolver clientIpResolver;

	@PostMapping("/login")
	public R<Map<String, Object>> login(@RequestBody Map<String, Object> body, HttpServletRequest request) {
		String loginName = string(body, "loginName");
		String password = string(body, "password");
		return R.ok(authService.login(loginName, password, clientIpResolver.resolve(request)));
	}

	@PostMapping("/refresh")
	public R<Map<String, Object>> refresh(@RequestBody Map<String, Object> body) {
		return R.ok(authService.refresh(string(body, "refreshToken")));
	}

	@GetMapping("/me")
	public R<Map<String, Object>> me() {
		return R.ok(authService.me());
	}

	/**
	 * 注销固定 200（契约：重复注销不报 500）。/auth/logout 已在 ignore-urls，
	 * 这里直接从 Authorization 头取 token，失效 token 也按幂等成功处理。
	 */
	@PostMapping("/logout")
	public R<Boolean> logout(HttpServletRequest request) {
		Object moved = request.getAttribute(MoldLogoutRequestFilter.TOKEN_ATTRIBUTE);
		String token = moved == null ? bearerToken(request) : moved.toString();
		return R.ok(authService.logout(token));
	}

	/**
	 * 自助改密（G-04）：旧口令 + 新口令；成功后 mustChangePassword=false，
	 * 当前 access token 继续有效，且任何出参不回显口令。
	 */
	@PostMapping("/password")
	public R<Map<String, Object>> changePassword(@RequestBody Map<String, Object> body) {
		return R.ok(userService.changeOwnPassword(string(body, "oldPassword"), string(body, "newPassword")));
	}

	private String bearerToken(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (StringUtils.hasText(header) && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			String token = header.substring(7).trim();
			return StringUtils.hasText(token) ? token : null;
		}
		return null;
	}

	private String string(Map<String, Object> body, String field) {
		Object value = body == null ? null : body.get(field);
		if (value == null) {
			throw MoldApiException.validation("/body/" + field, "required", null, field + " 不能为空");
		}
		return value.toString();
	}

}
