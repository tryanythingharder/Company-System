<template>
	<el-drawer
		v-model="visible"
		:size="drawerSize"
		:with-header="false"
		append-to-body
		class="mold-purchase-drawer"
		@closed="onClosed"
	>
		<div class="mold-pd">
			<div class="mold-pd-head">
				<div class="mold-pd-title">
					<div class="mold-pd-code">{{ purchase?.code || '采购单' }}</div>
					<h2>{{ counterpartyName }}</h2>
					<div class="mold-pd-tags">
						<StatusTag v-if="purchase" kind="purchase" :value="purchase.status" />
						<span v-if="purchase" class="mold-pd-version">v{{ purchase.version }}</span>
						<span v-if="purchase" class="mold-pd-date">{{ formatDate(purchase.purchaseDate) }}</span>
					</div>
				</div>
				<div class="mold-pd-head-actions">
					<el-button v-if="canEdit" size="small" @click="emit('edit', purchase)">编辑</el-button>
					<el-button
						v-if="canConfirm && purchase?.status === 'DRAFT'"
						size="small"
						type="primary"
						:loading="acting"
						@click="onConfirm"
					>
						确认采购单
					</el-button>
					<el-button v-if="canVoid && purchase && purchase.status !== 'VOID'" size="small" type="danger" plain @click="openVoid">
						作废
					</el-button>
					<el-button size="small" :icon="Refresh" aria-label="刷新采购单" :loading="loading" @click="loadAll" />
					<el-button size="small" text @click="visible = false">关闭</el-button>
				</div>
			</div>

			<div v-if="error" class="mold-pd-error" role="alert">{{ error }}</div>
			<div v-if="loading" class="mold-skeleton" aria-busy="true">
				<div class="mold-skel-block" style="height: 72px; width: 100%; margin-bottom: 10px" />
				<div class="mold-skel-block" style="height: 180px; width: 100%" />
			</div>

			<template v-else-if="purchase">
				<div class="mold-pd-stats">
					<div class="mold-pd-stat">
						<div class="mold-pd-stat-label">采购金额</div>
						<div class="mold-pd-stat-value"><MoneyText :value="purchase.amountTotal" :currency="purchase.currency" /></div>
					</div>
					<div class="mold-pd-stat">
						<div class="mold-pd-stat-label">已付款</div>
						<div class="mold-pd-stat-value"><MoneyText :value="summary?.paidAmount" :currency="purchase.currency" /></div>
					</div>
					<div class="mold-pd-stat">
						<div class="mold-pd-stat-label">已开票</div>
						<div class="mold-pd-stat-value"><MoneyText :value="summary?.invoicedAmount" :currency="purchase.currency" /></div>
					</div>
					<div class="mold-pd-stat">
						<div class="mold-pd-stat-label">进度</div>
						<div class="mold-pd-stat-tags">
							<StatusTag kind="progress" :value="summary?.receiptStatus || purchase.receiptStatus" />
							<StatusTag kind="progress" :value="summary?.invoiceStatus || purchase.invoiceStatus" />
							<StatusTag kind="payment" :value="summary?.paymentStatus || purchase.paymentStatus" />
						</div>
					</div>
				</div>

				<el-tabs v-model="tab" class="mold-pd-tabs">
					<el-tab-pane label="采购明细" name="items">
						<el-table :data="purchase.items || []" size="small" border>
							<el-table-column prop="lineNo" label="行" width="52" align="center" />
							<el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
							<el-table-column prop="spec" label="规格" min-width="110" show-overflow-tooltip />
							<el-table-column prop="qty" label="数量" width="90" align="right" />
							<el-table-column prop="unit" label="单位" width="70" />
							<el-table-column label="单价" width="110" align="right">
								<template #default="{ row }"><MoneyText :value="row.unitPrice" :currency="purchase.currency" /></template>
							</el-table-column>
							<el-table-column label="金额" width="120" align="right">
								<template #default="{ row }"><MoneyText :value="row.amount" :currency="purchase.currency" /></template>
							</el-table-column>
						</el-table>
						<div class="mold-pd-kv">
							<dl class="mold-kv mold-kv-tight">
								<div><dt>归属</dt><dd>{{ allocationText(purchase.allocationStatus) }}</dd></div>
								<div><dt>项目</dt><dd>{{ projectName || '—' }}</dd></div>
								<div><dt>币种</dt><dd>{{ purchase.currency }}</dd></div>
								<div><dt>含税</dt><dd>{{ purchase.taxIncluded === true ? '含税' : purchase.taxIncluded === false ? '不含税' : '未指定' }}</dd></div>
								<div><dt>备注</dt><dd>{{ purchase.note || '—' }}</dd></div>
							</dl>
						</div>
					</el-tab-pane>

					<el-tab-pane :label="`收货（${receipts.length}）`" name="receipts">
						<div class="mold-pd-tab-head">
							<span class="mold-inline-note">收货累计不得超采购量（服务端 422 OVER_RECEIPT）。</span>
							<el-button v-if="canReceipt && purchase.status === 'CONFIRMED'" size="small" type="primary" @click="openReceipt">
								登记收货
							</el-button>
						</div>
						<el-table :data="receipts" size="small" border empty-text="还没有收货记录">
							<el-table-column prop="receivedOn" label="收货日期" width="120" />
							<el-table-column label="数量" min-width="180">
								<template #default="{ row }">
									<span v-for="item in row.items" :key="item.purchaseItemId" class="mold-pd-chip">
										{{ itemName(item.purchaseItemId) }} × {{ item.qtyReceived }}
									</span>
								</template>
							</el-table-column>
							<el-table-column label="状态" width="90" align="center">
								<template #default="{ row }"><StatusTag kind="receipt" :value="row.status" /></template>
							</el-table-column>
							<el-table-column label="操作" width="80" align="right">
								<template #default="{ row }">
									<el-button v-if="canVoidReceipt && row.status === 'ACTIVE'" link type="danger" size="small" @click="openVoidReceipt(row)">
										作废
									</el-button>
								</template>
							</el-table-column>
						</el-table>
					</el-tab-pane>

					<el-tab-pane :label="`发票（${invoices.length}）`" name="invoices">
						<div class="mold-pd-tab-head">
							<span class="mold-inline-note">发票累计不得超采购额；同供应商 ACTIVE 发票号唯一。</span>
							<el-button v-if="canInvoice && purchase.status === 'CONFIRMED'" size="small" type="primary" @click="openInvoice">
								登记发票
							</el-button>
						</div>
						<el-table :data="invoices" size="small" border empty-text="还没有发票">
							<el-table-column prop="invoiceNo" label="发票号" min-width="130" />
							<el-table-column label="类型" width="100">
								<template #default="{ row }">{{ invoiceTypeText(row.invoiceType) }}</template>
							</el-table-column>
							<el-table-column prop="issueDate" label="开票日期" width="110" />
							<el-table-column label="金额" width="120" align="right">
								<template #default="{ row }"><MoneyText :value="row.amountTotal" :currency="row.currency" /></template>
							</el-table-column>
							<el-table-column label="状态" width="86" align="center">
								<template #default="{ row }"><StatusTag kind="invoice" :value="row.status" /></template>
							</el-table-column>
							<el-table-column label="操作" width="80" align="right">
								<template #default="{ row }">
									<el-button v-if="canVoidInvoice && row.status === 'ACTIVE'" link type="danger" size="small" @click="openVoidInvoice(row)">
										作废
									</el-button>
								</template>
							</el-table-column>
						</el-table>
					</el-tab-pane>

					<el-tab-pane :label="`付款（${payments.length}）`" name="payments">
						<div class="mold-pd-tab-head">
							<span class="mold-inline-note">付款落到收支流水（PURCHASE_PAYMENT / PURCHASE_REFUND），付款净额不得超采购额。</span>
							<div>
								<el-button v-if="canPay && purchase.status === 'CONFIRMED'" size="small" @click="openPayment('PURCHASE_REFUND')">
									登记退款
								</el-button>
								<el-button v-if="canPay && purchase.status === 'CONFIRMED'" size="small" type="primary" @click="openPayment('PURCHASE_PAYMENT')">
									登记付款
								</el-button>
							</div>
						</div>
						<el-table :data="payments" size="small" border empty-text="还没有付款记录">
							<el-table-column prop="code" label="流水号" min-width="150" show-overflow-tooltip />
							<el-table-column prop="occurredOn" label="日期" width="110" />
							<el-table-column label="类型" width="100">
								<template #default="{ row }">{{ row.category === 'PURCHASE_REFUND' ? '退款' : '付款' }}</template>
							</el-table-column>
							<el-table-column label="金额" width="120" align="right">
								<template #default="{ row }">
									<MoneyText :value="row.amount" :currency="row.currency" :direction="row.category === 'PURCHASE_REFUND' ? 'IN' : 'OUT'" />
								</template>
							</el-table-column>
							<el-table-column prop="method" label="方式" width="90" />
							<el-table-column label="状态" width="86" align="center">
								<template #default="{ row }"><StatusTag kind="cash" :value="row.status" /></template>
							</el-table-column>
						</el-table>
					</el-tab-pane>

					<el-tab-pane label="附件" name="attachments">
						<AttachmentPanel owner-type="PURCHASE" :owner-id="purchase.id" title="采购单附件" financial />
					</el-tab-pane>
				</el-tabs>
			</template>
		</div>

		<!-- 收货 -->
		<el-dialog v-model="receiptVisible" title="登记收货" :width="dialogWidth" append-to-body :close-on-click-modal="false" class="mold-dialog">
			<el-alert v-if="dialogError" type="error" :closable="false" show-icon :title="dialogError" class="mold-dialog-alert" />
			<el-form label-position="top" @submit.prevent="submitReceipt">
				<el-form-item label="收货日期">
					<el-date-picker v-model="receiptForm.receivedOn" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
				</el-form-item>
				<el-form-item label="收货数量">
					<el-table :data="receiptForm.items" size="small" border>
						<el-table-column prop="name" label="采购行" min-width="140" show-overflow-tooltip />
						<el-table-column label="已收 / 采购" width="110" align="right">
							<template #default="{ row }">{{ row.received }} / {{ row.qty }}</template>
						</el-table-column>
						<el-table-column label="本次收货" width="120">
							<template #default="{ row }">
								<el-input v-model="row.qtyReceived" size="small" inputmode="decimal" :disabled="row.received >= Number(row.qty)" />
							</template>
						</el-table-column>
					</el-table>
				</el-form-item>
				<el-form-item label="备注">
					<el-input v-model="receiptForm.note" type="textarea" :rows="2" maxlength="2000" />
				</el-form-item>
				<div class="mold-dialog-actions">
					<el-button @click="receiptVisible = false">取消</el-button>
					<el-button type="primary" native-type="submit" :loading="dialogSubmitting">确认收货</el-button>
				</div>
			</el-form>
		</el-dialog>

		<!-- 发票 -->
		<el-dialog v-model="invoiceVisible" title="登记发票" :width="dialogWidth" append-to-body :close-on-click-modal="false" class="mold-dialog">
			<el-alert v-if="dialogError" type="error" :closable="false" show-icon :title="dialogError" class="mold-dialog-alert" />
			<el-form label-position="top" @submit.prevent="submitInvoice">
				<div class="mold-form-grid">
					<el-form-item label="发票号">
						<el-input v-model="invoiceForm.invoiceNo" maxlength="64" />
					</el-form-item>
					<el-form-item label="发票类型">
						<el-select v-model="invoiceForm.invoiceType" style="width: 100%">
							<el-option label="增值税专用发票" value="VAT_SPECIAL" />
							<el-option label="增值税普通发票" value="VAT_NORMAL" />
							<el-option label="其他" value="OTHER" />
						</el-select>
					</el-form-item>
					<el-form-item label="开票日期">
						<el-date-picker v-model="invoiceForm.issueDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
					</el-form-item>
					<el-form-item label="价税合计">
						<el-input v-model="invoiceForm.amountTotal" inputmode="decimal" />
					</el-form-item>
					<el-form-item label="税额（可选）">
						<el-input v-model="invoiceForm.taxAmount" inputmode="decimal" />
					</el-form-item>
				</div>
				<div class="mold-dialog-actions">
					<el-button @click="invoiceVisible = false">取消</el-button>
					<el-button type="primary" native-type="submit" :loading="dialogSubmitting">确认登记</el-button>
				</div>
			</el-form>
		</el-dialog>

		<!-- 付款 / 退款 -->
		<el-dialog
			v-model="paymentVisible"
			:title="paymentForm.category === 'PURCHASE_REFUND' ? '登记退款' : '登记付款'"
			:width="dialogWidth"
			append-to-body
			:close-on-click-modal="false"
			class="mold-dialog"
		>
			<el-alert v-if="dialogError" type="error" :closable="false" show-icon :title="dialogError" class="mold-dialog-alert" />
			<el-form label-position="top" @submit.prevent="submitPayment">
				<div class="mold-form-grid">
					<el-form-item label="发生日期">
						<el-date-picker v-model="paymentForm.occurredOn" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
					</el-form-item>
					<el-form-item label="金额">
						<el-input v-model="paymentForm.amount" inputmode="decimal" />
					</el-form-item>
					<el-form-item label="方式">
						<el-select v-model="paymentForm.method" style="width: 100%">
							<el-option v-for="m in METHODS" :key="m.value" :label="m.label" :value="m.value" />
						</el-select>
					</el-form-item>
					<el-form-item label="凭证号">
						<el-input v-model="paymentForm.voucherNo" maxlength="64" />
					</el-form-item>
				</div>
				<el-form-item label="备注">
					<el-input v-model="paymentForm.note" type="textarea" :rows="2" maxlength="2000" />
				</el-form-item>
				<p class="mold-form-hint">
					币种固定 {{ purchase?.currency }}；付款净额不得超过采购金额（服务端 422 OVER_PAYMENT）。
				</p>
				<div class="mold-dialog-actions">
					<el-button @click="paymentVisible = false">取消</el-button>
					<el-button type="primary" native-type="submit" :loading="dialogSubmitting">确认</el-button>
				</div>
			</el-form>
		</el-dialog>

		<!-- 作废原因 -->
		<el-dialog v-model="voidVisible" :title="voidTitle" :width="dialogWidth" append-to-body :close-on-click-modal="false" class="mold-dialog">
			<el-alert v-if="dialogError" type="error" :closable="false" show-icon :title="dialogError" class="mold-dialog-alert" />
			<el-form label-position="top" @submit.prevent="submitVoid">
				<el-form-item label="作废原因（必填）">
					<el-input v-model="voidReason" type="textarea" :rows="3" maxlength="2000" show-word-limit placeholder="写清原因，作废后保留原记录与审计" />
				</el-form-item>
				<div class="mold-dialog-actions">
					<el-button @click="voidVisible = false">取消</el-button>
					<el-button type="danger" native-type="submit" :loading="dialogSubmitting" :disabled="!voidReason.trim()">确认作废</el-button>
				</div>
			</el-form>
		</el-dialog>
	</el-drawer>
