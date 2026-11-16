<template>
	<div class="mold-actions" :class="`is-${layout}`">
		<template v-if="layout === 'buttons'">
			<el-button
				v-if="canEdit"
				type="primary"
				:size="size"
				:title="editHint"
				data-testid="project-edit"
				@click="openEdit($event)"
			>
				编辑
			</el-button>
			<el-button v-if="canComplete" :size="size" :title="completeHint" data-testid="project-complete" @click="openComplete($event)">
				确认完工
			</el-button>
			<el-dropdown ref="dropdownRef" trigger="click" @command="onCommand">
				<el-button :size="size" :title="writeHint" data-testid="project-more">
					更多<el-icon class="el-icon--right"><ArrowDown /></el-icon>
				</el-button>
				<template #dropdown>
					<el-dropdown-menu>
						<el-dropdown-item v-if="canStage" command="stage">添加阶段</el-dropdown-item>
						<el-dropdown-item v-if="canOwner" command="owner">变更负责人</el-dropdown-item>
						<el-dropdown-item v-if="!hasAnyWrite" disabled>无写操作权限</el-dropdown-item>
					</el-dropdown-menu>
				</template>
			</el-dropdown>
		</template>
		<template v-else>
			<el-button v-if="!compact && canEdit" link type="primary" :title="editHint" data-testid="project-edit" @click="openEdit($event)">编辑</el-button>
			<el-dropdown ref="dropdownRef" trigger="click" @command="onCommand">
				<el-button link type="primary" :title="writeHint" data-testid="project-more">
					{{ compact ? '操作' : '更多' }}<el-icon class="el-icon--right"><ArrowDown /></el-icon>
				</el-button>
				<template #dropdown>
					<el-dropdown-menu>
						<el-dropdown-item v-if="compact" command="detail">查看详情</el-dropdown-item>
						<el-dropdown-item v-if="compact && canEdit" command="edit">编辑项目</el-dropdown-item>
						<el-dropdown-item v-if="canComplete" command="complete">确认完工</el-dropdown-item>
						<el-dropdown-item v-if="canStage" command="stage">添加阶段</el-dropdown-item>
						<el-dropdown-item v-if="canOwner" command="owner">变更负责人</el-dropdown-item>
						<el-dropdown-item v-if="!hasAnyWrite" disabled>无写操作权限</el-dropdown-item>
					</el-dropdown-menu>
				</template>
			</el-dropdown>
		</template>

		<!-- 编辑项目：真实 PATCH，If-Match 版本号 -->
		<el-dialog
			v-model="editVisible"
			title="编辑项目"
			:width="dialogWidth"
			:close-on-click-modal="false"
			append-to-body
			class="mold-dialog"
			@opened="focusOnOpen('edit-name')"
			@closed="restoreFocus"
		>
			<el-alert
				v-if="conflict"
				class="mold-dialog-alert"
				type="error"
				:closable="false"
				show-icon
				title="数据已被他人修改"
				:description="`${conflict.message}（最新 v${conflict.current ?? '?'}），你的修改没有保存。请重新加载后再提交。`"
			>
				<template #default>
					<div class="mold-dialog-alert-body">
						<span>数据已被他人修改（最新 v{{ conflict.current ?? '?' }}），你的修改没有保存。</span>
						<el-button link type="primary" data-testid="conflict-reload" @click="reloadAfterConflict">重新加载</el-button>
					</div>
				</template>
			</el-alert>
			<el-alert
				v-if="missingTarget"
				class="mold-dialog-alert"
				type="warning"
				:closable="false"
				show-icon
				title="未找到对应数据"
				:description="missingTarget"
			/>
			<el-alert
				v-else-if="actionError"
				class="mold-dialog-alert"
				type="error"
				:closable="false"
				show-icon
				:title="actionError"
				data-testid="edit-error"
			/>
			<el-form
				ref="editFormRef"
				:model="editForm"
				:rules="editRules"
				label-position="top"
				@submit.prevent="submitEdit"
			>
				<el-form-item label="项目名称" prop="name" :error="fieldErrors.name">
					<el-input v-model="editForm.name" maxlength="200" show-word-limit data-testid="edit-name" />
				</el-form-item>
				<el-form-item label="计划交期" prop="dueDate" :error="fieldErrors.dueDate">
					<el-date-picker
						v-model="editForm.dueDate"
						type="date"
						value-format="YYYY-MM-DD"
						placeholder="选择日期"
						style="width: 100%"
						data-testid="edit-due"
					/>
				</el-form-item>
				<el-form-item label="备注" prop="note" :error="fieldErrors.note">
					<el-input
						v-model="editForm.note"
						type="textarea"
						:rows="3"
						maxlength="500"
						show-word-limit
						placeholder="项目备注（可留空）"
						data-testid="edit-note"
					/>
				</el-form-item>
				<p class="mold-form-hint">
					当前版本 v{{ project.version }}。保存时带 <code>If-Match: "{{ project.version }}"</code>；若他人已修改，服务端返回
					409，不会覆盖。
				</p>
				<div class="mold-dialog-actions">
					<el-button @click="editVisible = false">取消</el-button>
					<el-button type="primary" native-type="submit" :loading="submitting" data-testid="edit-submit">保存</el-button>
				</div>
			</el-form>
		</el-dialog>

		<!-- 确认完工：D1 三要素由服务端校验 -->
		<el-dialog
			v-model="completeVisible"
			title="确认项目完工"
			:width="dialogWidth"
			:close-on-click-modal="false"
			append-to-body
			class="mold-dialog"
			@opened="focusOnOpen('complete-note')"
			@closed="restoreFocus"
		>
			<el-alert
				v-if="missingTarget"
				class="mold-dialog-alert"
				type="warning"
				:closable="false"
				show-icon
				title="未找到对应数据"
				:description="missingTarget"
			/>
			<el-alert
				v-else-if="actionError"
				class="mold-dialog-alert"
				type="error"
				:closable="false"
				show-icon
				:title="actionError"
			/>
			<div class="mold-complete-rules">
				<p>完工前服务端会核对 D1 三要素，缺一项拒绝并说明缺哪项：</p>
				<ul>
					<li>至少一个有效阶段</li>
					<li>所有阶段已完成</li>
					<li>没有未解决异常</li>
				</ul>
			</div>
			<el-form ref="completeFormRef" :model="completeForm" :rules="completeRules" label-position="top" @submit.prevent="submitComplete">
				<el-form-item label="完工说明（必填）" prop="completionNote" :error="fieldErrors.completionNote">
					<el-input
						v-model="completeForm.completionNote"
						type="textarea"
						:rows="3"
						maxlength="500"
						show-word-limit
						placeholder="例如：试模合格，客户签收。"
						data-testid="complete-note"
					/>
				</el-form-item>
				<p class="mold-form-hint">当前状态 {{ statusText(project.status) }} · 已完工 {{ project.stageCounts.completed }}/{{ project.stageCounts.total }} 阶段 · 未解决异常 {{ project.openIssueCount }} 个</p>
				<div class="mold-dialog-actions">
					<el-button @click="completeVisible = false">取消</el-button>
					<el-button type="primary" native-type="submit" :loading="submitting" data-testid="complete-submit">确认完工</el-button>
				</div>
			</el-form>
		</el-dialog>

		<!-- 添加阶段：真实 POST /projects/{id}/stages，Idempotency-Key 必填 -->
		<el-dialog
			v-model="stageVisible"
			title="添加阶段"
			:width="dialogWidth"
			:close-on-click-modal="false"
			append-to-body
			class="mold-dialog"
			@opened="focusOnOpen('stage-name')"
			@closed="restoreFocus"
		>
			<el-alert
				v-if="missingTarget"
				class="mold-dialog-alert"
				type="warning"
				:closable="false"
				show-icon
				title="未找到对应数据"
				:description="missingTarget"
			/>
			<el-alert
				v-else
				class="mold-dialog-alert"
				type="info"
				:closable="false"
				show-icon
				title="阶段字段"
				description="名称、排序号、计划开始/结束、阶段说明；负责人字段需在阶段详情里由项目负责人或 OWNER 设置。"
			/>
			<el-form ref="stageFormRef" :model="stageForm" :rules="stageRules" label-position="top" @submit.prevent="submitStage">
				<el-form-item label="阶段名称" prop="name" :error="fieldErrors.name">
					<el-input v-model="stageForm.name" maxlength="100" placeholder="例如：编程 / 粗加工 / 精加工 / 试模" data-testid="stage-name" />
				</el-form-item>
				<el-form-item label="排序号" prop="sortOrder" :error="fieldErrors.sortOrder">
					<el-input-number v-model="stageForm.sortOrder" :min="1" :max="999" controls-position="right" style="width: 160px" />
				</el-form-item>
				<el-form-item label="计划开始 / 结束">
					<div class="mold-date-range">
						<el-date-picker v-model="stageForm.plannedStart" type="date" value-format="YYYY-MM-DD" placeholder="开始" />
						<el-date-picker v-model="stageForm.plannedEnd" type="date" value-format="YYYY-MM-DD" placeholder="结束" />
					</div>
				</el-form-item>
				<el-form-item label="阶段说明" prop="note" :error="fieldErrors.note">
					<el-input v-model="stageForm.note" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="可留空" />
				</el-form-item>
				<div class="mold-dialog-actions">
					<el-button @click="stageVisible = false">取消</el-button>
					<el-button type="primary" native-type="submit" :loading="submitting" data-testid="stage-submit">添加阶段</el-button>
				</div>
			</el-form>
		</el-dialog>

		<!-- 变更负责人 -->
		<el-dialog
			v-model="ownerVisible"
			title="变更项目负责人"
			:width="dialogWidth"
			:close-on-click-modal="false"
			append-to-body
			class="mold-dialog"
			@opened="focusOnOpen('owner-select')"
			@closed="restoreFocus"
		>
			<el-alert
				v-if="missingTarget"
				class="mold-dialog-alert"
				type="warning"
				:closable="false"
				show-icon
				title="未找到对应数据"
				:description="missingTarget"
			/>
			<el-alert
				v-else-if="userListError"
				class="mold-dialog-alert"
				type="error"
				:closable="false"
				show-icon
				title="账号目录不可用"
				:description="userListError"
			/>
			<el-form ref="ownerFormRef" :model="ownerForm" :rules="ownerRules" label-position="top" @submit.prevent="submitOwner">
				<el-form-item label="新负责人" prop="ownerUserId" :error="fieldErrors.ownerUserId">
					<el-select
						v-model="ownerForm.ownerUserId"
						filterable
						placeholder="选择账号"
						style="width: 100%"
						:loading="loadingUsers"
						data-testid="owner-select"
					>
						<el-option v-for="u in users" :key="u.id" :label="`${u.displayName}（${u.loginName}）`" :value="u.id">
							<span>{{ u.displayName }}</span>
							<span class="mold-option-meta">{{ u.loginName }} · {{ (u.roles || []).join('/') }}</span>
						</el-option>
					</el-select>
				</el-form-item>
				<el-form-item label="变更原因（必填）" prop="reason" :error="fieldErrors.reason">
					<el-input
						v-model="ownerForm.reason"
						type="textarea"
						:rows="2"
						maxlength="200"
						show-word-limit
						placeholder="例如：原负责人调岗，交接给新负责人"
						data-testid="owner-reason"
					/>
				</el-form-item>
				<p class="mold-form-hint">当前负责人：{{ project.owner?.displayName || '—' }}。变更负责人仅 OWNER 可操作（服务端校验），且必须填写原因。</p>
				<div class="mold-dialog-actions">
					<el-button @click="ownerVisible = false">取消</el-button>
					<el-button type="primary" native-type="submit" :loading="submitting" data-testid="owner-submit">保存</el-button>
				</div>
			</el-form>
		</el-dialog>
	</div>
