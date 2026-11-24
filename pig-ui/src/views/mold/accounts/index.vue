<template>
	<div class="layout-padding">
		<div class="layout-padding-auto layout-padding-view">
			<div ref="scrollRef" class="page-scroll">
				<div class="mold-page">
					<div class="mold-page-head">
						<div>
							<h1>账号管理</h1>
							<p>
								给员工开账号、分配角色、重置密码。新账号或重置密码后，本人首次登录要先改密码；临时密码只显示一次，系统不保存明文。
								<span v-if="state !== 'forbidden' && state !== 'error'" class="mold-page-total">
									共 {{ state === 'loading' ? '—' : total }} 个账号
								</span>
							</p>
						</div>
						<div class="mold-page-head-actions">
							<el-button class="mold-mobile-filter-btn" :icon="Search" @click="filterOpen = !filterOpen">
								{{ filterOpen ? '收起筛选' : '筛选' }}
							</el-button>
							<el-button circle :icon="Refresh" aria-label="刷新账号列表" :loading="loading" @click="reload" />
						</div>
					</div>

					<div class="mold-toolbar">
						<div class="mold-toolbar-left">
							<el-button v-if="canCreate" type="primary" :icon="Plus" data-testid="account-create" @click="openCreate">
								新建账号
							</el-button>
						</div>
						<div class="mold-toolbar-right mold-inline-note">
							不能停用或移除自己的 OWNER 角色；系统始终保留至少一个启用中的 OWNER。
						</div>
					</div>

					<div class="mold-filter-bar" :class="{ 'is-open': filterOpen }">
						<el-form :inline="true" @submit.prevent="onQuery">
							<el-form-item label="关键字">
								<el-input v-model="filters.keyword" placeholder="登录名 / 显示名" clearable style="width: 190px" />
							</el-form-item>
							<el-form-item label="状态">
								<el-select v-model="filters.status" placeholder="全部" clearable style="width: 120px" @change="onQuery">
									<el-option label="启用" value="ACTIVE" />
									<el-option label="停用" value="DISABLED" />
								</el-select>
							</el-form-item>
							<el-form-item>
								<el-button type="primary" :icon="Search" @click="onQuery">查询</el-button>
								<el-button :icon="RefreshLeft" @click="onReset">重置</el-button>
							</el-form-item>
						</el-form>
					</div>

					<StatePanel
						v-if="state === 'forbidden'"
						kind="forbidden"
						title="无账号管理权限"
						description="账号管理只有老板可以使用。"
						action-text="返回首页"
						@action="goHome"
					/>
					<div v-else-if="state === 'loading'" class="mold-skeleton" aria-busy="true" aria-label="正在加载账号">
						<div v-for="i in 5" :key="i" class="mold-skel-row">
							<div class="mold-skel-block" style="height: 16px; width: 28%" />
							<div class="mold-skel-block" style="height: 16px; width: 22%" />
							<div class="mold-skel-block" style="height: 16px; width: 18%" />
						</div>
					</div>
					<StatePanel
						v-else-if="state === 'error'"
						kind="error"
						:title="errorInfo.title"
						:description="errorInfo.message"
						:request-id="errorInfo.requestId"
						:action-text="errorAction === 'login' ? '重新登录' : '重试'"
						@action="onErrorAction"
					/>
					<StatePanel
						v-else-if="state === 'empty'"
						kind="empty"
						:title="hasFilters ? '没有符合条件的账号' : '还没有账号'"
						:description="hasFilters ? '当前筛选条件下没有账号，可重置后重试。' : '新建账号并分配角色后，成员才能登录。'"
						:action-text="hasFilters ? '重置筛选' : canCreate ? '新建账号' : ''"
						@action="hasFilters ? onReset() : openCreate()"
					/>

					<div v-else class="mold-list">
						<el-table
							v-loading="loading"
							:data="rows"
							border
							stripe
							row-key="id"
							style="width: 100%"
							:max-height="tableMaxHeight"
							:aria-busy="loading ? 'true' : 'false'"
							@sort-change="onSortChange"
						>
							<el-table-column prop="loginName" label="登录名" min-width="140" sortable="custom" class-name="col-code">
								<template #default="{ row }">
									<span class="cell-label">登录名</span>
									<div class="cell-value">{{ row.loginName }}</div>
								</template>
							</el-table-column>
							<el-table-column prop="displayName" label="显示名" min-width="140" sortable="custom">
								<template #default="{ row }">
									<span class="cell-label">显示名</span>
									<div class="cell-value">{{ row.displayName }}</div>
								</template>
							</el-table-column>
							<el-table-column label="角色" min-width="180">
								<template #default="{ row }">
									<span class="cell-label">角色</span>
									<div class="cell-value mold-role-tags">
										<el-tag v-for="role in row.roles || []" :key="role" size="small" effect="plain" :type="roleTone(role)">
											{{ roleLabel(role) }}
										</el-tag>
										<span v-if="!row.roles?.length" class="mold-inline-note">未分配</span>
									</div>
								</template>
							</el-table-column>
							<el-table-column label="电话" width="140">
								<template #default="{ row }">
									<span class="cell-label">电话</span>
									<div class="cell-value">{{ row.phone || '—' }}</div>
								</template>
							</el-table-column>
							<el-table-column prop="status" label="状态" width="100" align="center" sortable="custom">
								<template #default="{ row }">
									<span class="cell-label">状态</span>
									<div class="cell-value"><StatusTag kind="user" :value="row.status" /></div>
								</template>
							</el-table-column>
							<el-table-column label="操作" width="130" align="right" class-name="col-act">
								<template #default="{ row }">
									<el-dropdown trigger="click" @command="(cmd: string) => onRowCommand(cmd, row)">
										<el-button link type="primary" size="small">操作<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
										<template #dropdown>
											<el-dropdown-menu>
												<el-dropdown-item v-if="canUpdate" command="edit">编辑资料</el-dropdown-item>
												<el-dropdown-item v-if="canRole" command="roles">分配角色</el-dropdown-item>
												<el-dropdown-item v-if="canReset && row.id !== meId" command="password">重置密码</el-dropdown-item>
												<el-dropdown-item v-if="canUpdate && row.id === meId" disabled>不能修改自己的状态和角色</el-dropdown-item>
											</el-dropdown-menu>
										</template>
									</el-dropdown>
								</template>
							</el-table-column>
						</el-table>
					</div>

					<div v-if="state === 'ready'" class="mold-pager">
						<div class="mold-pager-total">共 {{ total }} 个账号</div>
						<el-pagination
							class="mold-pager-desktop"
							:current-page="pageNum"
							:page-size="pageSize"
							:total="total"
							layout="prev, pager, next, jumper"
							background
							@current-change="goPage"
						/>
						<div class="mold-pager-mobile">
							<el-button :icon="ArrowLeft" :disabled="pageNum <= 1" @click="goPage(pageNum - 1)">上一页</el-button>
							<span class="mold-pager-info">第 {{ pageNum }} / {{ pageCount }} 页 · 共 {{ total }} 条</span>
							<el-button :disabled="pageNum >= pageCount" @click="goPage(pageNum + 1)">
								下一页<el-icon class="el-icon--right"><ArrowRight /></el-icon>
							</el-button>
						</div>
					</div>
				</div>
			</div>
		</div>

		<!-- 新建 -->
		<el-dialog v-model="createVisible" title="新建账号" :width="dialogWidth" append-to-body :close-on-click-modal="false" class="mold-dialog">
			<el-alert v-if="createError" type="error" :closable="false" show-icon :title="createError" class="mold-dialog-alert" />
			<el-form ref="createRef" :model="createForm" :rules="createRules" label-position="top" @submit.prevent="submitCreate">
				<div class="mold-form-grid">
					<el-form-item label="登录名" prop="loginName" :error="createFieldErrors.loginName">
						<el-input v-model="createForm.loginName" maxlength="64" placeholder="3-64 位，可用字母、数字和 . _ -" />
					</el-form-item>
					<el-form-item label="显示名" prop="displayName" :error="createFieldErrors.displayName">
						<el-input v-model="createForm.displayName" maxlength="128" />
					</el-form-item>
					<el-form-item label="手机号（可选）" :error="createFieldErrors.phone">
						<el-input v-model="createForm.phone" maxlength="20" />
					</el-form-item>
					<el-form-item label="初始密码" prop="password" :error="createFieldErrors.password">
						<div class="mold-password-row">
							<el-input v-model="createForm.password" type="password" show-password maxlength="128" placeholder="至少 12 位" />
							<el-button size="small" @click="generatePassword">生成</el-button>
						</div>
					</el-form-item>
				</div>
				<el-form-item label="角色（至少一个）">
					<div class="mold-role-picker">
						<el-checkbox v-for="r in MOLD_ROLES" :key="r.code" v-model="createForm.roles" :value="r.code" :label="r.code">
							{{ r.label }}
						</el-checkbox>
					</div>
					<p class="mold-form-hint">新账号首次登录时，系统会要求本人先修改密码。</p>
				</el-form-item>
				<div class="mold-dialog-actions">
					<el-button @click="createVisible = false">取消</el-button>
					<el-button type="primary" native-type="submit" :loading="acting">创建账号</el-button>
				</div>
			</el-form>
		</el-dialog>

		<!-- 编辑 -->
		<el-dialog v-model="editVisible" :title="`编辑账号 ${editTarget?.loginName || ''}`" :width="dialogWidth" append-to-body :close-on-click-modal="false" class="mold-dialog">
			<el-alert v-if="editError" type="error" :closable="false" show-icon :title="editError" class="mold-dialog-alert" />
			<el-form label-position="top" @submit.prevent="submitEdit">
				<div class="mold-form-grid">
					<el-form-item label="显示名">
						<el-input v-model="editForm.displayName" maxlength="128" />
					</el-form-item>
					<el-form-item label="手机号">
						<el-input v-model="editForm.phone" maxlength="20" />
					</el-form-item>
					<el-form-item label="状态">
						<el-select v-model="editForm.status" style="width: 100%" :disabled="editTarget?.id === meId">
							<el-option label="启用" value="ACTIVE" />
							<el-option label="停用" value="DISABLED" />
						</el-select>
					</el-form-item>
				</div>
				<div class="mold-dialog-actions">
					<el-button @click="editVisible = false">取消</el-button>
					<el-button type="primary" native-type="submit" :loading="acting" :disabled="editNoChanges">
						{{ editNoChanges ? '未修改' : '保存' }}
					</el-button>
				</div>
			</el-form>
		</el-dialog>

		<!-- 角色 -->
		<el-dialog v-model="rolesVisible" :title="`分配角色 ${rolesTarget?.loginName || ''}`" :width="dialogWidth" append-to-body :close-on-click-modal="false" class="mold-dialog">
			<el-alert v-if="rolesError" type="error" :closable="false" show-icon :title="rolesError" class="mold-dialog-alert" />
			<div class="mold-role-picker">
				<el-checkbox v-for="r in MOLD_ROLES" :key="r.code" v-model="rolesForm" :value="r.code" :label="r.code">
					<span class="mold-role-option">
						<span class="mold-role-option-name">{{ r.label }}</span>
						<span class="mold-role-option-desc">{{ r.desc }}</span>
					</span>
				</el-checkbox>
			</div>
			<div class="mold-dialog-actions">
				<el-button @click="rolesVisible = false">取消</el-button>
				<el-button type="primary" :loading="acting" :disabled="rolesNoChanges || !rolesForm.length" @click="submitRoles">
					{{ rolesNoChanges ? '未修改' : '保存角色' }}
				</el-button>
			</div>
		</el-dialog>

		<!-- 重置密码 -->
		<el-dialog v-model="passwordVisible" :title="`重置密码 ${passwordTarget?.loginName || ''}`" :width="dialogWidth" append-to-body :close-on-click-modal="false" class="mold-dialog">
			<el-alert v-if="passwordError" type="error" :closable="false" show-icon :title="passwordError" class="mold-dialog-alert" />
			<template v-if="temporaryPassword">
				<el-alert type="success" :closable="false" show-icon title="密码已重置" description="临时密码只显示这一次，关闭后无法再查看；请当面或通过安全渠道交给本人。" />
				<div class="mold-temp-password">
					<code>{{ temporaryPassword }}</code>
					<el-button size="small" :icon="CopyDocument" @click="copyPassword">复制</el-button>
				</div>
				<p class="mold-form-hint">对方下次登录时会被要求先修改密码。</p>
				<div class="mold-dialog-actions">
					<el-button type="primary" @click="closePassword">我已记录，关闭</el-button>
				</div>
			</template>
			<template v-else>
				<el-form label-position="top" @submit.prevent="submitPassword">
					<el-form-item label="方式">
						<el-radio-group v-model="passwordMode">
							<el-radio-button value="generate">生成临时密码</el-radio-button>
							<el-radio-button value="custom">自己设定密码</el-radio-button>
						</el-radio-group>
					</el-form-item>
					<el-form-item v-if="passwordMode === 'custom'" label="新密码">
						<el-input v-model="customPassword" type="password" show-password maxlength="128" placeholder="至少 12 位，不能和登录名、显示名相同" />
					</el-form-item>
					<div class="mold-dialog-actions">
						<el-button @click="passwordVisible = false">取消</el-button>
						<el-button type="primary" native-type="submit" :loading="acting" :disabled="passwordMode === 'custom' && customPassword.length < 12">
							确认重置
						</el-button>
					</div>
				</el-form>
			</template>
		</el-dialog>
	</div>
