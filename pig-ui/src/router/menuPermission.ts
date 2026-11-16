import { useMoldPermissions } from '/@/api/mold/permissions';

/**
 * 导航按功能权限过滤（体验层，不是安全控制）
 *
 * 背景：pig-ui 的顶栏/横向菜单、命令面板、菜单搜索都直接渲染路由表，
 * 从不判断权限；页面自身只会在请求 403 后显示「无权限」状态卡。
 * 结果是编程、只读角色在导航里能看到「账号管理」「审计日志」「更正记录」
 * 这类他们进不去的入口，点进去才撞 403。
 *
 * 规则：
 * - `meta.permissions` 为空 = 不限制（首页、我的工作等所有登录角色都能进）。
 * - 有值时任一命中即可见（OR 语义，便于一个页面接受多种能力）。
 * - 权限尚未加载、加载失败或运行在 legacy 兜底模式时**一律放行**：
 *   宁可多显示一个进不去的入口，也不能因为一次网络抖动把整个菜单清空。
 *   服务端仍逐请求鉴权，前端隐藏只影响观感。
 */

/** 当前权限状态是否足以做严格过滤 */
function strictMode(): boolean {
	return useMoldPermissions().source.value === 'capabilities';
}

/** 路由/命令面板条目是否对当前账号可见 */
export function canAccessRoute(meta: { permissions?: string[] } | null | undefined): boolean {
	const list = meta?.permissions;
	if (!Array.isArray(list) || list.length === 0) return true;
	if (!strictMode()) return true;
	const { can } = useMoldPermissions();
	return list.some((capability) => can(capability));
}

/**
 * 递归过滤菜单路由：只影响导航展示，不改 baseRoutes，路由本身仍然注册，
 * 因此直连 URL 依旧可打开（由页面自身 403 兜底）。
 */
export function filterMenuByPermission<T extends { meta?: any; children?: T[] }>(routes: T[]): T[] {
	return routes
		.filter((route) => canAccessRoute(route?.meta))
		.map((route) => (route.children?.length ? { ...route, children: filterMenuByPermission(route.children) } : route));
}

/**
 * 启动时先取 capabilities 再建菜单；取不到时静默降级为放行。
 * 这里不抛错：登录与路由初始化不能因为权限接口抖动而失败。
 */
export async function ensurePermissionsLoaded(): Promise<void> {
	try {
		await useMoldPermissions().ensureLoaded();
	} catch {
		// 忽略：source 会停留在 legacy，canAccessRoute 走放行分支
	}
}
