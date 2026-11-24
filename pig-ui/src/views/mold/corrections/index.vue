<template>
	<div class="layout-padding">
		<div class="layout-padding-auto layout-padding-view">
			<div ref="scrollRef" class="page-scroll">
				<div class="mold-page">
					<div class="mold-page-head">
						<div>
							<h1>更正记录</h1>
							<p>
								录错不物理改：作废原流水并重录（替换）或生成反向流水；每次更正先预览影响，再提交。
							</p>
						</div>
						<div class="mold-page-head-actions">
							<el-button :icon="Refresh" :loading="chainLoading" @click="reloadChain">刷新</el-button>
						</div>
					</div>

					<StatePanel
						v-if="forbidden"
						kind="forbidden"
						title="无更正权限"
						description="当前账号没有更正流水的权限。更正由财务或老板操作。"
						action-text="返回财务收支"
						@action="goCash"
					/>

					<div v-else class="mold-corr-layout">
						<!-- 左：选择流水 -->
						<aside class="mold-corr-picker">
							<div class="mold-card">
								<div class="mold-card-title">
									<span>1. 选择要更正的流水</span>
									<span class="mold-card-note">仅已确认</span>
								</div>
								<div class="mold-corr-filters">
									<el-select v-model="pickFilters.direction" placeholder="方向" clearable size="small" style="width: 96px" @change="loadEntries(1)">
										<el-option label="收入" value="IN" />
										<el-option label="支出" value="OUT" />
									</el-select>
									<el-select v-model="pickFilters.category" placeholder="类别" clearable size="small" style="width: 140px" @change="loadEntries(1)">
										<el-option v-for="(label, value) in CATEGORY_LABEL" :key="value" :label="label" :value="value" />
									</el-select>
									<el-button size="small" :icon="Refresh" :loading="entriesLoading" @click="loadEntries(entryPage)">刷新</el-button>
								</div>

								<div v-if="entriesError" class="mold-corr-error" role="alert">{{ entriesError }}</div>
								<div v-if="entriesLoading" class="mold-skeleton" aria-busy="true">
									<div v-for="i in 4" :key="i" class="mold-skel-block" style="height: 52px; width: 100%; margin-bottom: 6px" />
								</div>
								<el-empty v-else-if="!entries.length" description="没有可更正的已确认流水" :image-size="64" />
								<ul v-else class="mold-corr-list">
									<li v-for="e in entries" :key="e.id">
										<button
											type="button"
											class="mold-corr-item"
											:class="{ 'is-active': e.id === selectedId }"
											:aria-pressed="e.id === selectedId"
											@click="selectEntry(e.id)"
										>
											<span class="mold-corr-item-main">
												<span class="mold-corr-item-code">{{ e.code }}</span>
												<span class="mold-corr-item-meta">
													{{ formatDate(e.occurredOn) }} · {{ CATEGORY_LABEL[e.category] || e.category }}
													<span v-if="e.reconciledAt" class="mold-corr-reconciled">已对账</span>
												</span>
											</span>
											<MoneyText :value="e.amount" :currency="e.currency" :direction="e.direction" />
										</button>
									</li>
								</ul>

								<div class="mold-pager mold-pager-compact">
									<el-button size="small" :icon="ArrowLeft" :disabled="entryPage <= 1" @click="loadEntries(entryPage - 1)">上一页</el-button>
									<span class="mold-pager-info">第 {{ entryPage }} / {{ entryPageCount }} 页 · 共 {{ entryTotal }} 条</span>
									<el-button size="small" :disabled="entryPage >= entryPageCount" @click="loadEntries(entryPage + 1)">
										下一页<el-icon class="el-icon--right"><ArrowRight /></el-icon>
									</el-button>
								</div>
							</div>
						</aside>

						<!-- 右：预览与提交 -->
						<section class="mold-corr-main">
							<StatePanel
								v-if="!selectedId"
								kind="empty"
								title="先选择一条流水"
								description="选中左侧流水后，可填写更正原因并预览影响；也可以从「财务收支」行操作直接进入。"
							/>
							<template v-else>
								<div v-if="detailLoading" class="mold-skeleton" aria-busy="true">
									<div class="mold-skel-block" style="height: 96px; width: 100%; margin-bottom: 12px" />
									<div class="mold-skel-block" style="height: 220px; width: 100%" />
								</div>
								<StatePanel
									v-else-if="detailError"
									kind="error"
									title="流水加载失败"
									:description="detailError"
									action-text="重试"
									@action="loadDetail"
								/>
								<template v-else-if="detail">
									<div class="mold-card mold-corr-summary">
										<div class="mold-card-title">
											<span>2. 当前流水</span>
											<span class="mold-card-note">v{{ detail.version }} · {{ detail.code }}</span>
										</div>
										<dl class="mold-kv mold-kv-tight">
											<div><dt>类别 / 方向</dt><dd>{{ CATEGORY_LABEL[detail.category] || detail.category }} · {{ detail.direction === 'IN' ? '收入' : '支出' }}</dd></div>
											<div><dt>发生日期</dt><dd>{{ formatDate(detail.occurredOn) }}</dd></div>
											<div><dt>金额</dt><dd><MoneyText :value="detail.amount" :currency="detail.currency" :direction="detail.direction" /></dd></div>
											<div><dt>状态</dt><dd><StatusTag kind="cash" :value="detail.status" /> <StatusTag kind="reconcile" :value="!!detail.reconciledAt" /></dd></div>
											<div><dt>方式 / 凭证</dt><dd>{{ METHOD_LABEL[detail.method] || detail.method }} · {{ detail.voucherNo || '—' }}</dd></div>
											<div><dt>备注</dt><dd>{{ detail.note || '—' }}</dd></div>
										</dl>
									</div>

									<el-alert
										v-if="detail.status !== 'CONFIRMED'"
										type="info"
										:closable="false"
										show-icon
										title="该流水已作废，不能再次更正"
										class="mold-dialog-alert"
									/>
									<el-alert
										v-else-if="detail.reconciledAt"
										type="warning"
										:closable="false"
										show-icon
										title="该流水已对账"
										description="更正前需先在财务收支页取消对账，服务端会拒绝已对账流水的更正。"
										class="mold-dialog-alert"
									/>

									<div class="mold-card">
										<div class="mold-card-title">
											<span>3. 更正方式</span>
											<span class="mold-card-note">留空替换字段 = 仅作废重录</span>
										</div>
										<el-form label-position="top" @submit.prevent="submit">
											<el-form-item label="更正原因（必填）">
												<el-input v-model="form.reason" type="textarea" :rows="2" maxlength="2000" show-word-limit placeholder="写清为什么更正，会写入审计与更正链" />
											</el-form-item>
											<el-checkbox v-model="form.replace" :disabled="!canApply">替换为新流水（金额 / 日期 / 方式可改）</el-checkbox>
											<div v-if="form.replace" class="mold-form-grid mold-corr-replace">
												<el-form-item label="新金额">
													<el-input v-model="form.amount" inputmode="decimal" placeholder="留空 = 沿用原金额" />
												</el-form-item>
												<el-form-item label="新发生日期">
													<el-date-picker v-model="form.occurredOn" type="date" value-format="YYYY-MM-DD" style="width: 100%" placeholder="留空 = 沿用原日期" />
												</el-form-item>
												<el-form-item label="新方式">
													<el-select v-model="form.method" clearable placeholder="留空 = 沿用" style="width: 100%">
														<el-option v-for="(label, value) in METHOD_LABEL" :key="value" :label="label" :value="value" />
													</el-select>
												</el-form-item>
												<el-form-item label="新凭证号">
													<el-input v-model="form.voucherNo" maxlength="64" placeholder="留空 = 沿用" />
												</el-form-item>
												<el-form-item label="新备注">
													<el-input v-model="form.note" maxlength="2000" placeholder="留空 = 沿用" />
												</el-form-item>
											</div>
											<div class="mold-dialog-actions">
												<el-button :loading="previewLoading" :disabled="!form.reason.trim() || detail.status !== 'CONFIRMED' || !!detail.reconciledAt" @click="runPreview">
													预览影响
												</el-button>
												<el-button
													type="primary"
													native-type="submit"
													:loading="applying"
													:disabled="!preview || !canApply || detail.status !== 'CONFIRMED' || !!detail.reconciledAt"
												>
													提交更正
												</el-button>
											</div>
										</el-form>
										<p v-if="!canApply" class="mold-form-hint">当前账号只有预览权限（mold:correction:preview），提交需要 mold:correction:apply。</p>
									</div>

									<div v-if="actionError" class="mold-corr-error" role="alert">{{ actionError }}</div>

									<!-- 预览影响 -->
									<div v-if="preview" class="mold-card mold-corr-preview">
										<div class="mold-card-title">
											<span>4. 影响预览</span>
											<span class="mold-card-note">{{ preview.impact.mode === 'REPLACE' ? '替换' : '作废' }} · 未提交</span>
										</div>
										<dl class="mold-kv mold-kv-tight">
											<div><dt>金额变化</dt><dd>{{ preview.impact.amountBefore }} → {{ preview.impact.amountAfter ?? '作废' }}</dd></div>
											<div><dt>日期变化</dt><dd>{{ formatDate(preview.impact.dateBefore) }} → {{ preview.impact.dateAfter ? formatDate(preview.impact.dateAfter) : '作废' }}</dd></div>
											<div><dt>合计金额差</dt><dd><MoneyText :value="preview.impact.totalAmountDelta" :currency="preview.impact.currency" /></dd></div>
											<div><dt>合计净额差</dt><dd><MoneyText :value="preview.impact.totalNetDelta" :currency="preview.impact.currency" /></dd></div>
										</dl>
										<el-table :data="preview.impact.periodDeltas" size="small" border class="mold-corr-periods">
											<el-table-column prop="period" label="期间" width="100" />
											<el-table-column prop="currency" label="币种" width="80" />
											<el-table-column label="金额差" width="130" align="right">
												<template #default="{ row }"><MoneyText :value="row.amountDelta" :currency="row.currency" /></template>
											</el-table-column>
											<el-table-column label="净额差" width="130" align="right">
												<template #default="{ row }"><MoneyText :value="row.netDelta" :currency="row.currency" /></template>
											</el-table-column>
										</el-table>
										<el-alert
											v-if="preview.purchase?.overPayment"
											type="warning"
											:closable="false"
											show-icon
											title="更正后该采购单将超付"
											description="服务端可能拒绝提交；请先处理采购付款或改为不替换金额。"
											class="mold-corr-purchase-alert"
										/>
										<p v-if="preview.purchase" class="mold-inline-note">
											关联采购：已付净额 {{ preview.purchase.newPaidNet }} / 采购额 {{ preview.purchase.amountTotal }}
										</p>
									</div>

									<!-- 更正链 -->
									<div class="mold-card">
										<div class="mold-card-title">
											<span>更正链</span>
											<span class="mold-card-note">{{ detail.correctionChain?.truncated ? '链路过长，仅显示相邻节点' : `${detail.correctionChain?.nodes?.length || 0} 个节点` }}</span>
										</div>
										<ol v-if="detail.correctionChain?.nodes?.length" class="mold-chain">
											<li v-for="node in detail.correctionChain.nodes" :key="node.id" class="mold-chain-item" :class="{ 'is-current': node.id === detail.id }">
												<div class="mold-chain-head">
													<span class="mold-chain-code">{{ node.code }}</span>
													<StatusTag kind="cash" :value="node.status" />
													<span v-if="node.id === detail.id" class="mold-chain-current">当前</span>
												</div>
												<div class="mold-chain-meta">
													{{ formatDate(node.occurredOn) }} ·
													<MoneyText :value="node.amount" :currency="node.currency" :direction="node.direction" />
													<span v-if="node.voidedAt"> · 作废于 {{ formatDateTime(node.voidedAt) }}</span>
													<span v-if="node.voidReason"> · {{ node.voidReason }}</span>
												</div>
											</li>
										</ol>
										<p v-else class="mold-inline-note">没有更正记录，这是原始流水。</p>
									</div>
								</template>
							</template>
						</section>
					</div>
				</div>
			</div>
		</div>
	</div>
