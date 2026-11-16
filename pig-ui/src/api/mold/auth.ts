import { fetchCapabilities, moldClient, MoldApiError, toApiError, unwrap, type MoldCapabilities } from '/@/api/mold/projects';
import { clearMustChangeFlag, setMustChangeFlag } from '/@/api/mold/authEvents';

/**
 * 模具认证接口（G1 / G-04）
 *
 * GET  /admin/auth/me       当前账号 + capabilities + dataScopes + mustChangePassword
 * POST /admin/auth/password 自助改密（旧口令 + 新口令），成功后 mustChangePassword=false
 */

export type MoldMe = MoldCapabilities;

/** 拉当前账号信息，并同步本标签页的强制改密标记 */
export async function fetchMe(): Promise<MoldMe> {
	const me = await fetchCapabilities();
	setMustChangeFlag(me.mustChangePassword);
	return me;
}

export async function changeOwnPassword(oldPassword: string, newPassword: string): Promise<void> {
	try {
		await moldClient.post('/admin/auth/password', { oldPassword, newPassword });
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	// 服务端成功后必须重新核对 /auth/me，不能仅凭前端清标记
	clearMustChangeFlag();
}

/** 账号目录（仅 OWNER；其他角色会得到 403） */
export async function fetchUserDirectory(): Promise<any> {
	let res: any;
	try {
		res = await moldClient.get('/admin/mold/users', { params: { pageNum: 1, pageSize: 100 } });
	} catch (err) {
		throw err instanceof MoldApiError ? err : toApiError(err);
	}
	return unwrap(res);
}