</template>

<script setup lang="ts" name="MoldPurchaseDetailDrawer">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import MoneyText from '/@/components/mold/MoneyText.vue';
import StatusTag from '/@/components/mold/StatusTag.vue';
import AttachmentPanel from '/@/components/mold/AttachmentPanel.vue';
import { useMoldPermissions } from '/@/api/mold/permissions';
import { describeMoldError } from '/@/api/mold/errors';
import {
	confirmPurchase,
	createInvoice,
	createPurchasePayment,
	createPurchaseRefund,
	createReceipt,
	fetchInvoices,
	fetchPurchase,
	fetchPurchasePayments,
	fetchPurchaseSummary,
	fetchReceipts,
	voidInvoice,
	voidPurchase,
	voidReceipt,
	type MoldInvoiceRow,
	type MoldPurchasePayment,
	type MoldPurchaseRow,
	type MoldPurchaseSummary,
	type MoldReceiptRow,
} from '/@/api/mold/purchases';
import { todayStamp } from '/@/utils/moldFormat';
import { formatDate } from '/@/utils/moldFormat';
import { beginIdempotentAction, finishIdempotentAction, stableHash } from '/@/utils/idempotency';

const props = defineProps<{ modelValue: boolean; purchaseId: string; counterpartyName?: string; projectName?: string }>();
const emit = defineEmits<{
	(e: 'update:modelValue', v: boolean): void;
	(e: 'changed'): void;
	(e: 'edit', row: MoldPurchaseRow): void;
}>();

