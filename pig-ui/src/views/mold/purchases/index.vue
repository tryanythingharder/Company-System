<template>
	<div class="layout-padding">
		<div class="layout-padding-auto layout-padding-view">
			<div ref="scrollRef" class="page-scroll">
				<div class="mold-page">
					<div class="mold-page-head">
						<div>
							<h1>采购台账</h1>
							<p>
								采购单三段式（草稿 → 确认 → 收货 / 开票 / 付款）；金额由服务端重算，前端只做核对。
								<span v-if="tab === 'purchases' && state !== 'forbidden' && state !== 'error'" class="mold-page-total">
									共 {{ state === 'loading' ? '—' : total }} 张
								</span>
							</p>
						</div>
						<div class="mold-page-head-actions">
							<el-button class="mold-mobile-filter-btn" :icon="Search" @click="filterOpen = !filterOpen">
								{{ filterOpen ? '收起筛选' : '筛选' }}
							</el-button>
							<el-button circle :icon="Refresh" aria-label="刷新采购台账" :loading="loading" @click="reload" />
						</div>
					</div>

					<el-tabs v-model="tab" class="mold-tabs-card" @tab-change="onTabChange">
						<el-tab-pane label="采购单" name="purchases">
							<div class="mold-toolbar">
								<div class="mold-toolbar-left">
									<el-button v-if="canCreatePurchase" type="primary" :icon="Plus" data-testid="purchase-create" @click="openCreate">
										新建采购单
									</el-button>
								</div>
								<div class="mold-toolbar-right mold-inline-note">
									草稿可编辑；确认后仅可改备注，金额/归属变更走作废后重录。
								</div>
							</div>

							<div class="mold-filter-bar" :class="{ 'is-open': filterOpen }">
								<el-form :inline="true" @submit.prevent="onQuery">
									<el-form-item label="单号">
										<el-input v-model="filters.code" placeholder="模糊匹配" clearable style="width: 150px" />
									</el-form-item>
									<el-form-item label="状态">
										<el-select v-model="filters.status" placeholder="全部" clearable style="width: 120px">
											<el-option label="草稿" value="DRAFT" />
											<el-option label="已确认" value="CONFIRMED" />
											<el-option label="已作废" value="VOID" />
										</el-select>
									</el-form-item>
									<el-form-item label="供应商">
										<el-select v-model="filters.counterpartyId" placeholder="全部" clearable filterable style="width: 180px">
											<el-option v-for="c in suppliers" :key="c.id" :label="c.name" :value="c.id" />
										</el-select>
									</el-form-item>
									<el-form-item label="归属">
										<el-select v-model="filters.allocationStatus" placeholder="全部" clearable style="width: 130px">
											<el-option label="归属项目" value="PROJECT" />
											<el-option label="公共采购" value="PUBLIC" />
											<el-option label="未分配" value="UNASSIGNED" />
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
								title="无采购台账查看权限"
								description="当前账号没有 mold:purchase:list 权限。服务端仍会对每个请求重新鉴权。"
								action-text="返回首页"
								@action="goHome"
							/>
							<div v-else-if="state === 'loading'" class="mold-skeleton" aria-busy="true" aria-label="正在加载采购单">
								<div v-for="i in 5" :key="i" class="mold-skel-row">
									<div class="mold-skel-block" style="height: 16px; width: 30%" />
									<div class="mold-skel-block" style="height: 16px; width: 18%" />
									<div class="mold-skel-block" style="height: 16px; width: 22%" />
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
								:title="hasFilters ? '没有符合条件的采购单' : '还没有采购单'"
								:description="hasFilters ? '当前筛选条件下没有采购单，可重置后重试。' : '新建采购单并确认后，就可以登记收货、发票与付款。'"
								:action-text="hasFilters ? '重置筛选' : canCreatePurchase ? '新建采购单' : ''"
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
									@row-click="openDetail"
								>
									<el-table-column prop="code" label="采购单号" min-width="150" sortable="custom" class-name="col-code">
										<template #default="{ row }">
											<span class="cell-label">采购单号</span>
											<div class="cell-value">
												<el-button link type="primary" class="mold-name-link" @click.stop="openDetail(row)">{{ row.code }}</el-button>
											</div>
										</template>
									</el-table-column>
									<el-table-column label="供应商" min-width="150">
										<template #default="{ row }">
											<span class="cell-label">供应商</span>
											<div class="cell-value">{{ counterpartyName(row.counterpartyId) }}</div>
										</template>
									</el-table-column>
									<el-table-column prop="purchaseDate" label="采购日期" width="116" sortable="custom">
										<template #default="{ row }">
											<span class="cell-label">采购日期</span>
											<div class="cell-value">{{ formatDate(row.purchaseDate) }}</div>
										</template>
									</el-table-column>
									<el-table-column label="金额" width="140" align="right" prop="amountTotal" sortable="custom" class-name="col-num">
										<template #default="{ row }">
											<span class="cell-label">金额</span>
											<div class="cell-value"><MoneyText :value="row.amountTotal" :currency="row.currency" /></div>
										</template>
									</el-table-column>
									<el-table-column label="归属" width="116">
										<template #default="{ row }">
											<span class="cell-label">归属</span>
											<div class="cell-value">{{ allocationText(row.allocationStatus) }}</div>
										</template>
									</el-table-column>
									<el-table-column label="进度" width="180">
										<template #default="{ row }">
											<span class="cell-label">进度</span>
											<div class="cell-value mold-tag-row">
												<StatusTag kind="progress" :value="row.receiptStatus || 'NONE'" />
												<StatusTag kind="progress" :value="row.invoiceStatus || 'NONE'" />
												<StatusTag kind="payment" :value="row.paymentStatus || 'UNPAID'" />
											</div>
										</template>
									</el-table-column>
									<el-table-column prop="status" label="状态" width="100" align="center" sortable="custom">
										<template #default="{ row }">
											<span class="cell-label">状态</span>
											<div class="cell-value"><StatusTag kind="purchase" :value="row.status" /></div>
										</template>
									</el-table-column>
									<el-table-column label="操作" width="112" align="right" class-name="col-act">
										<template #default="{ row }">
											<el-button link type="primary" size="small" @click.stop="openDetail(row)">详情</el-button>
											<el-button v-if="canUpdatePurchase && row.status === 'DRAFT'" link type="primary" size="small" @click.stop="openEdit(row)">
												编辑
											</el-button>
										</template>
									</el-table-column>
								</el-table>
							</div>

							<div v-if="state === 'ready'" class="mold-pager">
								<div class="mold-pager-total">共 {{ total }} 张采购单</div>
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
						</el-tab-pane>

						<el-tab-pane label="往来单位" name="counterparties">
							<div class="mold-toolbar">
								<div class="mold-toolbar-left">
									<el-button v-if="canCreateCounterparty" type="primary" :icon="Plus" @click="openCounterpartyCreate">
										新建往来单位
									</el-button>
								</div>
								<div class="mold-toolbar-right mold-inline-note">供应商停用后不能再建采购单；历史单据仍保留。</div>
							</div>

							<StatePanel
								v-if="cpState === 'forbidden'"
								kind="forbidden"
								title="无往来单位查看权限"
								description="当前账号没有 mold:purchase:list 权限。"
								action-text="返回首页"
								@action="goHome"
							/>
							<div v-else-if="cpState === 'loading'" class="mold-skeleton" aria-busy="true" aria-label="正在加载往来单位">
								<div v-for="i in 4" :key="i" class="mold-skel-row">
									<div class="mold-skel-block" style="height: 16px; width: 34%" />
									<div class="mold-skel-block" style="height: 16px; width: 20%" />
								</div>
							</div>
							<StatePanel
								v-else-if="cpState === 'error'"
								kind="error"
								:title="cpError.title"
								:description="cpError.message"
								:request-id="cpError.requestId"
								action-text="重试"
								@action="loadCounterparties"
							/>
							<StatePanel
								v-else-if="cpState === 'empty'"
								kind="empty"
								title="还没有往来单位"
								description="先建供应商，才能建采购单；客户用于收款流水。"
								:action-text="canCreateCounterparty ? '新建往来单位' : ''"
								@action="openCounterpartyCreate"
							/>

							<div v-else class="mold-list">
								<el-table :data="counterparties" border stripe row-key="id" style="width: 100%" :max-height="tableMaxHeight">
									<el-table-column prop="name" label="名称" min-width="180">
										<template #default="{ row }">
											<span class="cell-label">名称</span>
											<div class="cell-value">{{ row.name }}</div>
										</template>
									</el-table-column>
									<el-table-column label="类型" width="110">
										<template #default="{ row }">
											<span class="cell-label">类型</span>
											<div class="cell-value">{{ typeText(row.type) }}</div>
										</template>
									</el-table-column>
									<el-table-column label="联系人" width="140">
										<template #default="{ row }">
											<span class="cell-label">联系人</span>
											<div class="cell-value">{{ row.contactName ?? row.contact ?? '—' }}</div>
										</template>
									</el-table-column>
									<el-table-column label="电话" width="140">
										<template #default="{ row }">
											<span class="cell-label">电话</span>
											<div class="cell-value">{{ row.phone || '—' }}</div>
										</template>
									</el-table-column>
									<el-table-column label="状态" width="100" align="center">
										<template #default="{ row }">
											<span class="cell-label">状态</span>
											<div class="cell-value"><StatusTag kind="counterparty" :value="row.status" /></div>
										</template>
									</el-table-column>
									<el-table-column label="操作" width="90" align="right" class-name="col-act">
										<template #default="{ row }">
											<el-button v-if="canUpdateCounterparty" link type="primary" size="small" @click.stop="openCounterpartyEdit(row)">
												编辑
											</el-button>
										</template>
									</el-table-column>
								</el-table>
							</div>

							<div v-if="cpState === 'ready'" class="mold-pager">
								<div class="mold-pager-total">共 {{ cpTotal }} 个往来单位</div>
								<el-pagination
									class="mold-pager-desktop"
									:current-page="cpPage"
									:page-size="cpPageSize"
									:total="cpTotal"
									layout="prev, pager, next, jumper"
									background
									@current-change="goCounterpartyPage"
								/>
								<div class="mold-pager-mobile">
									<el-button :icon="ArrowLeft" :disabled="cpPage <= 1" @click="goCounterpartyPage(cpPage - 1)">上一页</el-button>
									<span class="mold-pager-info">第 {{ cpPage }} / {{ cpPageCount }} 页 · 共 {{ cpTotal }} 条</span>
									<el-button :disabled="cpPage >= cpPageCount" @click="goCounterpartyPage(cpPage + 1)">
										下一页<el-icon class="el-icon--right"><ArrowRight /></el-icon>
									</el-button>
								</div>
							</div>
						</el-tab-pane>
					</el-tabs>
				</div>
			</div>
		</div>

		<PurchaseFormDialog
			v-model="formVisible"
			:purchase="editing"
			:counterparties="counterparties"
			:projects="projects"
			@saved="onPurchaseSaved"
		/>
		<CounterpartyDialog v-model="cpDialogVisible" :counterparty="editingCounterparty" @saved="onCounterpartySaved" />
		<PurchaseDetailDrawer
			v-model="detailVisible"
			:purchase-id="detailId"
			:counterparty-name="detailCounterpartyName"
			:project-name="detailProjectName"
			@changed="reload"
			@edit="onEditFromDrawer"
		/>
	</div>
