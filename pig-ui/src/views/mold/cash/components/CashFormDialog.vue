<template>
	<el-dialog
		v-model="visible"
		title="新建收支流水"
		:width="dialogWidth"
		:close-on-click-modal="false"
		append-to-body
		class="mold-dialog"
		@closed="onClosed"
	>
		<el-alert v-if="error" type="error" :closable="false" show-icon :title="error" class="mold-dialog-alert" />
		<el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
			<div class="mold-form-grid">
				<el-form-item label="流水号（自动生成）" prop="code" :error="fieldErrors.code">
					<el-input v-model="form.code" maxlength="64" :readonly="codeLocked" placeholder="打开表单时自动生成" data-testid="cash-code">
						<template v-if="canEditCode" #append>
							<el-button data-testid="cash-code-toggle" @click="codeLocked = !codeLocked">{{ codeLocked ? '改' : '锁定' }}</el-button>
						</template>
					</el-input>
					<p class="mold-form-hint">默认按时间自动生成，一般不用改；需沿用纸质单号时点「改」手工填写。</p>
				</el-form-item>
				<el-form-item label="类别" prop="category" :error="fieldErrors.category">
					<el-select v-model="form.category" style="width: 100%" @change="onCategoryChange">
						<el-option v-for="c in CATEGORIES" :key="c.value" :label="`${c.label}（${c.direction === 'IN' ? '收' : '支'}）`" :value="c.value" />
					</el-select>
				</el-form-item>
				<el-form-item label="发生日期" prop="occurredOn" :error="fieldErrors.occurredOn">
					<el-date-picker v-model="form.occurredOn" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
				</el-form-item>
				<el-form-item label="金额" prop="amount" :error="fieldErrors.amount">
					<el-input v-model="form.amount" inputmode="decimal" placeholder="大于 0，两位小数" />
				</el-form-item>
				<el-form-item label="币种">
					<el-select v-model="form.currency" style="width: 100%">
						<el-option v-for="c in CURRENCIES" :key="c" :label="c" :value="c" />
					</el-select>
				</el-form-item>
				<el-form-item label="收付方式">
					<el-select v-model="form.method" style="width: 100%">
						<el-option v-for="m in METHODS" :key="m.value" :label="m.label" :value="m.value" />
					</el-select>
				</el-form-item>
				<el-form-item label="归属">
					<el-select v-model="form.allocationStatus" style="width: 100%" @change="onAllocationChange">
						<el-option label="归属项目" value="PROJECT" />
						<el-option label="公共（不分摊）" value="PUBLIC" />
						<el-option label="未分配" value="UNASSIGNED" />
					</el-select>
				</el-form-item>
				<el-form-item v-if="form.allocationStatus === 'PROJECT'" label="项目" prop="projectId" :error="fieldErrors.projectId">
					<el-select v-model="form.projectId" filterable placeholder="选择项目" style="width: 100%">
						<el-option v-for="p in projects" :key="p.id" :label="`${p.code} · ${p.name}`" :value="p.id" />
					</el-select>
				</el-form-item>
				<el-form-item :label="counterpartyRequired ? '往来单位（必选客户）' : '往来单位（可选）'" prop="counterpartyId" :error="fieldErrors.counterpartyId">
					<el-select
						v-model="form.counterpartyId"
						clearable
						filterable
						:placeholder="counterpartyRequired ? '客户收款必须选择客户' : '可不选'"
						style="width: 100%"
					>
						<el-option v-for="c in counterpartyOptions" :key="c.id" :label="`${c.name}（${typeText(c.type)}）`" :value="c.id" />
					</el-select>
					<p v-if="counterpartyRequired && !counterpartyOptions.length" class="mold-form-hint">
						还没有可用客户：请联系财务在往来单位里维护客户，或改选其他类别。
					</p>
				</el-form-item>
				<el-form-item label="凭证号（可选）">
					<el-input v-model="form.voucherNo" maxlength="64" />
				</el-form-item>
			</div>
			<el-form-item label="备注">
				<el-input v-model="form.note" type="textarea" :rows="2" maxlength="2000" show-word-limit />
			</el-form-item>
			<p class="mold-form-hint">
				方向由类别决定（{{ directionText }}）。金额录错不改原单，请找财务走「更正记录」。
			</p>
			<div class="mold-dialog-actions">
				<el-button @click="visible = false">取消</el-button>
				<el-button
					type="primary"
					native-type="submit"
					:loading="submitting"
					:disabled="counterpartyRequired && !counterpartyOptions.length"
				>
					创建流水
				</el-button>
			</div>
		</el-form>
	</el-dialog>
