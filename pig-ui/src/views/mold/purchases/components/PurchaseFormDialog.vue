<template>
	<el-dialog
		v-model="visible"
		:title="isEdit ? `编辑采购单 ${form.code}` : '新建采购单'"
		:width="dialogWidth"
		:close-on-click-modal="false"
		append-to-body
		class="mold-dialog"
		@closed="onClosed"
	>
		<el-alert v-if="error" type="error" :closable="false" show-icon :title="error" class="mold-dialog-alert" />
		<el-alert
			v-if="isEdit && purchase?.status === 'CONFIRMED'"
			type="warning"
			:closable="false"
			show-icon
			title="已确认采购单只允许修改备注"
			description="归属、金额和明细不能直接修改；如需更改，请作废后重新录入。"
			class="mold-dialog-alert"
		/>

		<el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
			<div class="mold-form-grid">
				<el-form-item label="采购单号" prop="code" :error="fieldErrors.code">
					<el-input v-model="form.code" maxlength="64" :disabled="isEdit" placeholder="如 CG-2026-0001" />
				</el-form-item>
				<el-form-item label="供应商" prop="counterpartyId" :error="fieldErrors.counterpartyId">
					<el-select v-model="form.counterpartyId" filterable placeholder="选择供应商" style="width: 100%">
						<el-option v-for="c in suppliers" :key="c.id" :label="c.name" :value="c.id" />
					</el-select>
				</el-form-item>
				<el-form-item label="采购日期" prop="purchaseDate" :error="fieldErrors.purchaseDate">
					<el-date-picker v-model="form.purchaseDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
				</el-form-item>
				<el-form-item label="币种">
					<el-select v-model="form.currency" style="width: 100%">
						<el-option v-for="c in CURRENCIES" :key="c" :label="c" :value="c" />
					</el-select>
				</el-form-item>
				<el-form-item label="归属">
					<el-select v-model="form.allocationStatus" style="width: 100%" @change="onAllocationChange">
						<el-option label="归属项目" value="PROJECT" />
						<el-option label="公共采购" value="PUBLIC" />
						<el-option label="未分配" value="UNASSIGNED" />
					</el-select>
				</el-form-item>
				<el-form-item v-if="form.allocationStatus === 'PROJECT'" label="项目" prop="projectId" :error="fieldErrors.projectId">
					<el-select v-model="form.projectId" filterable placeholder="选择项目" style="width: 100%">
						<el-option v-for="p in projects" :key="p.id" :label="`${p.code} · ${p.name}`" :value="p.id" />
					</el-select>
				</el-form-item>
				<el-form-item label="含税">
					<el-select v-model="form.taxIncluded" clearable placeholder="未指定" style="width: 100%">
						<el-option label="含税" :value="true" />
						<el-option label="不含税" :value="false" />
					</el-select>
				</el-form-item>
			</div>

			<el-form-item label="备注">
				<el-input v-model="form.note" type="textarea" :rows="2" maxlength="2000" show-word-limit placeholder="可留空" />
			</el-form-item>

			<div class="mold-items-head">
				<span class="mold-card-title">采购明细</span>
				<span class="mold-inline-note">合计 <MoneyText :value="totalAmount" :currency="form.currency" /></span>
				<el-button size="small" :icon="Plus" :disabled="itemsLocked" @click="addItem">加一行</el-button>
			</div>
			<el-table :data="form.items" size="small" border class="mold-items-table" :max-height="260">
				<el-table-column label="行" width="52" align="center">
					<template #default="{ $index }">{{ $index + 1 }}</template>
				</el-table-column>
				<el-table-column label="名称" min-width="150">
					<template #default="{ row }">
						<el-input v-model="row.name" size="small" maxlength="200" :disabled="itemsLocked" placeholder="必填" />
					</template>
				</el-table-column>
				<el-table-column label="规格" min-width="120">
					<template #default="{ row }">
						<el-input v-model="row.spec" size="small" maxlength="255" :disabled="itemsLocked" />
					</template>
				</el-table-column>
				<el-table-column label="数量" width="100">
					<template #default="{ row }">
						<el-input v-model="row.qty" size="small" :disabled="itemsLocked" inputmode="decimal" />
					</template>
				</el-table-column>
				<el-table-column label="单位" width="80">
					<template #default="{ row }">
						<el-input v-model="row.unit" size="small" maxlength="32" :disabled="itemsLocked" />
					</template>
				</el-table-column>
				<el-table-column label="单价" width="110">
					<template #default="{ row }">
						<el-input v-model="row.unitPrice" size="small" :disabled="itemsLocked" inputmode="decimal" />
					</template>
				</el-table-column>
				<el-table-column label="金额" width="110" align="right">
					<template #default="{ row }">
						<MoneyText :value="lineAmount(row)" :currency="form.currency" />
					</template>
				</el-table-column>
				<el-table-column label="" width="56" align="center">
					<template #default="{ $index }">
						<el-button link type="danger" size="small" :disabled="itemsLocked || form.items.length <= 1" @click="removeItem($index)">删</el-button>
					</template>
				</el-table-column>
			</el-table>

			<p class="mold-form-hint">
				金额按「数量 × 单价」自动计算，以保存后的结果为准。新建的采购单是草稿，确认后才能收货、开票、付款。
			</p>

			<div class="mold-dialog-actions">
				<el-button @click="visible = false">取消</el-button>
				<el-button type="primary" native-type="submit" :loading="submitting" :disabled="noChanges">
					{{ noChanges ? '未修改' : isEdit ? '保存' : '创建采购单' }}
				</el-button>
			</div>
		</el-form>
	</el-dialog>