</template>

<script setup lang="ts" name="moldPurchases">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { ArrowLeft, ArrowRight, Plus, Refresh, RefreshLeft, Search } from '@element-plus/icons-vue';
import StatePanel from '/@/components/mold/StatePanel.vue';
import StatusTag from '/@/components/mold/StatusTag.vue';
import MoneyText from '/@/components/mold/MoneyText.vue';
import PurchaseFormDialog from './components/PurchaseFormDialog.vue';
import CounterpartyDialog from './components/CounterpartyDialog.vue';
import PurchaseDetailDrawer from './components/PurchaseDetailDrawer.vue';
import { useMoldPermissions } from '/@/api/mold/permissions';
import { fetchProjectPage, MoldApiError, type MoldProjectRow } from '/@/api/mold/projects';
import {
	fetchCounterparties,
	fetchPurchase,
	fetchPurchases,
	type MoldCounterpartyRow,
	type MoldPurchaseRow,
} from '/@/api/mold/purchases';
import { describeMoldError } from '/@/api/mold/errors';
import { debounceRefresh, useMoldEvent } from '/@/api/mold/events';
import { useMoldList } from '/@/utils/moldList';
import { formatDate } from '/@/utils/moldFormat';

const router = useRouter();
const { can, ensureLoaded } = useMoldPermissions();