</template>

<script setup lang="ts" name="moldCorrections">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { ArrowLeft, ArrowRight, Refresh } from '@element-plus/icons-vue';
import StatePanel from '/@/components/mold/StatePanel.vue';
import StatusTag from '/@/components/mold/StatusTag.vue';
import MoneyText from '/@/components/mold/MoneyText.vue';
import { useMoldPermissions } from '/@/api/mold/permissions';
import { MoldApiError } from '/@/api/mold/projects';
import {
	applyCorrection,
	CATEGORY_LABEL,
	fetchCashEntries,
	fetchCashEntry,
	METHOD_LABEL,
	previewCorrection,
	type MoldCashRow,
	type MoldCorrectionPreview,
} from '/@/api/mold/cash';
import { describeMoldError } from '/@/api/mold/errors';
import { debounceRefresh, useMoldEvent } from '/@/api/mold/events';
import { formatDate, formatDateTime } from '/@/utils/moldFormat';
import { beginIdempotentAction, finishIdempotentAction, stableHash } from '/@/utils/idempotency';

const route = useRoute();
const router = useRouter();
const { can, ensureLoaded } = useMoldPermissions();
const canApply = computed(() => can('mold:correction:apply'));

const forbidden = ref(false);
const pickFilters = reactive({ direction: '', category: '' });
const entries = ref<MoldCashRow[]>([]);
const entriesLoading = ref(false);
const entriesError = ref('');
const entryPage = ref(1);
const entryPageSize = ref(10);
const entryTotal = ref(0);
const entryPageCount = computed(() => Math.max(1, Math.ceil(entryTotal.value / entryPageSize.value)));