</template>

<script setup lang="ts" name="MoldPurchaseFormDialog">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import MoneyText from '/@/components/mold/MoneyText.vue';
import { createPurchase, updatePurchase, type MoldCounterpartyRow, type MoldPurchaseItem, type MoldPurchaseRow } from '/@/api/mold/purchases';
import { MoldApiError, type MoldProjectRow } from '/@/api/mold/projects';
import { describeMoldError } from '/@/api/mold/errors';
import { todayStamp, mulMoneyHalfUp, sumMoney } from '/@/utils/moldFormat';
import { beginIdempotentAction, finishIdempotentAction, stableHash } from '/@/utils/idempotency';

const props = defineProps<{
	modelValue: boolean;
	purchase?: MoldPurchaseRow | null;
	counterparties: MoldCounterpartyRow[];
	projects: MoldProjectRow[];
}>();
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'saved', row: MoldPurchaseRow): void }>();

const CURRENCIES = ['CNY', 'USD', 'EUR', 'HKD'];
const dialogWidth = computed(() => (window.innerWidth <= 900 ? '96%' : '760px'));
const visible = computed({
	get: () => props.modelValue,
	set: (v: boolean) => emit('update:modelValue', v),
});
const isEdit = computed(() => !!props.purchase);
const itemsLocked = computed(() => isEdit.value && props.purchase?.status === 'CONFIRMED');

const formRef = ref<any>();
const submitting = ref(false);
const error = ref('');
const fieldErrors = reactive<Record<string, string>>({});

const form = reactive<{
	code: string;
	counterpartyId: string;
	purchaseDate: string;
	projectId: string;
	allocationStatus: 'PROJECT' | 'PUBLIC' | 'UNASSIGNED';
	currency: string;
	taxIncluded: boolean | null;
	note: string;
	items: MoldPurchaseItem[];
}>({
	code: '',
	counterpartyId: '',
	purchaseDate: todayStamp(),
	projectId: '',
	allocationStatus: 'UNASSIGNED',
	currency: 'CNY',
	taxIncluded: null,
	note: '',
	items: [blankItem(1)],
});

function blankItem(lineNo: number): MoldPurchaseItem {
	return { lineNo, name: '', spec: '', qty: '1', unit: '', unitPrice: '0', note: '' };
}

const suppliers = computed(() => props.counterparties.filter((c) => c.type === 'SUPPLIER' && c.status === 'ACTIVE'));

const rules = {
	code: [{ required: true, message: '请填写采购单号', trigger: 'blur' }],
	counterpartyId: [{ required: true, message: '请选择供应商', trigger: 'change' }],
	purchaseDate: [{ required: true, message: '请选择采购日期', trigger: 'change' }],
	projectId: [{ required: true, message: '归属项目时必须选择项目', trigger: 'change' }],
};

// 与后端 BigDecimal HALF_UP 保持一致：按十进制整数运算，避免浮点 toFixed 差 1 分
const lineAmount = (item: MoldPurchaseItem) => mulMoneyHalfUp(item.qty, item.unitPrice) ?? '0.00';
const totalAmount = computed(() => sumMoney(form.items.map(lineAmount)));

/** 空 PATCH 防护：编辑时无任何字段变化则禁用保存 */
const noChanges = computed(() => {
	if (!isEdit.value || !props.purchase) return false;
	const p = props.purchase;
	if (p.status === 'CONFIRMED') return (p.note || '') === (form.note || '');
	const originalItems = (p.items || []).map((i) => `${i.name}|${i.spec || ''}|${i.qty}|${i.unit || ''}|${i.unitPrice}`);
	const nextItems = form.items.map((i) => `${i.name}|${i.spec || ''}|${i.qty}|${i.unit || ''}|${i.unitPrice}`);
	return (
		(p.counterpartyId || '') === form.counterpartyId &&
		(p.purchaseDate || '') === form.purchaseDate &&
		(p.projectId || '') === form.projectId &&
		(p.allocationStatus || '') === form.allocationStatus &&
		(p.currency || '') === form.currency &&
		(p.taxIncluded ?? null) === form.taxIncluded &&
		(p.note || '') === (form.note || '') &&
		originalItems.join('~') === nextItems.join('~')
	);
});

