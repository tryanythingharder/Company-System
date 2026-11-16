import axios from 'axios';
import { Session } from '/@/utils/storage';
import { redirectToChangePassword, redirectToLogin } from '/@/api/mold/authEvents';

/**
 * 模具公司进度 · G3 项目接口（M2 后端真实接口）
 *
 * 契约：coordination/pig/architecture/phase1-model-contract-20261112.md 6.5
 *   GET   /admin/mold/projects                 列表（offset：pageNum/pageSize -> rows/total）
 *   GET   /admin/mold/projects/{id}            详情
 *   PATCH /admin/mold/projects/{id}            编辑（If-Match 必填；name/dueDate/customerId/note）
 *   POST  /admin/mold/projects/{id}/complete   确认完工（If-Match + Idempotency-Key）
 *   PUT   /admin/mold/projects/{id}/owner      变更负责人
 *   GET   /admin/mold/projects/{id}/stages     阶段明细（G4）
 *   GET   /admin/mold/issues?projectId=        项目异常（G5，子资源不分页）
 *   GET   /admin/mold/projects/{id}/members    项目成员（G5，子资源不分页）
 *   GET   /admin/mold/users                    账号目录（G2，仅 OWNER）
 *
 * 真实接口是唯一主路径；mock 只在 DEV 且 VITE_MOLD_MOCK_FALLBACK=true / ?mock=1 时降级，
 * 页面必须显示「示例数据」提示条。
 */

export type MoldBusinessType = 'MOLD_SET' | 'PART_PROCESS';
export type MoldProjectStatus = 'ACTIVE' | 'ON_HOLD' | 'COMPLETED' | 'CANCELLED';

export interface MoldStageCounts {
	total: number;
	notStarted: number;
	inProgress: number;
	blocked: number;
	completed: number;
}

export interface MoldProjectRow {
	id: string;
	code: string;
	name: string;
	businessType: MoldBusinessType;
	status: MoldProjectStatus;
	owner: { id: string; displayName: string };
	ownerUserId: string;
	customerId?: string | null;
	dueDate: string | null;
	stageCounts: MoldStageCounts;
	/** 后端汇总字段：受阻阶段数（兼容旧响应时回退 stageCounts.blocked） */
	blockedStageCount?: number;
	openIssueCount: number;
	lastUpdatedAt: string;
	version: number;
	completedAt?: string | null;
	completedBy?: string | null;
	completionNote?: string | null;
	reopenedAt?: string | null;
	note?: string | null;
}

export type MoldIssueSeverity = 'LOW' | 'MEDIUM' | 'HIGH';
export type MoldIssueStatus = 'OPEN' | 'HANDLING' | 'RESOLVED' | 'CANCELLED';

/** 项目异常（契约 6.7 GET /issues） */
export interface MoldIssueRow {
	id: string;
	projectId: string;
	stageId?: string | null;
	title: string;
	description?: string | null;
	severity: MoldIssueSeverity;
	status: MoldIssueStatus;
	assigneeUserId?: string | null;
	assigneeDisplayName?: string | null;
	reporterId?: string | null;
	reporterDisplayName?: string | null;
	resolution?: string | null;
	version: number;
	occurredAt: string;
	resolvedAt?: string | null;
	createdAt?: string;
	updatedAt?: string;
}

/** 项目成员（契约 6.5 GET /projects/{id}/members） */
export interface MoldProjectMember {
	id?: string;
	userId: string;
	displayName?: string | null;
	role?: string | null;
	joinedAt?: string | null;
}

export interface MoldProjectQuery {
	pageNum: number;
	pageSize: number;
	q?: string;
	businessType?: string;
	status?: string;
	sort?: string;
	order?: 'asc' | 'desc';
}

export interface MoldProjectPage {
	rows: MoldProjectRow[];
	total: number;
	pageNum: number;
	pageSize: number;
	pages: number;
	asOf?: string;
	dataVersion?: string | number;
	requestId?: string;
}

export interface MoldUserRow {
	id: string;
	loginName: string;
	displayName: string;
	phone?: string | null;
	status: string;
	roles: string[];
}