const selectedId = ref(String(route.query.entryId || ''));
const detail = ref<MoldCashRow | null>(null);
const detailLoading = ref(false);
const detailError = ref('');
const chainLoading = computed(() => detailLoading.value);

const form = reactive({
	reason: '',
	replace: false,
	amount: '',
	occurredOn: '',
	method: '',
	voucherNo: '',
	note: '',
});

const preview = ref<MoldCorrectionPreview | null>(null);
const previewLoading = ref(false);
const applying = ref(false);
const actionError = ref('');

async function loadEntries(page = 1) {
	entriesLoading.value = true;
	entriesError.value = '';
	entryPage.value = page;
	try {
		const res = await fetchCashEntries({
			pageNum: page,
			pageSize: entryPageSize.value,
			status: 'CONFIRMED',
			direction: pickFilters.direction || undefined,
			category: pickFilters.category || undefined,
			sort: 'occurredOn',
			order: 'desc',
		});
		entries.value = res.rows;
		entryTotal.value = res.total;
	} catch (err: any) {
		if (err instanceof MoldApiError && err.status === 403) {
			forbidden.value = true;
			return;
		}
		entriesError.value = describeMoldError(err, '流水列表加载失败').message;
	} finally {
		entriesLoading.value = false;
	}
}

async function loadDetail() {
	if (!selectedId.value) return;
	detailLoading.value = true;
	detailError.value = '';
	try {
		detail.value = await fetchCashEntry(selectedId.value);
	} catch (err: any) {
		if (err instanceof MoldApiError && err.status === 403) {
			forbidden.value = true;
			return;
		}
		detailError.value = describeMoldError(err, '流水加载失败').message;
		detail.value = null;
	} finally {
		detailLoading.value = false;
	}
}