</template>

<script setup lang="ts" name="moldAccounts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { ArrowDown, ArrowLeft, ArrowRight, CopyDocument, Plus, Refresh, RefreshLeft, Search } from '@element-plus/icons-vue';
import StatePanel from '/@/components/mold/StatePanel.vue';
import StatusTag from '/@/components/mold/StatusTag.vue';
import { useMoldPermissions } from '/@/api/mold/permissions';
import { MoldApiError } from '/@/api/mold/projects';
import {
	createUser,
	fetchUserPage,
	MOLD_ROLES,
	resetUserPassword,
	setUserRoles,
	updateUser,
	type MoldRoleCode,
	type MoldUserAccount,
} from '/@/api/mold/users';
import { describeMoldError } from '/@/api/mold/errors';
import { useMoldList } from '/@/utils/moldList';
import { beginIdempotentAction, finishIdempotentAction, stableHash } from '/@/utils/idempotency';

const router = useRouter();
const { can, ensureLoaded, meId, unreachable } = useMoldPermissions();

const canCreate = computed(() => can('mold:user:create'));
const canUpdate = computed(() => can('mold:user:update'));
const canRole = computed(() => can('mold:user:role'));
const canReset = computed(() => can('mold:user:password-reset'));

const filterOpen = ref(false);
const dialogWidth = computed(() => (window.innerWidth <= 900 ? '94%' : '560px'));
const tableMaxHeight = computed(() => (window.innerWidth <= 900 ? undefined : 'calc(100vh - 340px)'));

