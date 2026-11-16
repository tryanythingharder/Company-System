import { getPage, postJson, patchJson, putJson, ifMatch, type MoldPage } from '/@/api/mold/paging';
import { newIdempotencyKey } from '/@/api/mold/projects';

/**
 * 账号与角色（契约 6.4，7 操作）
 *   GET/POST /users、PATCH /users/{id}、PUT /users/{id}/roles、POST /users/{id}/password
 *   GET /directory/users、GET /system/role-capabilities
 */

export type MoldRoleCode = 'OWNER' | 'PM' | 'PROGRAMMER' | 'MACHINIST' | 'FINANCE' | 'VIEWER';

export const MOLD_ROLES: { code: MoldRoleCode; label: string; desc: string }[] = [
	{ code: 'OWNER', label: '老板 / 管理员', desc: '全部页面与全部写操作，含账号管理' },
	{ code: 'PM', label: '项目负责人', desc: '项目、阶段、异常；采购/财务只读' },
	{ code: 'PROGRAMMER', label: '编程', desc: '公司进度、我的工作、阶段历史' },
	{ code: 'MACHINIST', label: '操机', desc: '公司进度、我的工作、阶段历史' },
	{ code: 'FINANCE', label: '财务', desc: '采购台账、财务收支、更正记录' },
	{ code: 'VIEWER', label: '只读', desc: '公司进度、阶段历史（只读）' },
];

export interface MoldUserAccount {
	id: string;
	loginName: string;
	displayName: string;
	phone?: string | null;
	roles: MoldRoleCode[] | string[];
	status: 'ACTIVE' | 'DISABLED';
	version: number;
	createdAt?: string;
	updatedAt?: string;
	lastLoginAt?: string | null;
}

export const USER_SORTABLE = ['loginName', 'displayName', 'status'] as const;

export function fetchUserPage(query: {
	pageNum: number;
	pageSize: number;
	status?: string;
	keyword?: string;
	sort?: string;
	order?: 'asc' | 'desc';
}): Promise<MoldPage<MoldUserAccount>> {
	return getPage<MoldUserAccount>('/admin/mold/users', query);
}

export function createUser(
	body: { loginName: string; displayName: string; phone?: string | null; password: string; roles: MoldRoleCode[] },
	idempotencyKey?: string
): Promise<MoldUserAccount> {
	return postJson<MoldUserAccount>('/admin/mold/users', body, {
		'Idempotency-Key': idempotencyKey || newIdempotencyKey(),
	});
}

export function updateUser(
	id: string,
	body: { displayName?: string; status?: 'ACTIVE' | 'DISABLED'; phone?: string | null },
	version: number
): Promise<MoldUserAccount> {
	return patchJson<MoldUserAccount>(`/admin/mold/users/${encodeURIComponent(id)}`, body, ifMatch(version));
}

export function setUserRoles(id: string, roles: MoldRoleCode[], version: number): Promise<MoldUserAccount> {
	return putJson<MoldUserAccount>(`/admin/mold/users/${encodeURIComponent(id)}/roles`, { roles }, ifMatch(version));
}

export function resetUserPassword(
	id: string,
	body: { generate?: boolean; password?: string }
): Promise<{ userId: string; mustChangePassword: boolean; temporaryPassword?: string }> {
	return postJson(`/admin/mold/users/${encodeURIComponent(id)}/password`, body);
}

export function fetchDirectory(keyword?: string): Promise<{ rows: MoldUserAccount[]; total: number }> {
	return getPage<MoldUserAccount>('/admin/mold/directory/users', { keyword, pageNum: 1, pageSize: 100 }).then((p) => ({
		rows: p.rows,
		total: p.total,
	}));
}