function selectEntry(id: string) {
	if (id === selectedId.value) return;
	selectedId.value = id;
	resetForm();
	router.replace({ path: '/mold/corrections', query: { entryId: id } });
	loadDetail();
}

function resetForm() {
	form.reason = '';
	form.replace = false;
	form.amount = '';
	form.occurredOn = '';
	form.method = '';
	form.voucherNo = '';
	form.note = '';
	preview.value = null;
	actionError.value = '';
}

function buildBody() {
	return {
		reason: form.reason.trim(),
		replacement: form.replace
			? {
					amount: form.amount.trim() || undefined,
					occurredOn: form.occurredOn || undefined,
					method: (form.method as any) || undefined,
					voucherNo: form.voucherNo.trim() || undefined,
					note: form.note.trim() || undefined,
				}
			: null,
	};
}

async function runPreview() {
	if (!detail.value || !form.reason.trim()) {
		actionError.value = '请先填写更正原因';
		return;
	}
	previewLoading.value = true;
	actionError.value = '';
	try {
		preview.value = await previewCorrection(detail.value.id, buildBody(), detail.value.version);
	} catch (err: any) {
		preview.value = null;
		actionError.value = describeMoldError(err, '预览失败').message;
	} finally {
		previewLoading.value = false;
	}
}

