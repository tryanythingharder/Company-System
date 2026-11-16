<template>
	<el-dialog
		v-model="visible"
		:title="isEdit ? `编辑往来单位 ${form.name}` : '新建往来单位'"
		:width="dialogWidth"
		:close-on-click-modal="false"
		append-to-body
		class="mold-dialog"
		@closed="onClosed"
	>
		<el-alert v-if="error" type="error" :closable="false" show-icon :title="error" class="mold-dialog-alert" />
		<el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
			<div class="mold-form-grid">
				<el-form-item label="名称" prop="name" :error="fieldErrors.name">
					<el-input v-model="form.name" maxlength="200" placeholder="客户 / 供应商名称" />
				</el-form-item>
				<el-form-item label="类型" prop="type" :error="fieldErrors.type">
					<el-select v-model="form.type" style="width: 100%">
						<el-option label="客户" value="CUSTOMER" />
						<el-option label="供应商" value="SUPPLIER" />
						<el-option label="其他" value="OTHER" />
					</el-select>
				</el-form-item>
				<el-form-item label="联系人">
					<el-input v-model="form.contact" maxlength="64" />
				</el-form-item>
				<el-form-item label="电话">
					<el-input v-model="form.phone" maxlength="32" />
				</el-form-item>
				<el-form-item label="税号">
					<el-input v-model="form.taxNo" maxlength="64" />
				</el-form-item>
				<el-form-item v-if="isEdit" label="状态">
					<el-select v-model="form.status" style="width: 100%">
						<el-option label="合作中" value="ACTIVE" />
						<el-option label="停用" value="DISABLED" />
					</el-select>
				</el-form-item>
			</div>
			<el-form-item label="备注">
				<el-input v-model="form.note" type="textarea" :rows="2" maxlength="2000" show-word-limit />
			</el-form-item>
			<div class="mold-dialog-actions">
				<el-button @click="visible = false">取消</el-button>
				<el-button type="primary" native-type="submit" :loading="submitting" :disabled="noChanges">
					{{ noChanges ? '未修改' : '保存' }}
				</el-button>
			</div>
		</el-form>
	</el-dialog>
</template>

<script setup lang="ts" name="MoldCounterpartyDialog">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
	createCounterparty,
	updateCounterparty,
	type MoldCounterpartyRow,
	type MoldCounterpartyType,
} from '/@/api/mold/purchases';
import { MoldApiError } from '/@/api/mold/projects';
import { describeMoldError } from '/@/api/mold/errors';
import { beginIdempotentAction, finishIdempotentAction, stableHash } from '/@/utils/idempotency';

const props = defineProps<{ modelValue: boolean; counterparty?: MoldCounterpartyRow | null }>();
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'saved', row: MoldCounterpartyRow): void }>();

const visible = computed({ get: () => props.modelValue, set: (v: boolean) => emit('update:modelValue', v) });
const isEdit = computed(() => !!props.counterparty);
const dialogWidth = computed(() => (window.innerWidth <= 900 ? '94%' : '560px'));
const formRef = ref<any>();
const submitting = ref(false);
const error = ref('');
const fieldErrors = reactive<Record<string, string>>({});

const form = reactive({
	name: '',
	type: 'SUPPLIER' as MoldCounterpartyType,
	contact: '',
	phone: '',
	taxNo: '',
	note: '',
	status: 'ACTIVE' as 'ACTIVE' | 'DISABLED',
});

const rules = {
	name: [{ required: true, message: '请填写名称', trigger: 'blur' }],
	type: [{ required: true, message: '请选择类型', trigger: 'change' }],
};

const noChanges = computed(() => {
	if (!isEdit.value || !props.counterparty) return false;
	const c = props.counterparty;
	return (
		c.name === form.name &&
		c.type === form.type &&
		(c.contact ?? c.contactName ?? '') === form.contact &&
		(c.phone ?? '') === form.phone &&
		(c.taxNo ?? '') === form.taxNo &&
		(c.note ?? '') === form.note &&
		c.status === form.status
	);
});

watch(
	() => props.modelValue,
	(open) => {
		if (!open) return;
		Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k]);
		error.value = '';
		const c = props.counterparty;
		form.name = c?.name || '';
		form.type = (c?.type as MoldCounterpartyType) || 'SUPPLIER';
		form.contact = c?.contact ?? c?.contactName ?? '';
		form.phone = c?.phone || '';
		form.taxNo = c?.taxNo || '';
		form.note = c?.note || '';
		form.status = c?.status === 'DISABLED' || (c?.status as string) === 'INACTIVE' ? 'DISABLED' : 'ACTIVE';
	}
);

async function submit() {
	if (noChanges.value) return;
	error.value = '';
	Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k]);
	try {
		await formRef.value?.validate();
	} catch {
		return;
	}
	submitting.value = true;
	try {
		const body = {
			name: form.name.trim(),
			type: form.type,
			contact: form.contact.trim() || null,
			phone: form.phone.trim() || null,
			taxNo: form.taxNo.trim() || null,
			note: form.note.trim() || null,
		};
		let saved;
		if (isEdit.value && props.counterparty) {
			saved = await updateCounterparty(props.counterparty.id, { ...body, status: form.status }, props.counterparty.version);
		} else {
			const scope = `counterparty.create:${stableHash(JSON.stringify(body))}`;
			saved = await createCounterparty(body, beginIdempotentAction(scope));
			finishIdempotentAction(scope);
		}
		ElMessage.success(isEdit.value ? '往来单位已保存' : '往来单位已创建');
		emit('saved', saved);
		visible.value = false;
	} catch (err: any) {
		if (err instanceof MoldApiError) Object.assign(fieldErrors, err.fieldErrors || {});
		error.value = describeMoldError(err, '保存失败，请重试').message;
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
	grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
	gap: 0 16px;
}
</style>