</template>

<script setup lang="ts" name="moldProjectActions">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { ArrowDown } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import {
	addProjectStage,
	changeProjectOwner,
	completeProject,
	fetchUsers,
	MoldApiError,
	updateProject,
	type MoldProjectRow,
	type MoldUserRow,
} from '/@/api/mold/projects';
import { useMoldPermissions } from '/@/api/mold/permissions';
import { describeMoldError } from '/@/api/mold/errors';
import { beginIdempotentAction, finishIdempotentAction, stableHash } from '/@/utils/idempotency';

const props = withDefaults(
	defineProps<{
		project: MoldProjectRow;
		layout?: 'buttons' | 'dropdown';
		size?: 'small' | 'default' | 'large';
		/** 列表行空间有限：只保留「操作」下拉，编辑也放进菜单 */
		compact?: boolean;
	}>(),
	{ layout: 'dropdown', size: 'default', compact: false }
);

const emit = defineEmits<{
	(e: 'saved', row: MoldProjectRow): void;
	(e: 'reload'): void;
	(e: 'detail'): void;
}>();

const { can, canWrite, canTransferOwner, isOwner, identityReady, ensureLoaded } = useMoldPermissions();
onMounted(() => ensureLoaded());

const viewportWidth = ref(typeof window === 'undefined' ? 1440 : window.innerWidth);
const onResize = () => (viewportWidth.value = window.innerWidth);
onMounted(() => window.addEventListener('resize', onResize));
onUnmounted(() => window.removeEventListener('resize', onResize));
const dialogWidth = computed(() => (viewportWidth.value <= 900 ? '92%' : '480px'));

