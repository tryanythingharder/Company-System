import { computed, ref } from 'vue';
import { fetchCapabilities } from '/@/api/mold/projects';
import { useUserInfo } from '/@/stores/userInfo';

/**
 * 模具模块权限桥接（集中开关）
 *
 * 07 的 G1 `/admin/auth/me` 已返回 capabilities（mold:<资源>:<动作>）与 dataScopes。
 * 本文件是唯一读取权限的地方，页面只调用 can()/canWrite()/isOwner()，
 * 不要在页面里散写角色判断。前端隐藏只是体验层，服务端仍逐请求鉴权。
 *
 * 切换方式：把 MOLD_PERMISSION_MODE 改成 'legacy' 即完全退回
 * `authBtnList + OWNER 角色 + admin` 兜底；'auto' 为 capabilities 优先、失败自动兜底。
 */
export const MOLD_PERMISSION_MODE: 'auto' | 'capabilities' | 'legacy' = 'auto';

type Source = 'capabilities' | 'legacy' | 'pending';

const state = ref({
	loaded: false,
	capabilities: [] as string[],
	roles: [] as string[],
	meId: '',
	meName: '',
	source: 'pending' as Source,
	error: '',
	/** true = 网络故障/超时导致没拿到权限，界面必须提示且不能当作「无权限」 */
	unreachable: false,
});

let pending: Promise<void> | null = null;

export function useMoldPermissions() {
	const userStore = useUserInfo();

	const legacyCaps = computed<string[]>(() => {
		const list = (userStore.userInfos as any)?.authBtnList;
		return Array.isArray(list) ? list : [];
	});
	const roleCodes = computed<string[]>(() => {
		if (state.value.roles.length) return state.value.roles;
		return (((userStore.userInfos as any)?.roles || []) as any[])
			.map((r) => (typeof r === 'string' ? r : r?.roleCode || r?.code || r?.roleName || ''))
			.filter(Boolean);
	});
	const isAdmin = computed(
		() =>
			legacyCaps.value.includes('*:*:*') ||
			roleCodes.value.includes('OWNER')
	);
	const isOwnerRole = computed(() => roleCodes.value.includes('OWNER'));

	const effectiveCaps = computed<string[]>(() => {
		if (MOLD_PERMISSION_MODE === 'legacy') return legacyCaps.value;
		if (state.value.source === 'capabilities') return state.value.capabilities;
		return legacyCaps.value;
	});

	const source = computed<Source>(() => (MOLD_PERMISSION_MODE === 'legacy' ? 'legacy' : state.value.source));
	const meId = computed(() => state.value.meId);
	const meName = computed(() => state.value.meName);
	const unreachable = computed(() => state.value.unreachable);
	const error = computed(() => state.value.error);
	/** 已核对到当前账号 id 才能做对象级判断；未核对前一律不放行写操作 */
	const identityReady = computed(() => !!state.value.meId);

	/** 只判断功能权限；对象级（是否项目负责人）用 canWrite */
	function can(capability: string): boolean {
		if (source.value === 'capabilities') return effectiveCaps.value.includes(capability);
		// legacy 兜底：OWNER/admin 视为全量
		return isAdmin.value || effectiveCaps.value.includes(capability);
	}

	/** 当前账号是否该项目负责人（meId 未知时返回 false，避免误放行） */
	function isOwner(project: { ownerUserId?: string } | null | undefined): boolean {
		if (!project?.ownerUserId || !state.value.meId) return false;
		return project.ownerUserId === state.value.meId;
	}

	/** 项目负责人本人，或老板（OWNER 可直接操作任意项目，2026-11 拍板） */
	function isManager(project: { ownerUserId?: string } | null | undefined): boolean {
		if (!project || !state.value.meId) return false;
		return isOwnerRole.value || isOwner(project);
	}

	/** 写项目/阶段 = 能力串 + 该项目负责人或老板（服务端最后仍会鉴权） */
	function canWrite(project: { ownerUserId?: string } | null | undefined, capability: string): boolean {
		return can(capability) && isManager(project);
	}

	/** 变更负责人：后端仅 OWNER，且对任意项目生效（不受 ownerUserId 限制） */
	function canTransferOwner(): boolean {
		return can('mold:project:owner') && isOwnerRole.value;
	}

	async function ensureLoaded(force = false): Promise<void> {
		if (MOLD_PERMISSION_MODE === 'legacy') return;
		if (state.value.loaded && !force) return;
		if (pending) return pending;
		pending = fetchCapabilities()
			.then((res) => {
				state.value = {
					loaded: true,
					capabilities: res.capabilities,
					roles: res.roles,
					meId: res.user?.id || '',
					meName: res.user?.displayName || '',
					source: 'capabilities',
					error: '',
					unreachable: false,
				};
			})
			.catch((err) => {
				// 网络故障不当作「无权限」：保留 legacy 兜底，并把 unreachable 交给页面提示
				state.value = {
					loaded: true,
					capabilities: [],
					roles: [],
					meId: '',
					meName: '',
					source: 'legacy',
					error: err?.message || 'capabilities 不可用',
					unreachable: err?.status === 0,
				};
			})
			.finally(() => {
				pending = null;
			});
		return pending;
	}

	function reset() {
		state.value = {
			loaded: false,
			capabilities: [],
			roles: [],
			meId: '',
			meName: '',
			source: 'pending',
			error: '',
			unreachable: false,
		};
		pending = null;
	}

	return {
		can,
		canWrite,
		canTransferOwner,
		isOwner,
		isManager,
		ensureLoaded,
		reset,
		source,
		meId,
		meName,
		isAdmin,
		isOwnerRole,
		identityReady,
		unreachable,
		error,
		capabilities: effectiveCaps,
	};
}