</template>

<script setup lang="ts" name="MoldCashFormDialog">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
	CATEGORY_DIRECTION,
	createCashEntry,
	type MoldCashCategory,
	type MoldCashMethod,
	type MoldCashRow,
} from '/@/api/mold/cash';
import type { MoldCounterpartyRow as CpRow } from '/@/api/mold/purchases';
import { MoldApiError, type MoldProjectRow } from '/@/api/mold/projects';
import { describeMoldError } from '/@/api/mold/errors';
import { useMoldPermissions } from '/@/api/mold/permissions';
import { todayStamp, beijingCodeStamp } from '/@/utils/moldFormat';
import { beginIdempotentAction, finishIdempotentAction, stableHash } from '/@/utils/idempotency';

const props = defineProps<{
	modelValue: boolean;
	projects: MoldProjectRow[];
	counterparties: CpRow[];
}>();
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'saved', row: MoldCashRow): void }>();

const visible = computed({ get: () => props.modelValue, set: (v: boolean) => emit('update:modelValue', v) });
const dialogWidth = computed(() => (window.innerWidth <= 900 ? '94%' : '620px'));

const CURRENCIES = ['CNY', 'USD', 'EUR', 'HKD'];
const METHODS: { value: MoldCashMethod; label: string }[] = [
	{ value: 'BANK', label: '银行转账' },
	{ value: 'ALIPAY', label: '支付宝' },
	{ value: 'WECHAT', label: '微信' },
	{ value: 'CASH', label: '现金' },
	{ value: 'OTHER', label: '其他' },
];
/** 普通流水只开放四类；采购付款/退款走采购单专用入口（契约 6.8） */
const CATEGORIES: { value: MoldCashCategory; label: string; direction: 'IN' | 'OUT' }[] = [
	{ value: 'CUSTOMER_RECEIPT', label: '客户收款', direction: 'IN' },
	{ value: 'DAILY_EXPENSE', label: '日常支出', direction: 'OUT' },
	{ value: 'OTHER_INCOME', label: '其他收入', direction: 'IN' },
	{ value: 'OTHER_EXPENSE', label: '其他支出', direction: 'OUT' },
];

const typeText = (value: string) => ({ CUSTOMER: '客户', SUPPLIER: '供应商', OTHER: '其他' } as Record<string, string>)[value] || value;

const formRef = ref<any>();
const submitting = ref(false);
const error = ref('');
const fieldErrors = reactive<Record<string, string>>({});

const form = reactive({
	code: '',
	category: 'DAILY_EXPENSE' as MoldCashCategory,
	occurredOn: todayStamp(),
	amount: '',
	currency: 'CNY',
	method: 'CASH' as MoldCashMethod,
	allocationStatus: 'UNASSIGNED' as 'PROJECT' | 'PUBLIC' | 'UNASSIGNED',
	projectId: '',
	counterpartyId: '',
	voucherNo: '',
	note: '',
});
/** 流水号默认只读（自动生成）；有 mold:cash:update 的财务可点「改」沿用纸质单号 */
const codeLocked = ref(true);
const { can } = useMoldPermissions();
const canEditCode = computed(() => can('mold:cash:update'));

const rules = {
	code: [{ required: true, message: '请填写流水号', trigger: 'blur' }],
	category: [{ required: true, message: '请选择类别', trigger: 'change' }],
	occurredOn: [{ required: true, message: '请选择发生日期', trigger: 'change' }],
	amount: [{ required: true, message: '请填写金额', trigger: 'blur' }],
	projectId: [{ required: true, message: '归属项目时必须选择项目', trigger: 'change' }],
	// 服务端规则：CUSTOMER_RECEIPT 必须指定启用中的客户（CashEntryService.resolveCounterparty）
	counterpartyId: [
		{
			validator: (_rule: any, value: string, callback: (err?: Error) => void) => {
				if (form.category === 'CUSTOMER_RECEIPT' && !value) callback(new Error('客户收款必须选择客户'));
				else callback();
			},
			trigger: 'change',
		},
	],
};

const directionText = computed(() => (CATEGORY_DIRECTION[form.category] === 'IN' ? '收入' : '支出'));