/* ------------------------------- 权限（对象级） ------------------------------- */
const dropdownRef = ref<any>();
const canEdit = computed(() => canWrite(props.project, 'mold:project:update'));
const canComplete = computed(
	() =>
		canWrite(props.project, 'mold:project:complete') &&
		(props.project.status === 'ACTIVE' || props.project.status === 'ON_HOLD')
);
const canStage = computed(() => can('mold:stage:create') && canWrite(props.project, 'mold:project:update'));
/** 变更负责人：后端仅 OWNER，且对任意项目生效（不要求当前负责人本人） */
const canOwner = computed(() => canTransferOwner());
const hasAnyWrite = computed(() => canEdit.value || canComplete.value || canStage.value || canOwner.value);

const writeHint = computed(() => {
	if (!identityReady.value) return '正在核对当前账号权限…';
	if (!isOwner(props.project)) return '仅项目负责人可操作（服务端会再次校验）';
	if (!hasAnyWrite.value) return '当前账号没有写操作权限';
	return '更多写操作';
});
const editHint = computed(() => (canEdit.value ? '编辑项目（带 If-Match）' : writeHint.value));
const completeHint = computed(() => (canComplete.value ? '确认项目完工' : writeHint.value));

function onCommand(cmd: string) {
	rememberTriggerFromDropdown();
	if (cmd === 'detail') emit('detail');
	else if (cmd === 'edit') openEdit();
	else if (cmd === 'complete') openComplete();
	else if (cmd === 'stage') openStage();
	else if (cmd === 'owner') openOwner();
}

