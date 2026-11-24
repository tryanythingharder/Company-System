import { defineStore } from 'pinia';

/** 验证码类型枚举 */
export enum CaptchaType {
	ClickWord = 'clickWord',
	BlockPuzzle = 'blockPuzzle',
	Math = 'math',
	None = 'none',
}

/** 密码规则预设 */
export enum PasswordRule {
	LetterNumber = 'letterNumber',
	LetterNumberSymbol = 'letterNumberSymbol',
	UpperLowerNumberSymbol = 'upperLowerNumberSymbol',
}

/**
 * 网站配置类型定义
 */
export interface SiteConfig {
	clarityId: string;
	captchaType: CaptchaType | string;
	passwordRule: PasswordRule | string;
	forceResetPwd: boolean;
	forceLogout: boolean;
	smsLoginEnable: boolean;
	socialLoginEnable: boolean;
	registerEnable: boolean;
	resetPassword: boolean;
	i18nEnable: boolean;
	darkModeEnable: boolean;
	antiDebugEnable: boolean;
	antiDebugKey: string;
	title: string;
	footer: string;
	privacyTip: string;
	logo: string;
}

/** 默认配置（兜底值） */
const DEFAULT_SITE_CONFIG: SiteConfig = {
	clarityId: '',
	captchaType: CaptchaType.ClickWord,
	passwordRule: PasswordRule.LetterNumber,
	forceResetPwd: false,
	forceLogout: false,
	// 兜底值按内部系统收紧：配置接口取不到时，登录页不能冒出注册/短信/社交登录/找回密码入口
	smsLoginEnable: false,
	socialLoginEnable: false,
	registerEnable: false,
	resetPassword: false,
	i18nEnable: false,
	darkModeEnable: true,
	antiDebugEnable: false,
	antiDebugKey: 'pig',
	title: '模具加工管理系统',
	footer: '',
	privacyTip: '',
	logo: '',
};

export const useSiteConfig = defineStore('siteConfig', {
	state: (): { siteConfig: SiteConfig } => ({
		siteConfig: { ...DEFAULT_SITE_CONFIG },
	}),
	actions: {
		setSiteConfig(config: SiteConfig) {
			this.siteConfig = { ...DEFAULT_SITE_CONFIG, ...config, passwordRule: config?.passwordRule || DEFAULT_SITE_CONFIG.passwordRule };
		},
	},
});