const filters = reactive({ keyword: '', status: '' });
const list = useMoldList<MoldUserAccount>({
	fetch: (query) => fetchUserPage(query as any),
	filters: () => ({ keyword: filters.keyword || undefined, status: filters.status || undefined }),
	sortable: ['loginName', 'displayName', 'status'],
	defaultSort: { key: 'loginName', order: 'asc' },
});
const { rows, total, pageNum, pageSize, pageCount, state, loading, errorInfo, errorAction, hasFilters } = list;

const roleLabel = (code: string) => MOLD_ROLES.find((r) => r.code === code)?.label || code;
const roleTone = (code: string) =>
	({ OWNER: 'danger', PM: 'primary', FINANCE: 'success', VIEWER: 'info' } as Record<string, any>)[code] || 'warning';

const acting = ref(false);

/* ------------------------------ 列表 ------------------------------ */
function onQuery() {
	list.query();
}
function onReset() {
	filters.keyword = '';
	filters.status = '';
	list.reset();
}
function onErrorAction() {
	if (errorAction.value === 'login') router.push('/login');
	else list.reload();
}
function goPage(next: number) {
	list.goPage(next);
}
function onSortChange(payload: { prop: string; order: string | null }) {
	list.onSortChange(payload);
}
function reload() {
	list.reload();
}
function goHome() {
	router.push('/home');
}