/* ------------------------------- 通用状态 ------------------------------- */
const submitting = ref(false);
/** 404：目标项目/阶段已不存在或当前账号不可见（不再解释为「接口未交付」） */
const missingTarget = ref('');
const actionError = ref('');
const conflict = ref<{ current?: number; message: string } | null>(null);
const fieldErrors = reactive<Record<string, string>>({});
let triggerEl: HTMLElement | null = null;

function clearFeedback() {
	missingTarget.value = '';
	actionError.value = '';
	conflict.value = null;
	Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k]);
}

/** 直接点击按钮打开：记录该按钮，Esc/取消关闭后焦点回到它 */
function rememberTriggerFromEvent(e?: Event) {
	if (!e) return;
	const target = (e.currentTarget as HTMLElement) || null;
	if (target && target !== document.body) triggerEl = target;
}

/** 通过下拉菜单打开：Element Plus 菜单项不可聚焦，改记下拉触发按钮 */
function rememberTriggerFromDropdown() {
	const root = dropdownRef.value?.$el as HTMLElement | undefined;
	const btn = root?.querySelector('button') as HTMLElement | null;
	if (btn) triggerEl = btn;
}

/** 对话框关闭（含 Esc）：焦点回到触发按钮；元素已随刷新销毁时回到主内容区 */
function restoreFocus() {
	const target = triggerEl;
	triggerEl = null;
	nextTick(() => {
		if (target && target.isConnected) target.focus();
		else (document.getElementById('mold-main-content') as HTMLElement | null)?.focus();
	});
}

