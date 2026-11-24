import request from '/@/utils/request';
import { Session } from '/@/utils/storage';
import { validateNull } from '/@/utils/validate';
import { useUserInfo } from '/@/stores/userInfo';
import { refAutoReset } from '@vueuse/core';

/**
 * https://www.ietf.org/rfc/rfc6749.txt
 * OAuth 协议 4.3.1 要求格式为 form 而不是 JSON 注意！
 */
const FORM_CONTENT_TYPE = 'application/x-www-form-urlencoded';

/**
 * OAuth 客户端类型枚举
 */
enum OAuth2ClientType {
	PASSWORD = 'VITE_OAUTH2_PASSWORD_CLIENT',
	MOBILE = 'VITE_OAUTH2_MOBILE_CLIENT',
	SOCIAL = 'VITE_OAUTH2_SOCIAL_CLIENT',
}

/**
 * 获取并设置 Basic Auth
 * @param clientType - OAuth 客户端类型
 * @returns Basic Auth 字符串
 */
const getBasicAuth = (clientType: OAuth2ClientType): string => {
	const clientId = import.meta.env[clientType];
	const basicAuth = 'Basic ' + window.btoa(clientId);
	Session.set('basicAuth', basicAuth);
	return basicAuth;
};

// 登录方式
export enum LoginTypeEnum {
	PASSWORD,
	MOBILE,
	REGISTER,
	EXPIRE,
	QRCODE,
	FORGET,
}

// 登录错误信息
export enum LoginErrorEnum {
	CREDENTIALS_EXPIRED = 'credentials_expired', // 密码过期
}

/**
 * 社交登录方式枚举
 */
export enum SocialLoginEnum {
	SMS = 'SMS', // 验证码登录
	APP_SMS = 'APP-SMS', // APP验证码登录
	QQ = 'QQ', // QQ登录
	WECHAT = 'WX', // 微信登录
	DINGTALK = 'DINGTALK', // 钉钉登录
	WEIXIN_CP = 'WEIXIN_CP', // 企业微信登录
	MINI_APP = 'MINI', // 微信小程序
	GITEE = 'GITEE', // 码云登录
	OSC = 'OSC', // 开源中国登录
}

/**
 * 登录
 *
 * 统一走 M2 的 POST /admin/auth/login（D3 限流 + mustChangePassword 与后端同一套语义），
 * 不再直接调 Pig 原生 /auth/oauth2/token（那条路径锁号只回 401 user_locked，没有 429/Retry-After）。
 * 该端点内部再转发 Pig 的 password 模式，所以口令按它要求发明文；出参 data.accessToken 即令牌。
 * @param data
 */
export const login = (data: any) => {
	return request({
		url: '/admin/auth/login',
		method: 'post',
		data: {
			loginName: data.loginName ?? data.username,
			password: data.password,
		},
		headers: {
			skipToken: true,
			// 关闭请求体加密（Pig 的 mold 客户端 enc_flag=0，见 pig-ui/.env.development.local）
			'Enc-Flag': 'false',
		},
	});
};

export const loginByMobile = (mobile: any, code: any) => {
	const grant_type = 'mobile';
	const scope = 'server';
	const basicAuth = getBasicAuth(OAuth2ClientType.MOBILE);

	return request({
		url: '/auth/oauth2/token',
		headers: {
			skipToken: true,
			Authorization: basicAuth,
			'Content-Type': FORM_CONTENT_TYPE,
		},
		method: 'post',
		params: { mobile: `${SocialLoginEnum.SMS}@${mobile}`, code: code, grant_type, scope },
	});
};

export const loginBySocial = (state: SocialLoginEnum, code: string) => {
	const grant_type = 'mobile';
	const scope = 'server';
	const basicAuth = getBasicAuth(OAuth2ClientType.SOCIAL);

	return request({
		url: '/auth/oauth2/token',
		headers: {
			skipToken: true,
			Authorization: basicAuth,
			'Content-Type': FORM_CONTENT_TYPE,
		},
		method: 'post',
		params: { mobile: `${state}@${code}`, code: code, grant_type, scope },
	});
};

export interface SendMobileCodeParams {
	mobile: string;
	registered?: boolean;
	captchaType: string;
	captchaVerification: string;
}

export const sendMobileCode = (params: SendMobileCodeParams) => {
	const { mobile, registered = true, captchaType, captchaVerification } = params;
	return request({
		url: '/admin/sysMessage/send/smsCode',
		method: 'get',
		params: { mobile, registered, captchaType, captchaVerification },
	});
};

export const refreshTokenApi = (refreshToken: string) => {
	// 生产加固：走后端 /admin/auth/refresh 适配层，OAuth 客户端密钥只留在服务端，
	// 不再在浏览器里拼 Basic 认证直调 Pig 原生 /oauth2/token（那需要把客户端密钥打进前端产物）。
	return request({
		url: '/admin/auth/refresh',
		headers: { skipToken: true, 'Enc-Flag': 'false' },
		method: 'post',
		data: { refreshToken },
	});
};

// Token 刷新锁 - 使用 VueUse 的 refAutoReset 防止并发刷新
const tokenRefreshLock = refAutoReset(false, 100);

/** 登录/刷新时记录的访问令牌过期时间戳（毫秒） */
export const TOKEN_EXPIRES_AT_KEY = 'token_expires_at';

/** 按响应里的 expiresIn（秒）记录过期时间 */
export const rememberTokenExpiry = (expiresIn?: number) => {
	const seconds = Number(expiresIn) || 0;
	if (seconds > 0) Session.set(TOKEN_EXPIRES_AT_KEY, Date.now() + seconds * 1000);
};

/**
 * 若访问令牌有效期小于半小时则自动续期。
 * 过期时间取自登录/刷新响应的 expiresIn，本地判断即可，不再调用 Pig 原生 /token/check_token
 * （那条路径同样需要客户端 Basic 认证，且对外已由 nginx 屏蔽）。
 * @returns {Promise<boolean>} 返回是否需要停止定时器
 */
export const checkToken = async (): Promise<boolean> => {
	try {
		if (!Session.getToken()) return true;
		const expire = Number(Session.get(TOKEN_EXPIRES_AT_KEY)) || 0;
		if (!expire) return false;

		const HALF_HOUR = 30 * 60 * 1000;
		if (expire - Date.now() <= HALF_HOUR && !tokenRefreshLock.value) {
			tokenRefreshLock.value = true;
			try {
				await useUserInfo().refreshToken();
			} catch (error) {
				return true; // 刷新失败，停止定时器
			} finally {
				tokenRefreshLock.value = false;
			}
		}
		return false; // 继续运行定时器
	} catch (error) {
		return true;
	}
};

/**
 * 获取用户信息
 */
export const getUserInfo = () => {
	return request({
		url: '/admin/user/info',
		method: 'get',
	});
};

/**
 * 注销
 *
 * 后端契约为 M2 的 POST /admin/auth/logout（Authorization: Bearer <accessToken>，无 body），
 * 固定返回 200 且幂等：token 已吊销/格式非法也按成功处理，不会 401。
 * 不再使用 Pig 原生的 DELETE /auth/token/logout（资源服务器会在进控制器前把已吊销 token 判 401）。
 */
export const logout = () => {
	return request({
		url: '/admin/auth/logout',
		method: 'post',
	});
};