const canCreatePurchase = computed(() => can('mold:purchase:create'));
const canUpdatePurchase = computed(() => can('mold:purchase:update'));
const canCreateCounterparty = computed(() => can('mold:counterparty:create'));
const canUpdateCounterparty = computed(() => can('mold:counterparty:update'));

const tab = ref<'purchases' | 'counterparties'>('purchases');
const filterOpen = ref(false);
const tableMaxHeight = computed(() => (window.innerWidth <= 900 ? undefined : 'calc(100vh - 340px)'));

const filters = reactive({ code: '', status: '', counterpartyId: '', allocationStatus: '' });

const list = useMoldList<MoldPurchaseRow>({
	fetch: (query) => fetchPurchases(query as any),
	filters: () => ({
		q: filters.code || undefined,
		status: filters.status || undefined,
		counterpartyId: filters.counterpartyId || undefined,
		allocationStatus: filters.allocationStatus || undefined,
	}),
	sortable: ['code', 'purchaseDate', 'amountTotal', 'status'],
	defaultSort: { key: 'purchaseDate', order: 'desc' },
});
const { rows, total, pageNum, pageSize, pageCount, state, loading, errorInfo, errorAction, hasFilters } = list;

const counterparties = ref<MoldCounterpartyRow[]>([]);
const projects = ref<MoldProjectRow[]>([]);

