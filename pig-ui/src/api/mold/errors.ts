import { MoldApiError } from '/@/api/mold/projects';
import { redirectToChangePassword, redirectToLogin } from '/@/api/mold/authEvents';

/**
 * 模具模块统一错误描述（401 / 403 / 409 / 422 / 429 / 网络）
 *
 * 规则：
 *  - 真实 401：跳登录页（由 applyAuthRedirect 执行），页面只负责显示提示；
 *  - 403：不登出、不清空已加载数据，文案固定「无此操作权限；当前登录仍有效」；
 *  - 403 PASSWORD_CHANGE_REQUIRED：跳改密页；
 *  - 网络故障 / 超时（status=0）：绝不跳登录页，提示「无法核对登录会话」并可重试。
 */

export type MoldErrorAction = 'retry' | 'login' | 'change-password';

export type MoldErrorKind = 'forbidden' | 'conflict' | 'validation' | 'network' | 'rate-limit' | 'error';

export interface MoldErrorView {
	kind: MoldErrorKind;
	title: string;
	message: string;
	requestId: string;
	action: MoldErrorAction;
	status: number;
	code?: string;
}

const NETWORK_TITLE = '无法核对登录会话';
const NETWORK_MESSAGE = '无法连接服务端（网络中断或请求超时）。当前登录状态没有被清除，请检查网络后重试。';

/**
 * 判断是否 MoldApiError。
 * 开发态 HMR 或重复打包可能产生两份类，instanceof 会失效；此时按结构识别，
 * 避免把 404/409/422 误降级成「操作失败」。
 */
export function isApiError(err: unknown): err is MoldApiError {
	if (err instanceof MoldApiError) return true;
	const candidate = err as any;
	return (
		!!candidate &&
		typeof candidate === 'object' &&
		typeof candidate.status === 'number' &&
		typeof candidate.message === 'string' &&
		(candidate.name === 'MoldApiError' || 'requestId' in candidate || 'code' in candidate)
	);
}

/** 统一跳转：只处理「必须换页面」的两类状态；返回 true 表示已触发跳转 */
export function applyAuthRedirect(err: unknown): boolean {
	if (!isApiError(err)) return false;
	if (err.status === 401) {
		redirectToLogin();
		return true;
	}
	if (err.status === 403 && err.code === 'PASSWORD_CHANGE_REQUIRED') {
		redirectToChangePassword();
		return true;
	}
	return false;
}

export function describeMoldError(err: unknown, fallback = '操作失败，请稍后重试'): MoldErrorView {
	const requestId = isApiError(err) ? err.requestId || '' : '';

	if (!isApiError(err)) {
		return {
			kind: 'error',
			title: '操作失败',
			message: (err as any)?.message || fallback,
			requestId,
			action: 'retry',
			status: 0,
		};
	}

	if (err.status === 0) {
		return { kind: 'network', title: NETWORK_TITLE, message: NETWORK_MESSAGE, requestId, action: 'retry', status: 0 };
	}

	if (err.status === 401) {
		return {
			kind: 'error',
			title: '登录已失效',
			message: '请重新登录后重试；当前页面数据不再可信。',
			requestId,
			action: 'login',
			status: 401,
			code: err.code,
		};
	}

	if (err.status === 403) {
		if (err.code === 'PASSWORD_CHANGE_REQUIRED') {
			return {
				kind: 'forbidden',
				title: '需要先修改初始密码',
				message: '服务端要求先修改初始密码后才能继续操作。',
				requestId,
				action: 'change-password',
				status: 403,
				code: err.code,
			};
		}
		// 403 不是登录失效：不登出、不清空数据，只说明没有该操作权限
		const detail = err.message && err.message !== '无此操作权限' ? `（服务端说明：${err.message}）` : '';
		return {
			kind: 'forbidden',
			title: '无此操作权限',
			message: `无此操作权限；当前登录仍有效。${detail}`,
			requestId,
			action: 'retry',
			status: 403,
			code: err.code,
		};
	}

	if (err.status === 409) {
		const current = err.currentVersion ? `（最新版本 v${err.currentVersion}）` : '';
		return {
			kind: 'conflict',
			title: '数据已被他人修改',
			message: `${err.message || '提交的版本已过期'}${current}，你的修改没有保存，请重新加载后再提交。`,
			requestId,
			action: 'retry',
			status: 409,
			code: err.code,
		};
	}

	if (err.status === 422) {
		const fields = Object.entries(err.fieldErrors || {})
			.map(([k, v]) => `${k}：${v}`)
			.join('；');
		const allowed = err.allowedValues?.length ? `（允许值：${err.allowedValues.join('、')}）` : '';
		return {
			kind: 'validation',
			title: '提交内容不符合要求',
			message: `${fields || err.message || '参数校验失败'}${allowed}`,
			requestId,
			action: 'retry',
			status: 422,
			code: err.code,
		};
	}

	// 404：按契约是「资源不存在」（如 projectId 查不到），不再用「功能未上线」占位文案。
	if (err.status === 404) {
		const hasDomainCode = !!err.code && err.code !== 'NOT_FOUND';
		return {
			kind: 'error',
			title: '未找到对应数据',
			message: hasDomainCode
				? `资源不存在或已被删除（${err.code}）。`
				: '请求的接口或数据不存在（404），请确认链接与筛选条件后重试。',
			requestId,
			action: 'retry',
			status: 404,
			code: err.code || 'NOT_FOUND',
		};
	}

	if (err.status === 429) {
		const wait = err.retryAfterSeconds;
		return {
			kind: 'rate-limit',
			title: '操作过于频繁',
			message: wait
				? `请 ${wait} 秒后重试（服务端 Retry-After: ${wait}）。`
				: '请求过于频繁，请稍后重试。',
			requestId,
			action: 'retry',
			status: 429,
			code: err.code,
		};
	}

	return {
		kind: 'error',
		title: '操作失败',
		message: err.message || fallback,
		requestId,
		action: 'retry',
		status: err.status,
		code: err.code,
	};
}