const { can } = useMoldPermissions();
const canEdit = computed(() => can('mold:purchase:update'));
const canConfirm = computed(() => can('mold:purchase:confirm'));
const canVoid = computed(() => can('mold:purchase:void'));
const canReceipt = computed(() => can('mold:receipt:create'));
const canVoidReceipt = computed(() => can('mold:receipt:void'));
const canInvoice = computed(() => can('mold:invoice:create'));
const canVoidInvoice = computed(() => can('mold:invoice:void'));
const canPay = computed(() => can('mold:cash:create'));

const visible = computed({ get: () => props.modelValue, set: (v: boolean) => emit('update:modelValue', v) });
const drawerSize = computed(() => (window.innerWidth <= 900 ? '100%' : '760px'));
const dialogWidth = computed(() => (window.innerWidth <= 900 ? '94%' : '520px'));

const purchase = ref<MoldPurchaseRow | null>(null);
const summary = ref<MoldPurchaseSummary | null>(null);
const receipts = ref<MoldReceiptRow[]>([]);
const invoices = ref<MoldInvoiceRow[]>([]);
const payments = ref<MoldPurchasePayment[]>([]);
const loading = ref(false);
const acting = ref(false);
const error = ref('');
const tab = ref('items');

const METHODS = [
	{ value: 'BANK', label: '银行转账' },
	{ value: 'ALIPAY', label: '支付宝' },
	{ value: 'WECHAT', label: '微信' },
	{ value: 'CASH', label: '现金' },
	{ value: 'OTHER', label: '其他' },
];