/** 业务/鉴权错误：页面按 status 渲染 401/403/404/409/422 */
export class MoldApiError extends Error {
	status: number;
	code?: string;
	requestId?: string;
	allowedValues?: string[];
	/** instancePath -> message，用于表单字段定位 */
	fieldErrors: Record<string, string>;
	/** 409 VERSION_CONFLICT 时服务端返回的最新版本 */
	currentVersion?: number;
	/** 服务端 details 原文（完工 D1 缺项、409 原因等） */
	details?: any;
	/** 429 时服务端 Retry-After 秒数 */
	retryAfterSeconds?: number;
	constructor(
		message: string,
		status: number,
		code?: string,
		requestId?: string,
		options?: {
			allowedValues?: string[];
			fieldErrors?: Record<string, string>;
			currentVersion?: number;
			details?: any;
			retryAfterSeconds?: number;
		}
	) {
		super(message);
		this.name = 'MoldApiError';
		this.status = status;
		this.code = code;
		this.requestId = requestId;
		this.allowedValues = options?.allowedValues;
		this.fieldErrors = options?.fieldErrors || {};
		this.currentVersion = options?.currentVersion;
		this.details = options?.details;
		this.retryAfterSeconds = options?.retryAfterSeconds;
	}
}

export const SORTABLE = ['code', 'name', 'businessType', 'status', 'lastUpdatedAt'] as const;

/**
 * 业务接口专用 axios 实例：不用 /@/utils/request，因为它把 HTTP 状态折叠成 body.code，
 * 无法区分 401/403/404/409/422，而四态与写操作提示依赖真实状态码。
 */
export const moldClient = axios.create({
	baseURL: import.meta.env.VITE_API_URL,
	timeout: 10000,
	headers: { Accept: 'application/json' },
});

export function newRequestId(): string {
	const uuid =
		typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
			? crypto.randomUUID()
			: `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`;
	return `req_${uuid.replace(/-/g, '').slice(0, 24)}`;
}

export function newIdempotencyKey(): string {
	const uuid =
		typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
			? crypto.randomUUID()
			: `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`;
	return `idem_${uuid.replace(/-/g, '')}`;
}

moldClient.interceptors.request.use((config) => {
	const token = Session.getToken();
	if (token) config.headers.Authorization = `Bearer ${token}`;
	if (!config.headers['X-Request-Id']) config.headers['X-Request-Id'] = newRequestId();
	return config;
});