/* ------------------------------ 新建 ------------------------------ */
const createVisible = ref(false);
const createRef = ref<any>();
const createError = ref('');
const createFieldErrors = reactive<Record<string, string>>({});
const createForm = reactive({ loginName: '', displayName: '', phone: '', password: '', roles: ['VIEWER'] as string[] });
const createRules = {
	loginName: [{ required: true, message: '请填写登录名', trigger: 'blur' }],
	displayName: [{ required: true, message: '请填写显示名', trigger: 'blur' }],
	password: [{ required: true, message: '请填写初始密码', trigger: 'blur' }],
};

function openCreate() {
	createForm.loginName = '';
	createForm.displayName = '';
	createForm.phone = '';
	createForm.password = '';
	createForm.roles = ['VIEWER'];
	createError.value = '';
	Object.keys(createFieldErrors).forEach((k) => delete createFieldErrors[k]);
	createVisible.value = true;
}

function generatePassword() {
	const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789';
	const bytes = new Uint32Array(16);
	if (typeof crypto !== 'undefined' && crypto.getRandomValues) crypto.getRandomValues(bytes);
	else for (let i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 0xffffffff);
	createForm.password = Array.from(bytes, (b) => chars[b % chars.length]).join('');
}

async function submitCreate() {
	createError.value = '';
	Object.keys(createFieldErrors).forEach((k) => delete createFieldErrors[k]);
	try {
		await createRef.value?.validate();
	} catch {
		return;
	}
	if (!createForm.roles.length) {
		createError.value = '至少分配一个角色';
		return;
	}
	acting.value = true;
	const userBody = {
		loginName: createForm.loginName.trim(),
		displayName: createForm.displayName.trim(),
		phone: createForm.phone.trim() || null,
		password: createForm.password,
		roles: createForm.roles as MoldRoleCode[],
	};
	const scope = `user.create:${stableHash(JSON.stringify(userBody))}`;
	try {
		await createUser(userBody, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success('账号已创建，本人首次登录时需要先修改密码');
		createVisible.value = false;
		list.reload();
	} catch (err: any) {
		if (err instanceof MoldApiError) Object.assign(createFieldErrors, err.fieldErrors || {});
		createError.value = describeMoldError(err, '创建失败').message;
	} finally {
		acting.value = false;
	}
}

/* ------------------------------ 编辑 ------------------------------ */
const editVisible = ref(false);
const editError = ref('');
const editTarget = ref<MoldUserAccount | null>(null);
const editForm = reactive({ displayName: '', phone: '', status: 'ACTIVE' as 'ACTIVE' | 'DISABLED' });
const editNoChanges = computed(() => {
	if (!editTarget.value) return true;
	const t = editTarget.value;
	return (t.displayName || '') === editForm.displayName && (t.phone || '') === editForm.phone && t.status === editForm.status;
});

function openEdit(row: MoldUserAccount) {
	editTarget.value = row;
	editForm.displayName = row.displayName || '';
	editForm.phone = row.phone || '';
	editForm.status = row.status === 'DISABLED' ? 'DISABLED' : 'ACTIVE';
	editError.value = '';
	editVisible.value = true;
}

async function submitEdit() {
	if (!editTarget.value || editNoChanges.value) return;
	acting.value = true;
	editError.value = '';
	try {
		await updateUser(
			editTarget.value.id,
			{
				displayName: editForm.displayName.trim(),
				phone: editForm.phone.trim() || null,
				status: editForm.status,
			},
			editTarget.value.version
		);
		ElMessage.success('账号已保存');
		editVisible.value = false;
		list.reload();
	} catch (err: any) {
		editError.value = describeMoldError(err, '保存失败').message;
	} finally {
		acting.value = false;
	}
}

/* ------------------------------ 角色 ------------------------------ */
const rolesVisible = ref(false);
const rolesError = ref('');
const rolesTarget = ref<MoldUserAccount | null>(null);
const rolesForm = ref<string[]>([]);
const rolesNoChanges = computed(() => {
	if (!rolesTarget.value) return true;
	const before = [...(rolesTarget.value.roles || [])].sort().join(',');
	const after = [...rolesForm.value].sort().join(',');
	return before === after;
});

function openRoles(row: MoldUserAccount) {
	rolesTarget.value = row;
	rolesForm.value = [...(row.roles || [])];
	rolesError.value = '';
	rolesVisible.value = true;
}

async function submitRoles() {
	if (!rolesTarget.value || rolesNoChanges.value || !rolesForm.value.length) return;
	acting.value = true;
	rolesError.value = '';
	try {
		await setUserRoles(rolesTarget.value.id, rolesForm.value as MoldRoleCode[], rolesTarget.value.version);
		ElMessage.success('角色已更新');
		rolesVisible.value = false;
		list.reload();
	} catch (err: any) {
		rolesError.value = describeMoldError(err, '角色保存失败').message;
	} finally {
		acting.value = false;
	}
}

/* ------------------------------ 重置密码 ------------------------------ */
const passwordVisible = ref(false);
const passwordError = ref('');
const passwordTarget = ref<MoldUserAccount | null>(null);
const passwordMode = ref<'generate' | 'custom'>('generate');
const customPassword = ref('');
const temporaryPassword = ref('');

function openPassword(row: MoldUserAccount) {
	passwordTarget.value = row;
	passwordMode.value = 'generate';
	customPassword.value = '';
	temporaryPassword.value = '';
	passwordError.value = '';
	passwordVisible.value = true;
}

async function submitPassword() {
	if (!passwordTarget.value) return;
	acting.value = true;
	passwordError.value = '';
	try {
		const res = await resetUserPassword(
			passwordTarget.value.id,
			passwordMode.value === 'generate' ? { generate: true } : { password: customPassword.value }
		);
		temporaryPassword.value = res.temporaryPassword || customPassword.value;
		ElMessage.success('密码已重置');
		list.reload();
	} catch (err: any) {
		passwordError.value = describeMoldError(err, '重置失败').message;
	} finally {
		acting.value = false;
	}
}

async function copyPassword() {
	try {
		await navigator.clipboard.writeText(temporaryPassword.value);
		ElMessage.success('已复制');
	} catch {
		ElMessage.warning('复制失败，请手动选择密码');
	}
}

function closePassword() {
	temporaryPassword.value = '';
	passwordVisible.value = false;
}

/* ------------------------------ 行命令 ------------------------------ */
function onRowCommand(command: string, row: MoldUserAccount) {
	if (command === 'edit') openEdit(row);
	else if (command === 'roles') openRoles(row);
	else if (command === 'password') openPassword(row);
}

/**
 * 没有查看权限时直接显示无权限面板，不再请求接口，避免控制台和网络面板留下 403。
 * 权限接口因网络故障拿不到时不当作「无权限」，仍照常请求，由服务端裁决。
 */
const noViewAccess = computed(() => !unreachable.value && !can('mold:user:list'));

onMounted(async () => {
	await ensureLoaded();
	if (noViewAccess.value) {
		state.value = 'forbidden';
		return;
	}
	list.load(true);
});
</script>

<style scoped lang="scss">
.mold-toolbar-left {
	display: flex;
	flex-wrap: wrap;
	gap: 8px;
}
.mold-role-tags {
	display: flex;
	flex-wrap: wrap;
	gap: 4px;
}
.mold-form-grid {
	display: grid;
	grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
	gap: 0 16px;
}
.mold-password-row {
	display: flex;
	gap: 8px;
	width: 100%;
}
.mold-role-picker {
	display: flex;
	flex-direction: column;
	gap: 6px;
}
.mold-role-option {
	display: flex;
	flex-direction: column;
	line-height: 18px;
}
.mold-role-option-name {
	font-weight: 500;
}
.mold-role-option-desc {
	font-size: 11px;
	color: var(--el-text-color-secondary);
}
.mold-temp-password {
	display: flex;
	align-items: center;
	gap: 10px;
	margin: 12px 0 4px;
	padding: 10px 12px;
	border: 1px dashed var(--el-color-primary);
	border-radius: 6px;
	background: var(--el-color-primary-light-9);
	code {
		flex: 1;
		min-width: 0;
		font-size: 15px;
		overflow-wrap: anywhere;
	}
}
</style>