const counterpartyName = computed(() => props.counterpartyName || '采购单详情');
const projectName = computed(() => props.projectName || '');

const receivedByItem = computed(() => {
	const map: Record<string, number> = {};
	for (const receipt of receipts.value) {
		if (receipt.status !== 'ACTIVE') continue;
		for (const item of receipt.items || []) {
			map[item.purchaseItemId] = (map[item.purchaseItemId] || 0) + Number(item.qtyReceived);
		}
	}
	return map;
});

const itemName = (id: string) => (purchase.value?.items || []).find((i) => i.id === id)?.name || id.slice(0, 8);

const allocationText = (value: string) =>
	({ PROJECT: '归属项目', PUBLIC: '公共采购', UNASSIGNED: '未分配' } as Record<string, string>)[value] || value;

const invoiceTypeText = (value: string) =>
	({ VAT_SPECIAL: '专票', VAT_NORMAL: '普票', OTHER: '其他' } as Record<string, string>)[value] || value;

async function loadAll() {
	if (!props.purchaseId) return;
	loading.value = true;
	error.value = '';
	try {
		const detail = await fetchPurchase(props.purchaseId);
		purchase.value = detail;
		const results = await Promise.allSettled([
			fetchPurchaseSummary(props.purchaseId),
			fetchReceipts(props.purchaseId),
			fetchInvoices(props.purchaseId),
			fetchPurchasePayments(props.purchaseId),
		]);
		if (results[0].status === 'fulfilled') summary.value = results[0].value;
		if (results[1].status === 'fulfilled') receipts.value = results[1].value.rows;
		if (results[2].status === 'fulfilled') invoices.value = results[2].value.rows;
		if (results[3].status === 'fulfilled') payments.value = results[3].value.rows;
	} catch (err: any) {
		error.value = describeMoldError(err, '采购单加载失败').message;
	} finally {
		loading.value = false;
	}
}