function parseFieldErrors(errors: any[]): Record<string, string> {
	const out: Record<string, string> = {};
	for (const e of errors) {
		const path = String(e?.instancePath || '');
		const key = path.replace(/^\/(body|query|header)\//, '');
		if (key) out[key] = e?.message || '参数无效';
	}
	return out;
}

export function toApiError(error: any): MoldApiError {
	const status = Number(error?.response?.status) || 0;
	const body = error?.response?.data;
	const data = body?.data ?? body;
	const errors = Array.isArray(data?.details?.errors) ? data.details.errors : [];
	const requestId =
		data?.requestId ?? body?.requestId ?? error?.config?.headers?.['X-Request-Id'] ?? error?.config?.headers?.['x-request-id'];
	const message =
		data?.msg ||
		body?.msg ||
		body?.message ||
		(errors[0]?.message as string) ||
		(error?.code === 'ECONNABORTED' ? '请求超时' : error?.message) ||
		'请求失败';
	const retryAfterRaw = error?.response?.headers?.['retry-after'];
	const retryAfterSeconds = Number(retryAfterRaw) > 0 ? Number(retryAfterRaw) : undefined;
	return new MoldApiError(message, status === 424 ? 401 : status, data?.errorCode, requestId, {
		allowedValues: errors[0]?.params?.allowedValues,
		fieldErrors: parseFieldErrors(errors),
		currentVersion: typeof data?.details?.current === 'number' ? data.details.current : undefined,
		details: data?.details,
		retryAfterSeconds,
	});
}

/**
 * 全局认证跳转（只做页面跳转，不清业务数据）：
 *  - 401：清会话回登录页；
 *  - 403 PASSWORD_CHANGE_REQUIRED：回改密页；
 *  - 网络故障（status=0）与普通 403 不跳转，由页面就地提示。
 */
export function handleMoldAuthFailure(err: unknown) {
	if (!(err instanceof MoldApiError)) return;
	if (err.status === 401) redirectToLogin();
	else if (err.status === 403 && err.code === 'PASSWORD_CHANGE_REQUIRED') redirectToChangePassword();
}

moldClient.interceptors.response.use(
	(res) => res,
	(error) => {
		const apiError = toApiError(error);
		handleMoldAuthFailure(apiError);
		throw apiError;
	}
);

export function unwrap(res: any): any {
	return res?.data?.data ?? res?.data;
}

/* ------------------------------- 列表 / 详情 ------------------------------- */

export async function fetchProjectPage(query: MoldProjectQuery): Promise<MoldProjectPage> {
	const params: Record<string, any> = {
		pageNum: query.pageNum,
		pageSize: query.pageSize,
	};
	// 07 已实现服务端 q（编号/名称模糊匹配），不再拉全量本地过滤
	if (query.q) params.q = query.q;
	if (query.businessType) params.businessType = query.businessType;
	if (query.status) params.status = query.status;
	if (query.sort && (SORTABLE as readonly string[]).includes(query.sort)) {
		params.sort = query.sort;
		params.order = query.order === 'desc' ? 'desc' : 'asc';
	}

	let res: any;
	try {
		res = await moldClient.get('/admin/mold/projects', { params });
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	const payload = unwrap(res);
	const rows: MoldProjectRow[] = Array.isArray(payload?.rows) ? payload.rows : [];
	const total = Number(payload?.total ?? rows.length);
	return {
		rows,
		total: Number.isFinite(total) ? total : rows.length,
		pageNum: Number(payload?.pageNum ?? query.pageNum),
		pageSize: Number(payload?.pageSize ?? query.pageSize),
		pages: Number(payload?.pages ?? 1),
		asOf: payload?.asOf,
		dataVersion: payload?.dataVersion,
	};
}

/** D1：逐页取完全部筛选结果（最多 1000 页防死循环） */
export async function fetchAllProjects(query: Omit<MoldProjectQuery, 'pageNum' | 'pageSize'>): Promise<{
	rows: MoldProjectRow[];
	total: number;
	pagesFetched: number;
	pageSize: number;
}> {
	const envSize = Number(import.meta.env.VITE_MOLD_EXPORT_PAGE_SIZE) || 100;
	const pageSize = Math.min(100, Math.max(1, envSize));
	const rows: MoldProjectRow[] = [];
	let total = Number.POSITIVE_INFINITY;
	let pageNum = 1;
	let pagesFetched = 0;
	while (rows.length < total && pageNum <= 1000) {
		const page = await fetchProjectPage({ ...query, pageNum, pageSize });
		pagesFetched += 1;
		total = page.total;
		rows.push(...page.rows);
		if (!page.rows.length) break;
		pageNum += 1;
	}
	return { rows, total, pagesFetched, pageSize };
}

export async function fetchProjectDetail(id: string): Promise<MoldProjectRow> {
	let res: any;
	try {
		res = await moldClient.get(`/admin/mold/projects/${encodeURIComponent(id)}`);
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	return unwrap(res) as MoldProjectRow;
}

/* ------------------------------- 写操作 ------------------------------- */

function versionHeader(version: number) {
	// 契约：If-Match: "<version>"；服务端也接受裸数字，带引号更符合 HTTP 语义
	return `"${version}"`;
}

/** 项目创建入参（契约 6.5 POST /projects；字段同 ProjectService.create 的 rejectUnknownFields 白名单） */
export interface MoldProjectCreateBody {
	code: string;
	name: string;
	businessType: MoldBusinessType;
	ownerUserId: string;
	dueDate?: string | null;
	note?: string | null;
}

/** 新建项目（契约 6.5：幂等必填） */
export async function createProject(body: MoldProjectCreateBody, idempotencyKey?: string): Promise<MoldProjectRow> {
	let res: any;
	try {
		res = await moldClient.post('/admin/mold/projects', body, {
			headers: { 'Idempotency-Key': idempotencyKey || newIdempotencyKey() },
		});
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	return unwrap(res) as MoldProjectRow;
}

export async function updateProject(
	id: string,
	body: { name?: string; dueDate?: string | null; customerId?: string | null; note?: string | null },
	version: number,
	idempotencyKey?: string
): Promise<MoldProjectRow> {
	let res: any;
	try {
		res = await moldClient.patch(`/admin/mold/projects/${encodeURIComponent(id)}`, body, {
			headers: { 'If-Match': versionHeader(version), ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}) },
		});
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	return unwrap(res) as MoldProjectRow;
}

export async function completeProject(
	id: string,
	completionNote: string,
	version: number,
	idempotencyKey?: string
): Promise<MoldProjectRow> {
	let res: any;
	try {
		res = await moldClient.post(
			`/admin/mold/projects/${encodeURIComponent(id)}/complete`,
			{ completionNote },
			{
				headers: {
					'If-Match': versionHeader(version),
					'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
				},
			}
		);
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	return unwrap(res) as MoldProjectRow;
}

export async function addProjectStage(
	id: string,
	body: { name: string; sortOrder?: number; plannedStart?: string | null; plannedEnd?: string | null; note?: string | null },
	idempotencyKey?: string
): Promise<any> {
	let res: any;
	try {
		res = await moldClient.post(`/admin/mold/projects/${encodeURIComponent(id)}/stages`, body, {
			headers: { 'Idempotency-Key': idempotencyKey || newIdempotencyKey() },
		});
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	return unwrap(res);
}

export async function changeProjectOwner(
	id: string,
	ownerUserId: string,
	reason: string,
	idempotencyKey?: string
): Promise<MoldProjectRow> {
	let res: any;
	try {
		res = await moldClient.put(
			`/admin/mold/projects/${encodeURIComponent(id)}/owner`,
			{ ownerUserId, reason },
			{ headers: { 'Idempotency-Key': idempotencyKey || newIdempotencyKey() } }
		);
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	return unwrap(res) as MoldProjectRow;
}

/**
 * 全量导出（D1 / 修复轮）：直接调用后端 CSV 流，不再逐页拼接。
 * 返回 Blob 与服务端文件名；401/403 由统一拦截器处理。
 */
export async function exportProjectsCsv(query: Omit<MoldProjectQuery, 'pageNum' | 'pageSize'>): Promise<{
	blob: Blob;
	filename: string;
}> {
	const params: Record<string, any> = {};
	if (query.q) params.q = query.q;
	if (query.businessType) params.businessType = query.businessType;
	if (query.status) params.status = query.status;
	if (query.sort && (SORTABLE as readonly string[]).includes(query.sort)) {
		params.sort = query.sort;
		params.order = query.order === 'desc' ? 'desc' : 'asc';
	}
	let res: any;
	try {
		res = await moldClient.get('/admin/mold/projects/export', { params, responseType: 'blob', timeout: 60000 });
	} catch (err) {
		// blob 响应下的错误体也是 Blob，先读回 JSON 再转统一错误
		if (axios.isAxiosError(err) && err.response?.data instanceof Blob) {
			try {
				const text = await err.response.data.text();
				err.response.data = JSON.parse(text);
			} catch {
				/* 保持原样 */
			}
		}
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	const disposition = String(res.headers?.['content-disposition'] || '');
	const match = /filename="?([^";]+)"?/.exec(disposition);
	return { blob: res.data as Blob, filename: match?.[1] || `projects-${Date.now()}.csv` };
}

export async function fetchProjectStages(id: string): Promise<any[]> {
	let res: any;
	try {
		res = await moldClient.get(`/admin/mold/projects/${encodeURIComponent(id)}/stages`);
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	const payload = unwrap(res);
	return Array.isArray(payload?.rows) ? payload.rows : Array.isArray(payload) ? payload : [];
}

/** 项目异常明细（契约 6.7）：子资源列表不分页，返回 {rows,total,asOf,dataVersion} */
export async function fetchProjectIssues(projectId: string): Promise<MoldIssueRow[]> {
	let res: any;
	try {
		res = await moldClient.get('/admin/mold/issues', { params: { projectId } });
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	const payload = unwrap(res);
	return Array.isArray(payload?.rows) ? payload.rows : Array.isArray(payload) ? payload : [];
}

/* ------------------------------- 异常写操作（契约 6.7） ------------------------------- */

/** 新建异常入参：projectId/title/description/severity 必填；stageId/assigneeUserId 可选 */
export interface MoldIssueCreateBody {
	projectId: string;
	stageId?: string | null;
	title: string;
	description: string;
	severity: MoldIssueSeverity;
	assigneeUserId?: string | null;
}

/** 新建异常（契约 6.7：幂等必填，`mold:issue:create`） */
export async function createIssue(body: MoldIssueCreateBody, idempotencyKey?: string): Promise<MoldIssueRow> {
	let res: any;
	try {
		res = await moldClient.post('/admin/mold/issues', body, {
			headers: { 'Idempotency-Key': idempotencyKey || newIdempotencyKey() },
		});
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	return unwrap(res) as MoldIssueRow;
}

/** 标记处理（契约 6.7：幂等必填，`mold:issue:handle`；resolution 必填） */
export async function resolveIssue(issueId: string, resolution: string, idempotencyKey?: string): Promise<MoldIssueRow> {
	let res: any;
	try {
		res = await moldClient.post(
			`/admin/mold/issues/${encodeURIComponent(issueId)}/resolve`,
			{ resolution },
			{ headers: { 'Idempotency-Key': idempotencyKey || newIdempotencyKey() } }
		);
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	return unwrap(res) as MoldIssueRow;
}

/** 取消异常（契约 6.7：幂等必填，`mold:issue:cancel`；reason 必填） */
export async function cancelIssue(issueId: string, reason: string, idempotencyKey?: string): Promise<MoldIssueRow> {
	let res: any;
	try {
		res = await moldClient.post(
			`/admin/mold/issues/${encodeURIComponent(issueId)}/cancel`,
			{ reason },
			{ headers: { 'Idempotency-Key': idempotencyKey || newIdempotencyKey() } }
		);
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	return unwrap(res) as MoldIssueRow;
}

/** 项目成员（契约 6.5）：返回 {rows,total,asOf,dataVersion} */
export async function fetchProjectMembers(id: string): Promise<MoldProjectMember[]> {
	let res: any;
	try {
		res = await moldClient.get(`/admin/mold/projects/${encodeURIComponent(id)}/members`);
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	const payload = unwrap(res);
	return Array.isArray(payload?.rows) ? payload.rows : Array.isArray(payload) ? payload : [];
}

/* ------------------------------- 账号与能力 ------------------------------- */

export async function fetchUsers(): Promise<MoldUserRow[]> {
	let res: any;
	try {
		res = await moldClient.get('/admin/mold/users', { params: { pageNum: 1, pageSize: 100 } });
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	const payload = unwrap(res);
	return Array.isArray(payload?.rows) ? payload.rows : [];
}

export interface MoldCapabilities {
	user: { id: string; loginName: string; displayName: string; roles: string[] };
	capabilities: string[];
	roles: string[];
	dataScopes?: Record<string, string>;
	mustChangePassword: boolean;
}

export async function fetchCapabilities(): Promise<MoldCapabilities> {
	let res: any;
	try {
		res = await moldClient.get('/admin/auth/me');
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	const payload = unwrap(res);
	const roles = Array.isArray(payload?.roles) ? payload.roles : Array.isArray(payload?.user?.roles) ? payload.user.roles : [];
	return {
		user: payload?.user,
		capabilities: Array.isArray(payload?.capabilities) ? payload.capabilities : [],
		roles,
		dataScopes: payload?.dataScopes,
		mustChangePassword: payload?.mustChangePassword === true,
	};
}

/* =============================================================================
 * DEV 降级 mock（仅 VITE_MOLD_MOCK_FALLBACK=true 或 ?mock=1）
 * 字段与真实 DTO 一致；页面必须显示「示例数据」提示条，不得冒充真实数据。
 * ========================================================================== */

const sc = (completed: number, total: number): MoldStageCounts => ({
	total,
	notStarted: total - completed,
	inProgress: 0,
	blocked: 0,
	completed,
});

const MOCK_PROJECTS: MoldProjectRow[] = [
	{
		id: 'mock-001',
		code: 'DEMO-2026-001',
		name: '汽车内饰件精密注塑模',
		businessType: 'MOLD_SET',
		status: 'COMPLETED',
		owner: { id: 'u1', displayName: '林嘉禾' },
		ownerUserId: 'u1',
		dueDate: '2026-10-20',
		stageCounts: sc(8, 8),
		openIssueCount: 0,
		lastUpdatedAt: '2026-11-09T06:27:00Z',
		version: 3,
		completionNote: '试模合格，客户签收，整单完工。',
		completedAt: '2026-11-09T06:27:00Z',
		note: '一模两腔，客户已验收。',
	},
	{
		id: 'mock-002',
		code: 'DEMO-2026-002',
		name: '家电外壳注塑模',
		businessType: 'MOLD_SET',
		status: 'COMPLETED',
		owner: { id: 'u2', displayName: '周若岚' },
		ownerUserId: 'u2',
		dueDate: '2026-11-15',
		stageCounts: sc(6, 6),
		openIssueCount: 0,
		lastUpdatedAt: '2026-11-09T06:27:00Z',
		version: 2,
		completionNote: '整单完工。',
		completedAt: '2026-11-09T06:27:00Z',
	},
	{
		id: 'mock-003',
		code: 'DEMO-2026-003',
		name: '医疗耗材多腔模',
		businessType: 'MOLD_SET',
		status: 'ACTIVE',
		owner: { id: 'u1', displayName: '林嘉禾' },
		ownerUserId: 'u1',
		dueDate: '2026-12-05',
		stageCounts: sc(2, 8),
		openIssueCount: 0,
		lastUpdatedAt: '2026-11-10T01:12:00Z',
		version: 1,
		note: '示例数据。',
	},
	{
		id: 'mock-004',
		code: 'DEMO-2026-004',
		name: '连接器端子级进模',
		businessType: 'PART_PROCESS',
		status: 'ACTIVE',
		owner: { id: 'u2', displayName: '周若岚' },
		ownerUserId: 'u2',
		dueDate: '2026-11-28',
		stageCounts: sc(3, 9),
		openIssueCount: 1,
		lastUpdatedAt: '2026-11-10T08:40:00Z',
		version: 1,
	},
	{
		id: 'mock-005',
		code: 'DEMO-2026-005',
		name: '动力电池托盘零件',
		businessType: 'PART_PROCESS',
		status: 'ACTIVE',
		owner: { id: 'u1', displayName: '林嘉禾' },
		ownerUserId: 'u1',
		dueDate: '2026-12-18',
		stageCounts: sc(3, 9),
		openIssueCount: 0,
		lastUpdatedAt: '2026-11-11T00:05:00Z',
		version: 1,
	},
	{
		id: 'mock-006',
		code: 'DEMO-2026-006',
		name: '光学镜片模具',
		businessType: 'MOLD_SET',
		status: 'ACTIVE',
		owner: { id: 'u2', displayName: '周若岚' },
		ownerUserId: 'u2',
		dueDate: '2027-01-15',
		stageCounts: sc(0, 6),
		openIssueCount: 0,
		lastUpdatedAt: '2026-11-11T02:22:00Z',
		version: 1,
	},
	{
		id: 'mock-007',
		code: 'DEMO-2026-007',
		name: '五金冲压件加工',
		businessType: 'PART_PROCESS',
		status: 'ACTIVE',
		owner: { id: 'u1', displayName: '林嘉禾' },
		ownerUserId: 'u1',
		dueDate: '2026-11-10',
		stageCounts: sc(0, 4),
		openIssueCount: 0,
		lastUpdatedAt: '2026-11-11T09:31:00Z',
		version: 1,
	},
	{
		id: 'mock-008',
		code: 'DEMO-2026-008',
		name: '包装吸塑模',
		businessType: 'MOLD_SET',
		status: 'ON_HOLD',
		owner: { id: 'u3', displayName: '陈启明' },
		ownerUserId: 'u3',
		dueDate: '2026-12-30',
		stageCounts: sc(4, 5),
		openIssueCount: 0,
		lastUpdatedAt: '2026-11-12T00:47:00Z',
		version: 1,
	},
];

const MOCK_LATENCY = 160;

export function fetchMockProjectPage(query: MoldProjectQuery, forceEmpty = false): Promise<MoldProjectPage> {
	return new Promise((resolve) => {
		setTimeout(() => {
			let rows = forceEmpty ? [] : MOCK_PROJECTS.slice();
			const q = (query.q || '').trim().toLowerCase();
			if (q) rows = rows.filter((r) => r.code.toLowerCase().includes(q) || r.name.toLowerCase().includes(q));
			if (query.businessType) rows = rows.filter((r) => r.businessType === query.businessType);
			if (query.status) rows = rows.filter((r) => r.status === query.status);
			if (query.sort) {
				const dir = query.order === 'desc' ? -1 : 1;
				const key = query.sort as keyof MoldProjectRow;
				rows.sort((a, b) => {
					const av = String((a as any)[key] ?? '');
					const bv = String((b as any)[key] ?? '');
					return av === bv ? a.id.localeCompare(b.id) : av > bv ? dir : -dir;
				});
			} else {
				rows.sort((a, b) => a.code.localeCompare(b.code) || a.id.localeCompare(b.id));
			}
			const total = rows.length;
			const start = (query.pageNum - 1) * query.pageSize;
			resolve({
				rows: rows.slice(start, start + query.pageSize),
				total,
				pageNum: query.pageNum,
				pageSize: query.pageSize,
				pages: Math.max(1, Math.ceil(total / query.pageSize)),
			});
		}, MOCK_LATENCY);
	});
}

export function findMockProject(id: string): MoldProjectRow | undefined {
	return MOCK_PROJECTS.find((r) => r.id === id);
}