/** 对话框打开后把焦点放到第一个输入，Tab 由 el-dialog 的 focus-trap 限制在框内 */
function focusOnOpen(testId: string) {
	requestAnimationFrame(() => {
		const root = document.querySelector(`[data-testid="${testId}"]`) as HTMLElement | null;
		const target = (root?.querySelector('input, textarea') as HTMLElement | null) || root;
		target?.focus?.();
	});
}

const statusText = (s: string) =>
	({ ACTIVE: '进行中', ON_HOLD: '已暂停', COMPLETED: '已完工', CANCELLED: '已取消' } as Record<string, string>)[s] || s || '—';

function handleError(err: any, fallback: string) {
	if (!(err instanceof MoldApiError)) {
		actionError.value = err?.message || fallback;
		return;
	}
	Object.assign(fieldErrors, err.fieldErrors || {});
	if (err.status === 409 && err.code === 'VERSION_CONFLICT') {
		conflict.value = { current: err.currentVersion, message: err.message || '版本冲突' };
		actionError.value = '';
		return;
	}
	if (err.status === 404) {
		// 项目/阶段接口早已交付，404 只表示目标已不存在或当前账号看不到；
		// 用统一的中性文案，不再解释成「后端未实现」。
		const view = describeMoldError(err, '操作失败');
		missingTarget.value = `${view.message} 本次未产生任何修改。`;
		return;
	}
	// 403 不是登录失效：就地提示「无此操作权限；当前登录仍有效」，不清空已加载数据
	actionError.value = describeMoldError(err, fallback).message || fallback;
}

/* ------------------------------- 编辑 ------------------------------- */
const editVisible = ref(false);
const editFormRef = ref<any>();
const editForm = reactive({ name: '', dueDate: '' as string | null, note: '' });
const editRules = {
	name: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
};

function openEdit(e?: Event) {
	if (!canEdit.value) return;
	rememberTriggerFromEvent(e);
	clearFeedback();
	editForm.name = props.project.name;
	editForm.dueDate = props.project.dueDate || null;
	editForm.note = props.project.note || '';
	editVisible.value = true;
}