watch(
	() => [props.modelValue, props.purchaseId],
	([open]) => {
		if (open && props.purchaseId) {
			tab.value = 'items';
			loadAll();
		}
	}
);

/* ------------------------------ 确认 / 作废 ------------------------------ */
async function onConfirm() {
	if (!purchase.value) return;
	acting.value = true;
	const scope = `purchase.confirm:${purchase.value.id}`;
	try {
		await confirmPurchase(purchase.value.id, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success('采购单已确认');
		await loadAll();
		emit('changed');
	} catch (err: any) {
		ElMessage.error(describeMoldError(err, '确认失败').message);
	} finally {
		acting.value = false;
	}
}

const voidVisible = ref(false);
const voidReason = ref('');
const voidTarget = ref<{ kind: 'purchase' | 'receipt' | 'invoice'; id: string } | null>(null);
const dialogError = ref('');
const dialogSubmitting = ref(false);
const voidTitle = computed(() =>
	voidTarget.value?.kind === 'receipt' ? '作废收货单' : voidTarget.value?.kind === 'invoice' ? '作废发票' : '作废采购单'
);

function openVoid() {
	if (!purchase.value) return;
	voidTarget.value = { kind: 'purchase', id: purchase.value.id };
	voidReason.value = '';
	dialogError.value = '';
	voidVisible.value = true;
}
function openVoidReceipt(row: MoldReceiptRow) {
	voidTarget.value = { kind: 'receipt', id: row.id };
	voidReason.value = '';
	dialogError.value = '';
	voidVisible.value = true;
}
function openVoidInvoice(row: MoldInvoiceRow) {
	voidTarget.value = { kind: 'invoice', id: row.id };
	voidReason.value = '';
	dialogError.value = '';
	voidVisible.value = true;
}

async function submitVoid() {
	if (!voidTarget.value || !voidReason.value.trim()) return;
	dialogSubmitting.value = true;
	dialogError.value = '';
	const target = voidTarget.value;
	const reason = voidReason.value.trim();
	const scope = `purchase.void:${target.kind}:${target.id}:${stableHash(reason)}`;
	try {
		const key = beginIdempotentAction(scope);
		if (target.kind === 'purchase') await voidPurchase(target.id, reason, key);
		else if (target.kind === 'receipt') await voidReceipt(target.id, reason, key);
		else await voidInvoice(target.id, reason, key);
		finishIdempotentAction(scope);
		ElMessage.success('已作废');
		voidVisible.value = false;
		await loadAll();
		emit('changed');
	} catch (err: any) {
		dialogError.value = describeMoldError(err, '作废失败').message;
	} finally {
		dialogSubmitting.value = false;
	}
}

/* ------------------------------ 收货 ------------------------------ */
const receiptVisible = ref(false);
const receiptForm = reactive({ receivedOn: todayStamp(), note: '', items: [] as { purchaseItemId: string; name: string; qty: string; received: number; qtyReceived: string }[] });

function openReceipt() {
	if (!purchase.value) return;
	receiptForm.receivedOn = todayStamp();
	receiptForm.note = '';
	receiptForm.items = (purchase.value.items || []).map((item) => ({
		purchaseItemId: item.id as string,
		name: item.name,
		qty: String(item.qty),
		received: receivedByItem.value[item.id as string] || 0,
		qtyReceived: '',
	}));
	dialogError.value = '';
	receiptVisible.value = true;
}

async function submitReceipt() {
	if (!purchase.value) return;
	const items = receiptForm.items
		.filter((i) => i.qtyReceived !== '' && Number(i.qtyReceived) > 0)
		.map((i) => ({ purchaseItemId: i.purchaseItemId, qtyReceived: String(i.qtyReceived) }));
	if (!items.length) {
		dialogError.value = '请至少填写一行收货数量';
		return;
	}
	dialogSubmitting.value = true;
	dialogError.value = '';
	const receiptBody = {
		receivedOn: receiptForm.receivedOn,
		note: receiptForm.note.trim() || null,
		items,
	};
	const scope = `receipt.create:${purchase.value.id}:${stableHash(JSON.stringify(receiptBody))}`;
	try {
		await createReceipt(purchase.value.id, receiptBody, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success('收货已登记');
		receiptVisible.value = false;
		await loadAll();
		emit('changed');
	} catch (err: any) {
		dialogError.value = describeMoldError(err, '登记收货失败').message;
	} finally {
		dialogSubmitting.value = false;
	}
}

/* ------------------------------ 发票 ------------------------------ */
const invoiceVisible = ref(false);
const invoiceForm = reactive({
	invoiceNo: '',
	invoiceType: 'VAT_NORMAL',
	issueDate: todayStamp(),
	amountTotal: '',
	taxAmount: '',
});

function openInvoice() {
	invoiceForm.invoiceNo = '';
	invoiceForm.invoiceType = 'VAT_NORMAL';
	invoiceForm.issueDate = todayStamp();
	invoiceForm.amountTotal = '';
	invoiceForm.taxAmount = '';
	dialogError.value = '';
	invoiceVisible.value = true;
}

async function submitInvoice() {
	if (!purchase.value) return;
	if (!invoiceForm.invoiceNo.trim()) {
		dialogError.value = '请填写发票号';
		return;
	}
	const amount = Number(invoiceForm.amountTotal);
	if (!Number.isFinite(amount) || amount <= 0) {
		dialogError.value = '发票金额必须大于 0';
		return;
	}
	dialogSubmitting.value = true;
	dialogError.value = '';
	const invoiceBody = {
		counterpartyId: purchase.value.counterpartyId,
		invoiceNo: invoiceForm.invoiceNo.trim(),
		invoiceType: invoiceForm.invoiceType as any,
		issueDate: invoiceForm.issueDate,
		amountTotal: invoiceForm.amountTotal,
		taxAmount: invoiceForm.taxAmount.trim() || null,
		currency: purchase.value.currency,
	};
	const scope = `invoice.create:${purchase.value.id}:${stableHash(JSON.stringify(invoiceBody))}`;
	try {
		await createInvoice(purchase.value.id, invoiceBody, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success('发票已登记');
		invoiceVisible.value = false;
		await loadAll();
		emit('changed');
	} catch (err: any) {
		dialogError.value = describeMoldError(err, '登记发票失败').message;
	} finally {
		dialogSubmitting.value = false;
	}
}

/* ------------------------------ 付款 / 退款 ------------------------------ */
const paymentVisible = ref(false);
const paymentForm = reactive({
	category: 'PURCHASE_PAYMENT' as 'PURCHASE_PAYMENT' | 'PURCHASE_REFUND',
	occurredOn: todayStamp(),
	amount: '',
	method: 'BANK',
	voucherNo: '',
	note: '',
});

function openPayment(category: 'PURCHASE_PAYMENT' | 'PURCHASE_REFUND') {
	paymentForm.category = category;
	paymentForm.occurredOn = todayStamp();
	paymentForm.amount = '';
	paymentForm.method = 'BANK';
	paymentForm.voucherNo = '';
	paymentForm.note = '';
	dialogError.value = '';
	paymentVisible.value = true;
}

async function submitPayment() {
	if (!purchase.value) return;
	const amount = Number(paymentForm.amount);
	if (!Number.isFinite(amount) || amount <= 0) {
		dialogError.value = '金额必须大于 0';
		return;
	}
	dialogSubmitting.value = true;
	dialogError.value = '';
	const body = {
		occurredOn: paymentForm.occurredOn,
		amount: paymentForm.amount,
		currency: purchase.value.currency,
		method: paymentForm.method,
		voucherNo: paymentForm.voucherNo.trim() || null,
		note: paymentForm.note.trim() || null,
	};
	const scope = `purchase.payment:${purchase.value.id}:${paymentForm.category}:${stableHash(JSON.stringify(body))}`;
	try {
		const key = beginIdempotentAction(scope);
		if (paymentForm.category === 'PURCHASE_REFUND') await createPurchaseRefund(purchase.value.id, body, key);
		else await createPurchasePayment(purchase.value.id, body, key);
		finishIdempotentAction(scope);
		ElMessage.success(paymentForm.category === 'PURCHASE_REFUND' ? '退款已登记' : '付款已登记');
		paymentVisible.value = false;
		await loadAll();
		emit('changed');
	} catch (err: any) {
		dialogError.value = describeMoldError(err, '登记失败').message;
	} finally {
		dialogSubmitting.value = false;
	}
}

function onClosed() {
	purchase.value = null;
	summary.value = null;
	receipts.value = [];
	invoices.value = [];
	payments.value = [];
	error.value = '';
}
</script>

<style scoped lang="scss">
.mold-pd {
	padding: 16px 18px 24px;
}
.mold-pd-head {
	display: flex;
	flex-wrap: wrap;
	align-items: flex-start;
	justify-content: space-between;
	gap: 10px;
	margin-bottom: 12px;
}
.mold-pd-code {
	font-size: 12px;
	color: var(--el-text-color-secondary);
}
.mold-pd-title h2 {
	margin: 2px 0 4px;
	font-size: 20px;
	line-height: 28px;
	overflow-wrap: anywhere;
}
.mold-pd-tags {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	gap: 8px;
	font-size: 12px;
	color: var(--el-text-color-secondary);
}
.mold-pd-head-actions {
	display: flex;
	flex-wrap: wrap;
	gap: 6px;
	align-items: center;
}
.mold-pd-error {
	margin-bottom: 10px;
	padding: 8px 10px;
	border-radius: 4px;
	background: var(--el-color-danger-light-9);
	color: var(--el-color-danger);
	font-size: 13px;
}
.mold-pd-stats {
	display: grid;
	grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
	gap: 10px;
	margin-bottom: 12px;
}
.mold-pd-stat {
	border: 1px solid var(--el-border-color-lighter);
	border-radius: var(--mold-radius-card);
	padding: 8px 10px;
	background: var(--el-fill-color-lighter);
}
.mold-pd-stat-label {
	font-size: 12px;
	color: var(--el-text-color-secondary);
}
.mold-pd-stat-value {
	margin-top: 2px;
	font-size: 16px;
	font-weight: 600;
}
.mold-pd-stat-tags {
	display: flex;
	flex-wrap: wrap;
	gap: 4px;
	margin-top: 4px;
}
.mold-pd-tabs {
	margin-top: 4px;
}
.mold-pd-tab-head {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	justify-content: space-between;
	gap: 8px;
	margin-bottom: 8px;
}
.mold-pd-kv {
	margin-top: 12px;
}
.mold-pd-chip {
	display: inline-block;
	margin-right: 8px;
	font-size: 12px;
}
.mold-form-grid {
	display: grid;
	grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
	gap: 0 14px;
}
@media (max-width: 900px) {
	.mold-pd {
		padding: 12px;
	}
	.mold-pd-head-actions {
		width: 100%;
	}
}
</style>
