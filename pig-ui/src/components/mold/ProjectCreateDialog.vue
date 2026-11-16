<!-- 新建项目（契约 6.5 POST /projects，幂等必填）
     字段口径取自 ProjectService.create 的 rejectUnknownFields 白名单：
     code / name / businessType / ownerUserId（必填）、dueDate / note（可选） -->
<template>
	<el-dialog
		v-model="visible"
		title="新建项目"
		:width="dialogWidth"
		:close-on-click-modal="false"
		append-to-body
		class="mold-dialog"
		@closed="onClosed"
	>
		<el-alert v-if="error" type="error" :closable="false" show-icon :title="error" class="mold-dialog-alert" />
		<el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
			<div class="mold-form-grid">
				<el-form-item label="项目编号" prop="code" :error="fieldErrors.code">
					<el-input v-model="form.code" maxlength="64" placeholder="如 DEMO-2026-009" data-testid="project-code" />
				</el-form-item>
				<el-form-item label="项目名称" prop="name" :error="fieldErrors.name">
					<el-input v-model="form.name" maxlength="200" show-word-limit data-testid="project-name" />
				</el-form-item>
				<el-form-item label="业务类型" prop="businessType" :error="fieldErrors.businessType">
					<el-select v-model="form.businessType" style="width: 100%" data-testid="project-business-type">
						<el-option label="整套模具制作" value="MOLD_SET" />
						<el-option label="零件·工序加工" value="PART_PROCESS" />
					</el-select>
				</el-form-item>
				<el-form-item label="项目负责人" prop="ownerUserId" :error="fieldErrors.ownerUserId">
					<el-select
						v-model="form.ownerUserId"
						filterable
						placeholder="选择账号"
						style="width: 100%"
						:loading="loadingUsers"
						data-testid="project-owner"
					>
						<el-option v-for="u in users" :key="u.id" :label="u.displayName" :value="u.id">
							<span>{{ u.displayName }}</span>
						</el-option>
					</el-select>
				</el-form-item>
				<el-form-item label="计划交期" prop="dueDate" :error="fieldErrors.dueDate">
					<el-date-picker
						v-model="form.dueDate"
						type="date"
						value-format="YYYY-MM-DD"
						placeholder="可留空"
						style="width: 100%"
						data-testid="project-due"
					/>
				</el-form-item>
			</div>
			<el-form-item label="备注" prop="note" :error="fieldErrors.note">
				<el-input v-model="form.note" type="textarea" :rows="3" maxlength="2000" show-word-limit placeholder="可留空" data-testid="project-note" />
			</el-form-item>
			<p class="mold-form-hint">
				项目创建后状态为「进行中」；创建人需是项目负责人本人才能继续维护该项目。同一份内容重复提交只会建一个项目。
			</p>
			<div class="mold-dialog-actions">
				<el-button @click="visible = false">取消</el-button>
				<el-button type="primary" native-type="submit" :loading="submitting" data-testid="project-create-submit">
					创建项目
				</el-button>
			</div>
		</el-form>
	</el-dialog>
</template>

<script setup lang="ts" name="MoldProjectCreateDialog">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { createProject, MoldApiError, type MoldBusinessType, type MoldProjectRow } from '/@/api/mold/projects';
import { fetchDirectory } from '/@/api/mold/users';
import { describeMoldError } from '/@/api/mold/errors';
import { beginIdempotentAction, finishIdempotentAction, stableHash } from '/@/utils/idempotency';

const props = defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{
	(e: 'update:modelValue', v: boolean): void;
	(e: 'created', row: MoldProjectRow): void;
}>();

const visible = computed({ get: () => props.modelValue, set: (v: boolean) => emit('update:modelValue', v) });
const dialogWidth = computed(() => (window.innerWidth <= 900 ? '94%' : '560px'));

const formRef = ref<any>();
const form = reactive({
	code: '',
	name: '',
	businessType: 'MOLD_SET' as MoldBusinessType,
	ownerUserId: '',
	dueDate: '' as string | null,
	note: '',
});
const rules = {
	code: [{ required: true, message: '请输入项目编号', trigger: 'blur' }],
	name: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
	businessType: [{ required: true, message: '请选择业务类型', trigger: 'change' }],
	ownerUserId: [{ required: true, message: '请选择项目负责人', trigger: 'change' }],
};

const error = ref('');
const submitting = ref(false);
const fieldErrors = reactive<Record<string, string>>({});
const users = ref<{ id: string; displayName: string }[]>([]);
const loadingUsers = ref(false);

async function loadUsers() {
	if (users.value.length) return;
	loadingUsers.value = true;
	try {
		// 契约 6.4：GET /directory/users 六角色可用（/users 仅 OWNER，PM 会 403）
		users.value = (await fetchDirectory()).rows;
	} catch (err: any) {
		error.value = describeMoldError(err, '账号目录加载失败').message;
	} finally {
		loadingUsers.value = false;
	}
}

watch(
	() => props.modelValue,
	(open) => {
		if (!open) return;
		error.value = '';
		Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k]);
		form.code = '';
		form.name = '';
		form.businessType = 'MOLD_SET';
		form.ownerUserId = '';
		form.dueDate = null;
		form.note = '';
		loadUsers();
	}
);

async function submit() {
	if (submitting.value) return;
	error.value = '';
	Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k]);
	// 先置占位再校验：Enter 连发或双击不会在校验期间重复放行
	submitting.value = true;
	try {
		await formRef.value?.validate();
	} catch {
		submitting.value = false;
		return;
	}
	const body = {
		code: form.code.trim(),
		name: form.name.trim(),
		businessType: form.businessType,
		ownerUserId: form.ownerUserId,
		dueDate: form.dueDate || null,
		note: form.note.trim() || null,
	};
	// 同一份内容复用同一个键：双击/重试只建一个项目
	const scope = `project.create:${stableHash(JSON.stringify(body))}`;
	try {
		const created = await createProject(body, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success(`项目 ${created.code} 已创建`);
		visible.value = false;
		emit('created', created);
	} catch (err: any) {
		if (err instanceof MoldApiError && err.code === 'PROJECT_CODE_DUPLICATE') {
			const dup = String((err as any).details?.value || body.code);
			fieldErrors.code = `项目编号「${dup}」已存在，请换一个`;
			error.value = '';
		} else {
			if (err instanceof MoldApiError) Object.assign(fieldErrors, err.fieldErrors || {});
			error.value = describeMoldError(err, '创建失败，请重试').message;
		}
	} finally {
		submitting.value = false;
	}
}

function onClosed() {
	error.value = '';
	submitting.value = false;
}
</script>

<style scoped lang="scss">
.mold-form-grid {
	display: grid;
	grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
	gap: 0 16px;
}
.mold-option-meta {
	float: right;
	color: var(--el-text-color-secondary);
	font-size: 12px;
}
.mold-form-hint {
	font-size: 12px;
	line-height: 18px;
	color: var(--el-text-color-secondary);
	margin: 0 0 12px;
}
</style>
