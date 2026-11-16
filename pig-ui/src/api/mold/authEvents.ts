/**
 * 模具模块认证事件（无依赖，避免 api 模块互相 import 形成环）
 *
 * 401 / 强制改密 403 的跳转统一在这里处理：
 *  - 真实 401：清会话并回登录页（网络故障 status=0 不走这里）
 *  - 403 PASSWORD_CHANGE_REQUIRED：标记强制改密并回改密页
 *
 * 前端跳转只是体验层，服务端仍会对每个请求重新鉴权。
 */

export const MUST_CHANGE_KEY = 'mold.mustChangePassword';

/** null = 本标签页尚未核对；true/false = 已核对结果 */
export function getMustChangeFlag(): boolean | null {
	try {
		const v = sessionStorage.getItem(MUST_CHANGE_KEY);
		if (v === '1') return true;
		if (v === '0') return false;
		return null;
	} catch {
		return null;
	}
}

export function setMustChangeFlag(value: boolean) {
	try {
		sessionStorage.setItem(MUST_CHANGE_KEY, value ? '1' : '0');
	} catch {
		/* 隐私模式忽略 */
	}
}

export function clearMustChangeFlag() {
	try {
		sessionStorage.removeItem(MUST_CHANGE_KEY);
	} catch {
		/* 隐私模式忽略 */
	}
}

/** 当前 hash 路由（去掉前导 #），用于登录后回跳 */
export function currentHashPath(): string {
	const hash = window.location.hash.replace(/^#/, '');
	if (!hash || hash.startsWith('/login')) return '/home';
	return hash;
}

/** 真实 401：清会话并进登录页；同一时刻只跳一次 */
let redirecting = false;
export function redirectToLogin() {
	if (redirecting) return;
	if (window.location.hash.startsWith('#/login')) return;
	redirecting = true;
	try {
		// 动态 import 避免与 storage 的初始化顺序耦合
		import('/@/utils/storage').then(({ Session }) => {
			Session.clear();
			window.location.hash = `#/login?redirect=${encodeURIComponent(currentHashPath())}`;
		});
	} finally {
		setTimeout(() => (redirecting = false), 1200);
	}
}

/** 强制改密：标记并进改密页；保留当前路由作为改密后的回跳目标 */
export function redirectToChangePassword() {
	setMustChangeFlag(true);
	if (window.location.hash.startsWith('#/change-password')) return;
	const next = currentHashPath();
	window.location.hash = `#/change-password?redirect=${encodeURIComponent(next)}`;
}