async function submitEdit() {
	if (conflict.value || missingTarget.value) return;
	const valid = await editFormRef.value?.validate().catch(() => false);
	if (!valid) return;
	submitting.value = true;
	clearFeedback();
	try {
		const payload = {
			name: editForm.name.trim(),
			dueDate: editForm.dueDate || null,
			note: editForm.note?.trim() || null,
		};
		// 同一用户动作 + 同一内容复用同一个 Idempotency-Key；成功后才清除
		const scope = `project.update:${props.project.id}:${props.project.version}:${stableHash(JSON.stringify(payload))}`;
		const updated = await updateProject(props.project.id, payload, props.project.version, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success('已保存，列表将刷新');
		editVisible.value = false;
		emit('saved', updated);
	} catch (err: any) {
		handleError(err, '保存失败');
		if (err instanceof MoldApiError && err.status === 409 && err.code !== 'VERSION_CONFLICT') {
			actionError.value = err.message || '项目状态已变化，无法编辑';
		}
	} finally {
		submitting.value = false;
	}
}

function reloadAfterConflict() {
	editVisible.value = false;
	emit('reload');
}

/* ------------------------------- 确认完工 ------------------------------- */
const completeVisible = ref(false);
const completeFormRef = ref<any>();
const completeForm = reactive({ completionNote: '' });
const completeRules = {
	completionNote: [{ required: true, message: '请填写完工说明', trigger: 'blur' }],
};

function openComplete(e?: Event) {
	if (!canComplete.value) return;
	rememberTriggerFromEvent(e);
	clearFeedback();
	completeForm.completionNote = '';
	completeVisible.value = true;
}

async function submitComplete() {
	if (missingTarget.value) return;
	const valid = await completeFormRef.value?.validate().catch(() => false);
	if (!valid) return;
	submitting.value = true;
	clearFeedback();
	try {
		const note = completeForm.completionNote.trim();
		const scope = `project.complete:${props.project.id}:${props.project.version}:${stableHash(note)}`;
		const updated = await completeProject(props.project.id, note, props.project.version, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success('已确认完工');
		completeVisible.value = false;
		emit('saved', updated);
	} catch (err: any) {
		handleError(err, '确认完工失败');
		if (err instanceof MoldApiError && err.status === 409 && err.code !== 'VERSION_CONFLICT') {
			actionError.value = buildStateMessage(err);
		}
	} finally {
		submitting.value = false;
	}
}

function buildStateMessage(err: MoldApiError): string {
	const details = err.details || {};
	if (details.missing === 'OPEN_STAGES' && Array.isArray(details.stages)) {
		const list = details.stages
			.map((s: any) => `${s.name}（${s.status}）`)
			.slice(0, 6)
			.join('、');
		return `服务端拒绝完工：仍有未完成阶段 ${list}${details.stages.length > 6 ? ' 等' : ''}。`;
	}
	if (details.missing === 'STAGES') return '服务端拒绝完工：至少需要一个有效阶段。';
	if (details.missing === 'OPEN_ISSUES' && Array.isArray(details.issues)) {
		return `服务端拒绝完工：仍有未解决异常 ${details.issues.map((i: any) => i.title).join('、')}。`;
	}
	return err.message || '项目状态不允许完工';
}

/* ------------------------------- 添加阶段 ------------------------------- */
const stageVisible = ref(false);
const stageFormRef = ref<any>();
const stageForm = reactive({ name: '', sortOrder: 1, plannedStart: '' as string | null, plannedEnd: '' as string | null, note: '' });
const stageRules = {
	name: [{ required: true, message: '请输入阶段名称', trigger: 'blur' }],
};

function openStage(e?: Event) {
	if (!canStage.value) return;
	rememberTriggerFromEvent(e);
	clearFeedback();
	stageForm.name = '';
	stageForm.sortOrder = (props.project.stageCounts?.total || 0) + 1;
	stageForm.plannedStart = null;
	stageForm.plannedEnd = null;
	stageForm.note = '';
	stageVisible.value = true;
}

async function submitStage() {
	if (missingTarget.value) return;
	const valid = await stageFormRef.value?.validate().catch(() => false);
	if (!valid) return;
	submitting.value = true;
	clearFeedback();
	try {
		const payload = {
			name: stageForm.name.trim(),
			sortOrder: stageForm.sortOrder,
			plannedStart: stageForm.plannedStart || null,
			plannedEnd: stageForm.plannedEnd || null,
			note: stageForm.note?.trim() || null,
		};
		const scope = `project.stage:${props.project.id}:${stableHash(JSON.stringify(payload))}`;
		await addProjectStage(props.project.id, payload, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success('阶段已添加');
		stageVisible.value = false;
		emit('reload');
	} catch (err: any) {
		handleError(err, '添加阶段失败');
	} finally {
		submitting.value = false;
	}
}

/* ------------------------------- 变更负责人 ------------------------------- */
const ownerVisible = ref(false);

/**
 * Esc 关闭对话框（G-13 / 可访问性）
 *
 * el-select 的键盘处理会对 Esc 调用 stopPropagation（即使下拉未展开），
 * 导致 el-dialog 自带的 close-on-press-escape 收不到事件。这里在捕获阶段
 * 统一处理，保证「Esc 关闭 + 焦点回到触发按钮」在含下拉的对话框里也成立。
 */
function onDialogEscape(e: KeyboardEvent) {
	if (e.key !== 'Escape') return;
	if (editVisible.value) editVisible.value = false;
	else if (completeVisible.value) completeVisible.value = false;
	else if (stageVisible.value) stageVisible.value = false;
	else if (ownerVisible.value) ownerVisible.value = false;
	else return;
	e.preventDefault();
	e.stopPropagation();
}

watch(
	[editVisible, completeVisible, stageVisible, ownerVisible],
	([edit, complete, stage, owner]) => {
		if (edit || complete || stage || owner) window.addEventListener('keydown', onDialogEscape, true);
		else window.removeEventListener('keydown', onDialogEscape, true);
	},
	{ immediate: true }
);
onUnmounted(() => window.removeEventListener('keydown', onDialogEscape, true));
const ownerFormRef = ref<any>();
const ownerForm = reactive({ ownerUserId: '', reason: '' });
const ownerRules = {
	ownerUserId: [{ required: true, message: '请选择新负责人', trigger: 'change' }],
	reason: [{ required: true, message: '请填写变更原因', trigger: 'blur' }],
};
const users = ref<MoldUserRow[]>([]);
const loadingUsers = ref(false);
const userListError = ref('');

async function openOwner(e?: Event) {
	if (!canOwner.value) return;
	rememberTriggerFromEvent(e);
	clearFeedback();
	ownerForm.ownerUserId = '';
	ownerForm.reason = '';
	userListError.value = '';
	ownerVisible.value = true;
	if (!users.value.length) {
		loadingUsers.value = true;
		try {
			users.value = await fetchUsers();
		} catch (err: any) {
			userListError.value =
				err instanceof MoldApiError && err.status === 403
					? '当前账号没有账号目录权限（mold:user:list 仅 OWNER）。'
					: err?.message || '账号目录加载失败';
		} finally {
			loadingUsers.value = false;
		}
	}
}

async function submitOwner() {
	if (missingTarget.value || userListError.value) return;
	const valid = await ownerFormRef.value?.validate().catch(() => false);
	if (!valid) return;
	submitting.value = true;
	clearFeedback();
	try {
		const reason = ownerForm.reason.trim();
		const scope = `project.owner:${props.project.id}:${stableHash(ownerForm.ownerUserId + '|' + reason)}`;
		const updated = await changeProjectOwner(
			props.project.id,
			ownerForm.ownerUserId,
			reason,
			beginIdempotentAction(scope)
		);
		finishIdempotentAction(scope);
		ElMessage.success('负责人已变更');
		ownerVisible.value = false;
		emit('saved', updated);
	} catch (err: any) {
		handleError(err, '变更负责人失败');
	} finally {
		submitting.value = false;
	}
}
</script>

<style scoped lang="scss">
.mold-actions {
	display: inline-flex;
	align-items: center;
	gap: 4px;
	flex-wrap: wrap;
}
.mold-dialog-alert {
	margin-bottom: 12px;
}
.mold-dialog-alert-body {
	display: flex;
	align-items: center;
	gap: 8px;
	flex-wrap: wrap;
}
.mold-dialog-actions {
	display: flex;
	justify-content: flex-end;
	gap: 8px;
	margin-top: 8px;
}
.mold-form-hint {
	font-size: 12px;
	line-height: 18px;
	color: var(--el-text-color-secondary);
	margin: 0 0 12px;
}
.mold-complete-rules {
	font-size: 13px;
	line-height: 20px;
	color: var(--el-text-color-regular);
	margin-bottom: 12px;
	ul {
		margin: 4px 0 0;
		padding-left: 20px;
	}
}
.mold-option-meta {
	float: right;
	color: var(--el-text-color-secondary);
	font-size: 12px;
}
.mold-date-range {
	display: flex;
	gap: 8px;
	width: 100%;
	.el-date-editor {
		flex: 1;
	}
}
@media (max-width: 900px) {
	.mold-date-range {
		flex-direction: column;
	}
}
</style>