const cpState = ref<'loading' | 'ready' | 'empty' | 'error' | 'forbidden'>('loading');
const cpError = reactive({ title: '加载失败', message: '', requestId: '' });
const cpPage = ref(1);
const cpPageSize = ref(20);
const cpTotal = ref(0);
const cpPageCount = computed(() => Math.max(1, Math.ceil(cpTotal.value / cpPageSize.value)));

const suppliers = computed(() => counterparties.value.filter((c) => c.type === 'SUPPLIER' && c.status === 'ACTIVE'));

const counterpartyMap = computed(() => {
	const map = new Map<string, MoldCounterpartyRow>();
	counterparties.value.forEach((c) => map.set(c.id, c));
	return map;
});
const projectMap = computed(() => {
	const map = new Map<string, MoldProjectRow>();
	projects.value.forEach((p) => map.set(p.id, p));
	return map;
});

const counterpartyName = (id: string) => counterpartyMap.value.get(id)?.name || id.slice(0, 8);
const allocationText = (value: string) =>
	({ PROJECT: '归属项目', PUBLIC: '公共采购', UNASSIGNED: '未分配' } as Record<string, string>)[value] || value || '—';
const typeText = (value: string) => ({ CUSTOMER: '客户', SUPPLIER: '供应商', OTHER: '其他' } as Record<string, string>)[value] || value;

/* ------------------------------ 往来单位 ------------------------------ */
async function loadCounterparties(page = cpPage.value) {
	cpState.value = cpPage.value === page && cpTotal.value === 0 ? 'loading' : cpState.value;
	cpPage.value = page;
	cpError.title = '加载失败';
	cpError.message = '';
	try {
		const res = await fetchCounterparties({ pageNum: cpPage.value, pageSize: cpPageSize.value, sort: 'name', order: 'asc' });
		counterparties.value = res.rows;
		cpTotal.value = res.total;
		cpState.value = res.rows.length ? 'ready' : 'empty';
	} catch (err: any) {
		if (err instanceof MoldApiError && err.status === 403) {
			cpState.value = 'forbidden';
			return;
		}
		const view = describeMoldError(err, '往来单位加载失败');
		cpError.title = view.title;
		cpError.message = view.message;
		cpError.requestId = view.requestId;
		cpState.value = 'error';
	}
}