/** 客户收款只能选启用中的客户；其他类别可选任意启用中的往来单位 */
const counterpartyRequired = computed(() => form.category === 'CUSTOMER_RECEIPT');
const counterpartyOptions = computed(() =>
	props.counterparties.filter((c) => {
		if ((c.status || 'ACTIVE') !== 'ACTIVE') return false;
		if (counterpartyRequired.value) return c.type === 'CUSTOMER';
		return true;
	})
);

function onCategoryChange(value: MoldCashCategory) {
	// 类别切换后，已选往来单位可能不再符合服务端约束（如非客户类型），直接清空避免提交被拒
	const stillValid = counterpartyOptions.value.some((c) => c.id === form.counterpartyId);
	if (!stillValid) form.counterpartyId = '';
	void value;
}

function onAllocationChange(value: string) {
	if (value !== 'PROJECT') form.projectId = '';
}


/**
 * 自动流水号：SK-yyyyMMdd-HHmmss-XXX
 * XXX 为 3 位大写字母/数字（36^3 = 46656 组合）。同一秒内重复概率约 1/46656，
 * 若服务端仍判重（CASH_ENTRY_CODE_DUPLICATE）会自动换号并提示再次提交。
 */
function generateCode(): string {
	// 按北京时间取号，异地/海外设备打开也不会生成错位日期的流水号
	const stamp = beijingCodeStamp();
	const rand = Math.random().toString(36).slice(2, 5).toUpperCase().padEnd(3, '0');
	return `SK-${stamp}-${rand}`;
}

function reset() {
	Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k]);
	error.value = '';
	form.code = generateCode();
	codeLocked.value = true;
	// 随手记场景：默认「日常支出（方向自动 OUT）+ 现金」，不选客户也能直接提交
	form.category = 'DAILY_EXPENSE';
	form.occurredOn = todayStamp();
	form.amount = '';
	form.currency = 'CNY';
	form.method = 'CASH';
	form.allocationStatus = 'UNASSIGNED';
	form.projectId = '';
	form.counterpartyId = '';
	form.voucherNo = '';
	form.note = '';
}

watch(
	() => props.modelValue,
	(open) => {
		if (open) reset();
	}
);

async function submit() {
	error.value = '';
	Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k]);
	try {
		await formRef.value?.validate();
	} catch {
		return;
	}
	const amount = Number(form.amount);
	if (!Number.isFinite(amount) || amount <= 0) {
		fieldErrors.amount = '金额必须大于 0';
		return;
	}
	if (form.allocationStatus === 'PROJECT' && !form.projectId) {
		fieldErrors.projectId = '归属项目时必须选择项目';
		return;
	}
	if (counterpartyRequired.value && !form.counterpartyId) {
		fieldErrors.counterpartyId = '客户收款必须选择客户';
		return;
	}
	if (form.counterpartyId && !counterpartyOptions.value.some((c) => c.id === form.counterpartyId)) {
		fieldErrors.counterpartyId = '所选往来单位已停用或类型不符，请重新选择';
		return;
	}
	submitting.value = true;
	const cashBody = {
		code: form.code.trim(),
		direction: CATEGORY_DIRECTION[form.category],
		category: form.category,
		occurredOn: form.occurredOn,
		amount: form.amount,
		currency: form.currency,
		counterpartyId: form.counterpartyId || null,
		projectId: form.allocationStatus === 'PROJECT' ? form.projectId : null,
		allocationStatus: form.allocationStatus,
		method: form.method,
		voucherNo: form.voucherNo.trim() || null,
		note: form.note.trim() || null,
	};
	const scope = `cash.create:${stableHash(JSON.stringify(cashBody))}`;
	try {
		const saved = await createCashEntry(cashBody, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success('流水已创建');
		emit('saved', saved);
		visible.value = false;
	} catch (err: any) {
		if (err instanceof MoldApiError) Object.assign(fieldErrors, err.fieldErrors || {});
		if (err?.code === 'CASH_ENTRY_CODE_DUPLICATE' || /流水编号已存在/.test(err?.message || '')) {
			// 自动号撞号（同秒同随机）或手工填了已存在的号：换一个新号并放开，避免用户卡死
			form.code = generateCode();
			codeLocked.value = true;
			error.value = '流水号已被占用，已自动换一个新号，请再次提交。';
			return;
		}
		error.value = describeMoldError(err, '创建失败，请重试').message;
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