async function submit() {
	if (!detail.value || !preview.value || !canApply.value) return;
	applying.value = true;
	actionError.value = '';
	const correctionBody = buildBody();
	const scope = `cash.correct:${detail.value.id}:${detail.value.version}:${stableHash(JSON.stringify(correctionBody))}`;
	try {
		const updated = await applyCorrection(detail.value.id, correctionBody, detail.value.version, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success('更正已提交');
		preview.value = null;
		selectedId.value = updated.id;
		router.replace({ path: '/mold/corrections', query: { entryId: updated.id } });
		await Promise.all([loadDetail(), loadEntries(entryPage.value)]);
	} catch (err: any) {
		actionError.value = describeMoldError(err, '提交更正失败').message;
	} finally {
		applying.value = false;
	}
}

function reloadChain() {
	loadDetail();
	loadEntries(entryPage.value);
}

function goCash() {
	router.push('/mold/cash');
}

watch(
	() => route.query.entryId,
	(id) => {
		const next = String(id || '');
		if (next && next !== selectedId.value) {
			selectedId.value = next;
			resetForm();
			loadDetail();
		}
	}
);

onMounted(async () => {
	await ensureLoaded();
	if (!can('mold:correction:preview')) {
		forbidden.value = true;
		return;
	}
	await loadEntries(1);
	if (selectedId.value) await loadDetail();
});

/* SSE：流水变更只刷左侧可选列表与当前详情 */
const refreshCorrectionOnEvent = debounceRefresh(() => {
	loadEntries(entryPage.value);
	if (selectedId.value) loadDetail();
}, 800);
useMoldEvent(['cash_entry.created', 'cash_entry.updated', 'cash_entry.voided', 'cash_entry.reversed', 'cash_entry.reconciled', 'cash_entry.unreconciled', 'cash_entry.corrected'], refreshCorrectionOnEvent);
</script>

<style scoped lang="scss">
.mold-corr-layout {
	display: grid;
	grid-template-columns: 340px minmax(0, 1fr);
	gap: 16px;
	align-items: start;
}
.mold-corr-picker {
	min-width: 0;
	position: sticky;
	top: 8px;
}
.mold-corr-filters {
	display: flex;
	flex-wrap: wrap;
	gap: 6px;
	margin-bottom: 8px;
}
.mold-corr-list {
	list-style: none;
	margin: 0;
	padding: 0;
	max-height: 46vh;
	overflow-y: auto;
}
.mold-corr-item {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 8px;
	width: 100%;
	padding: 8px 10px;
	margin-bottom: 4px;
	border: 1px solid var(--el-border-color-lighter);
	border-radius: 6px;
	background: var(--el-bg-color);
	text-align: left;
	cursor: pointer;
	color: inherit;
	font: inherit;
	&:hover {
		border-color: var(--el-color-primary-light-5);
	}
	&.is-active {
		border-color: var(--el-color-primary);
		background: var(--el-color-primary-light-9);
	}
	&:focus-visible {
		outline: 2px solid var(--el-color-primary);
		outline-offset: 2px;
	}
}
.mold-corr-item-main {
	min-width: 0;
	display: flex;
	flex-direction: column;
	gap: 2px;
}
.mold-corr-item-code {
	font-weight: 600;
	font-size: 13px;
	overflow-wrap: anywhere;
}
.mold-corr-item-meta {
	font-size: 11px;
	color: var(--el-text-color-secondary);
}
.mold-corr-reconciled {
	margin-left: 4px;
	color: var(--el-color-warning);
}
.mold-corr-main {
	min-width: 0;
	display: flex;
	flex-direction: column;
	gap: 12px;
}
.mold-corr-summary,
.mold-corr-preview {
	margin-bottom: 0;
}
.mold-form-grid {
	display: grid;
	grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
	gap: 0 14px;
}
.mold-corr-replace {
	margin-top: 8px;
}
.mold-corr-periods {
	margin-top: 10px;
}
.mold-corr-purchase-alert {
	margin-top: 10px;
}
.mold-corr-error {
	padding: 8px 10px;
	border-radius: 4px;
	background: var(--el-color-danger-light-9);
	color: var(--el-color-danger);
	font-size: 13px;
}
.mold-pager-compact {
	margin-top: 8px;
	justify-content: space-between;
}
.mold-chain {
	list-style: none;
	margin: 0;
	padding: 0;
}
.mold-chain-item {
	padding: 8px 10px;
	border-left: 3px solid var(--el-border-color);
	margin-bottom: 6px;
	background: var(--el-fill-color-lighter);
	border-radius: 0 4px 4px 0;
	&.is-current {
		border-left-color: var(--el-color-primary);
	}
}
.mold-chain-head {
	display: flex;
	align-items: center;
	gap: 8px;
}
.mold-chain-code {
	font-weight: 600;
	font-size: 13px;
}
.mold-chain-current {
	font-size: 11px;
	color: var(--el-color-primary);
}
.mold-chain-meta {
	margin-top: 2px;
	font-size: 12px;
	color: var(--el-text-color-secondary);
	overflow-wrap: anywhere;
}
@media (max-width: 900px) {
	.mold-corr-layout {
		grid-template-columns: minmax(0, 1fr);
	}
	.mold-corr-picker {
		position: static;
	}
	.mold-corr-list {
		max-height: 280px;
	}
}
</style>