function goCounterpartyPage(next: number) {
	if (next < 1 || next > cpPageCount.value) return;
	loadCounterparties(next);
}

async function loadProjects() {
	try {
		const page = await fetchProjectPage({ pageNum: 1, pageSize: 100, sort: 'code', order: 'asc' });
		projects.value = page.rows;
	} catch {
		projects.value = [];
	}
}

/* ------------------------------ 筛选 ------------------------------ */
function onQuery() {
	list.query();
}
function onReset() {
	filters.code = '';
	filters.status = '';
	filters.counterpartyId = '';
	filters.allocationStatus = '';
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
	if (tab.value === 'counterparties') loadCounterparties();
}
function onTabChange(name: string | number) {
	if (name === 'counterparties' && cpState.value !== 'ready') loadCounterparties();
}

/* ------------------------------ 采购单弹窗 ------------------------------ */
const formVisible = ref(false);
const editing = ref<MoldPurchaseRow | null>(null);

function openCreate() {
	editing.value = null;
	formVisible.value = true;
}

async function openEdit(row: MoldPurchaseRow) {
	try {
		editing.value = await fetchPurchase(row.id);
		formVisible.value = true;
	} catch (err: any) {
		ElMessage.error(describeMoldError(err, '采购单加载失败').message);
	}
}

function onEditFromDrawer(row: MoldPurchaseRow) {
	detailVisible.value = false;
	editing.value = row;
	formVisible.value = true;
}

function onPurchaseSaved() {
	list.reload();
	loadCounterparties();
}

/* ------------------------------ 往来单位弹窗 ------------------------------ */
const cpDialogVisible = ref(false);
const editingCounterparty = ref<MoldCounterpartyRow | null>(null);

function openCounterpartyCreate() {
	editingCounterparty.value = null;
	cpDialogVisible.value = true;
}
function openCounterpartyEdit(row: MoldCounterpartyRow) {
	editingCounterparty.value = row;
	cpDialogVisible.value = true;
}
function onCounterpartySaved() {
	loadCounterparties();
	list.reload();
}

/* ------------------------------ 详情抽屉 ------------------------------ */
const detailVisible = ref(false);
const detailId = ref('');
const detailCounterpartyName = computed(() => {
	const row = rows.value.find((r) => r.id === detailId.value);
	return row ? counterpartyName(row.counterpartyId) : '';
});
const detailProjectName = computed(() => {
	const row = rows.value.find((r) => r.id === detailId.value);
	if (!row?.projectId) return '';
	return projectMap.value.get(row.projectId)?.name || '';
});

function openDetail(row: MoldPurchaseRow) {
	detailId.value = row.id;
	detailVisible.value = true;
}

function goHome() {
	router.push('/home');
}

onMounted(() => {
	ensureLoaded();
	list.load(true);
	loadCounterparties(1);
	loadProjects();
});

/* SSE：采购/收货/发票/往来变更只刷本页列表 */
const refreshPurchaseOnEvent = debounceRefresh(() => {
	list.reload();
	loadCounterparties(1);
}, 700);
useMoldEvent(['purchase.created', 'purchase.confirmed', 'purchase.voided', 'receipt.created', 'receipt.voided', 'invoice.created', 'invoice.voided', 'counterparty.created', 'counterparty.updated'], refreshPurchaseOnEvent);
</script>

<style scoped lang="scss">
.mold-toolbar-left {
	display: flex;
	flex-wrap: wrap;
	gap: 8px;
}
.mold-tag-row {
	display: flex;
	flex-wrap: wrap;
	gap: 4px;
}
.mold-name-link {
	padding: 0;
	height: auto;
	font-weight: 500;
}
@media (max-width: 900px) {
	.mold-tag-row {
		justify-content: flex-end;
	}
}
</style>
