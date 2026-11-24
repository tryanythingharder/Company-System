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

/**
 * 业务 errorCode → 标题与处理建议。
 * 409 只有 VERSION_CONFLICT 是并发修改，其余都是业务规则冲突，刷新解决不了，必须告诉用户该怎么办。
 * 正文优先用服务端 msg（已是中文且带具体数字），这里只补标题和下一步建议。
 */
const CODE_VIEWS: Record<string, { title: string; hint?: string }> = {
	// 409 业务冲突
	INVALID_STATE_TRANSITION: { title: '当前状态不允许这个操作', hint: '请按页面上可用的按钮推进状态。' },
	POLICY_NOT_CONFIRMED: { title: '该操作暂未开放', hint: '如确需处理，请联系老板确认流程。' },
	PROJECT_HAS_NO_STAGES: { title: '项目还没有阶段', hint: '请先新建阶段并全部完成后再完工。' },
	PROJECT_HAS_OPEN_STAGES: { title: '还有阶段没有完成', hint: '请先把所有阶段完成后再完工。' },
	STAGE_HAS_OPEN_ISSUES: { title: '该阶段还有未关闭的异常', hint: '请先处理或取消这些异常，再作废阶段。' },
	PROJECT_HAS_OPEN_ISSUES: { title: '还有未关闭的异常', hint: '请先处理并关闭异常后再完工。' },
	ALREADY_CORRECTED: { title: '这笔已经更正过', hint: '请在最新的那一笔上操作。' },
	ALREADY_REVERSED: { title: '这笔已经冲正过', hint: '同一笔流水只能冲正一次。' },
	ENTRY_ALREADY_VOID: { title: '这笔已经作废', hint: '已作废的记录不能再修改。' },
	RECONCILED_ENTRY: { title: '这笔已经对账', hint: '已对账的流水不能直接改，请用冲正处理。' },
	DUPLICATE_INVOICE_NO: { title: '发票号重复', hint: '请核对发票号，同一张发票不能重复登记。' },
	PURCHASE_CODE_DUPLICATE: { title: '采购单号重复', hint: '请换一个单号。' },
	LOGIN_NAME_DUPLICATE: { title: '登录名已被占用', hint: '请换一个登录名。' },
	LAST_OWNER_PROTECTED: { title: '不能移除最后一个老板账号', hint: '请先把另一个账号设为老板。' },
	SELF_DISABLE_DENIED: { title: '不能停用自己的账号' },
	SELF_OWNER_REMOVAL_DENIED: { title: '不能取消自己的老板角色', hint: '请由另一位老板操作。' },
	SELF_PASSWORD_RESET_DENIED: { title: '不能在这里重置自己的密码', hint: '请从右上角“修改密码”进入。' },
	IDEMPOTENCY_KEY_REUSED: { title: '重复提交的内容不一致', hint: '请关闭弹窗重新填写后再提交。' },
	IDEMPOTENCY_EXPIRED: { title: '提交已过期', hint: '请关闭弹窗重新填写后再提交。' },
	RETRYABLE_CONFLICT: { title: '同时有其他人在操作', hint: '请稍等几秒再提交一次。' },
	CONSTRAINT_VIOLATION: { title: '数据不满足约束', hint: '可能与已有记录冲突，请刷新后核对。' },
	// 403
	NOT_PROJECT_OWNER: { title: '只有项目负责人或老板可以操作', hint: '请联系该项目的负责人。' },
	STAGE_NOT_ASSIGNED: { title: '这个阶段没有分配给你', hint: '请联系项目负责人分配。' },
	// 422
	OVER_PAYMENT: { title: '付款金额超过应付余额' },
	OVER_INVOICE: { title: '开票金额超过采购金额' },
	OVER_RECEIPT: { title: '收货数量超过采购数量' },
	CURRENCY_MISMATCH: { title: '币种与采购单不一致' },
	FILE_TYPE_NOT_ALLOWED: { title: '不支持的文件类型', hint: '请上传图片、PDF 或 Office 文档。' },
	ATTACHMENT_OWNER_INVALID: { title: '附件所属记录无效', hint: '请刷新页面后重新上传。' },
	// 401 / 500
	INVALID_CREDENTIALS: { title: '账号或密码错误' },
	INTERNAL_ERROR: { title: '服务端出错', hint: '请把请求编号发给管理员排查。' },
};

function codeMessage(err: MoldApiError, fallback: string): string {
	const hint = (err.code && CODE_VIEWS[err.code]?.hint) || '';
	const base = err.message || fallback;
	return hint ? `${base}${/[。！？.!?]$/.test(base) ? '' : '。'}${hint}` : base;
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
		// 业务归属类 403（不是负责人、阶段没分给你）：说明原因和该找谁，而不是笼统的“无权限”
		if (err.code && CODE_VIEWS[err.code]) {
			return {
				kind: 'forbidden',
				title: CODE_VIEWS[err.code].title,
				message: codeMessage(err, '无此操作权限'),
				requestId,
				action: 'retry',
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

	if (err.status === 409 && err.code && err.code !== 'VERSION_CONFLICT') {
		// 业务规则冲突：刷新解决不了，给出具体原因与下一步
		return {
			kind: 'conflict',
			title: CODE_VIEWS[err.code]?.title || '当前数据状态不允许这个操作',
			message: codeMessage(err, '操作与当前数据状态冲突'),
			requestId,
			action: 'retry',
			status: 409,
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
		const codeView = err.code ? CODE_VIEWS[err.code] : undefined;
		return {
			kind: 'validation',
			title: codeView?.title || '提交内容不符合要求',
			message: fields ? `${fields}${allowed}` : `${codeView ? codeMessage(err, '参数校验失败') : err.message || '参数校验失败'}${allowed}`,
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
		title: (err.code && CODE_VIEWS[err.code]?.title) || '操作失败',
		message: err.code && CODE_VIEWS[err.code] ? codeMessage(err, fallback) : err.message || fallback,
		requestId,
		action: 'retry',
		status: err.status,
		code: err.code,
	};
}