function reset() {
	const p = props.purchase;
	Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k]);
	error.value = '';
	if (p) {
		form.code = p.code;
		form.counterpartyId = p.counterpartyId;
		form.purchaseDate = p.purchaseDate;
		form.projectId = p.projectId || '';
		form.allocationStatus = p.allocationStatus || (p.projectId ? 'PROJECT' : 'UNASSIGNED');
		form.currency = p.currency || 'CNY';
		form.taxIncluded = p.taxIncluded ?? null;
		form.note = p.note || '';
		form.items = (p.items && p.items.length ? p.items : [blankItem(1)]).map((i, idx) => ({
			lineNo: idx + 1,
			name: i.name,
			spec: i.spec || '',
			qty: String(i.qty),
			unit: i.unit || '',
			unitPrice: String(i.unitPrice),
			note: i.note || '',
		}));
	} else {
		form.code = '';
		form.counterpartyId = '';
		form.purchaseDate = todayStamp();
		form.projectId = '';
		form.allocationStatus = 'UNASSIGNED';
		form.currency = 'CNY';
		form.taxIncluded = null;
		form.note = '';
		form.items = [blankItem(1)];
	}
}

watch(() => props.modelValue, (open) => {
	if (open) reset();
});

function onAllocationChange(value: string) {
	if (value !== 'PROJECT') form.projectId = '';
}

function addItem() {
	form.items.push(blankItem(form.items.length + 1));
}
function removeItem(index: number) {
	if (form.items.length <= 1) return;
	form.items.splice(index, 1);
}

function validateItems(): string {
	if (!form.items.length) return '采购明细至少 1 行';
	for (const [index, item] of form.items.entries()) {
		if (!String(item.name || '').trim()) return `第 ${index + 1} 行缺少名称`;
		const qty = Number(item.qty);
		if (!Number.isFinite(qty) || qty <= 0) return `第 ${index + 1} 行数量必须大于 0`;
		const price = Number(item.unitPrice);
		if (!Number.isFinite(price) || price < 0) return `第 ${index + 1} 行单价不能为负`;
	}
	return '';
}

async function submit() {
	if (noChanges.value) return;
	error.value = '';
	Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k]);
	try {
		await formRef.value?.validate();
	} catch {
		return;
	}
	const itemError = validateItems();
	if (itemError) {
		error.value = itemError;
		return;
	}
	if (form.allocationStatus === 'PROJECT' && !form.projectId) {
		fieldErrors.projectId = '归属项目时必须选择项目';
		return;
	}

	const items = form.items.map((item, index) => ({
		lineNo: index + 1,
		name: item.name.trim(),
		spec: item.spec?.trim() || null,
		qty: String(item.qty),
		unit: item.unit?.trim() || null,
		unitPrice: String(item.unitPrice),
		note: item.note?.trim() || null,
	}));

	submitting.value = true;
	try {
		let saved: MoldPurchaseRow;
		if (isEdit.value && props.purchase) {
			const p = props.purchase;
			const body: Record<string, any> = {};
			if (p.status === 'CONFIRMED') {
				body.note = form.note.trim() || null;
			} else {
				if ((p.counterpartyId || '') !== form.counterpartyId) body.counterpartyId = form.counterpartyId;
				if ((p.purchaseDate || '') !== form.purchaseDate) body.purchaseDate = form.purchaseDate;
				if ((p.projectId || '') !== form.projectId) body.projectId = form.projectId || null;
				if ((p.allocationStatus || '') !== form.allocationStatus) body.allocationStatus = form.allocationStatus;
				if ((p.currency || '') !== form.currency) body.currency = form.currency;
				if ((p.taxIncluded ?? null) !== form.taxIncluded) body.taxIncluded = form.taxIncluded;
				if ((p.note || '') !== (form.note || '')) body.note = form.note.trim() || null;
				body.items = items;
			}
			saved = await updatePurchase(p.id, body, p.version);
		} else {
			const body = {
				code: form.code.trim(),
				counterpartyId: form.counterpartyId,
				purchaseDate: form.purchaseDate,
				projectId: form.allocationStatus === 'PROJECT' ? form.projectId : null,
				allocationStatus: form.allocationStatus,
				currency: form.currency,
				taxIncluded: form.taxIncluded,
				note: form.note.trim() || null,
				items,
			};
			const scope = `purchase.create:${stableHash(JSON.stringify(body))}`;
			saved = await createPurchase(body, beginIdempotentAction(scope));
			finishIdempotentAction(scope);
		}
		ElMessage.success(isEdit.value ? '采购单已保存' : '采购单已创建（草稿）');
		emit('saved', saved);
		visible.value = false;
	} catch (err: any) {
		if (err instanceof MoldApiError) {
			Object.assign(fieldErrors, err.fieldErrors || {});
		}
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
	grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
	gap: 0 16px;
}
.mold-items-head {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	gap: 12px;
	margin: 4px 0 8px;
	.mold-card-title {
		margin: 0;
	}
	.mold-inline-note {
		margin-left: auto;
	}
}
.mold-items-table {
	width: 100%;
}
@media (max-width: 900px) {
	.mold-items-table {
		font-size: 12px;
	}
}
</style>
