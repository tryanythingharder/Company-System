<template>
	<div class="layout-padding">
		<div class="layout-padding-auto layout-padding-view">
			<div ref="scrollRef" class="page-scroll">
				<div class="mold-page">
					<div class="mold-page-head">
						<div>
							<h1>财务收支</h1>
							<p>
								收支流水与报表；录错不改原单，作废后重录或用「更正记录」替换。已对账流水不可编辑。
								<span v-if="tab === 'entries' && state !== 'forbidden' && state !== 'error'" class="mold-page-total">
									共 {{ state === 'loading' ? '—' : total }} 条
								</span>
							</p>
						</div>
						<div class="mold-page-head-actions">
							<el-button class="mold-mobile-filter-btn" :icon="Search" @click="filterOpen = !filterOpen">
								{{ filterOpen ? '收起筛选' : '筛选' }}
							</el-button>
							<el-button circle :icon="Refresh" aria-label="刷新收支流水" :loading="loading" @click="reload" />
						</div>
					</div>

					<el-tabs v-model="tab" class="mold-tabs-card" @tab-change="onTabChange">
						<el-tab-pane label="收支流水" name="entries">
							<div class="mold-toolbar">
								<div class="mold-toolbar-left">
									<el-button v-if="canCreate" type="primary" :icon="Plus" data-testid="cash-create" @click="formVisible = true">
										新建流水
									</el-button>
									<el-button v-if="canViewAll" :icon="Document" @click="goCorrections()">更正记录</el-button>
								</div>
								<div class="mold-toolbar-right mold-inline-note">
									本页记录客户收付与日常/其他收支；采购类历史流水保留可查，新增支出直接记「日常支出」。
								</div>
							</div>

							<div class="mold-filter-bar" :class="{ 'is-open': filterOpen }">
								<el-form :inline="true" @submit.prevent="onQuery">
									<el-form-item label="方向">
										<el-select v-model="filters.direction" placeholder="全部" clearable style="width: 110px">
											<el-option label="收入" value="IN" />
											<el-option label="支出" value="OUT" />
										</el-select>
									</el-form-item>
									<el-form-item label="类别">
										<el-select v-model="filters.category" placeholder="全部" clearable style="width: 150px">
											<el-option v-for="(label, value) in CATEGORY_LABEL" :key="value" :label="label" :value="value" />
										</el-select>
									</el-form-item>
									<el-form-item label="状态">
										<el-select v-model="filters.status" placeholder="全部" clearable style="width: 120px">
											<el-option label="已确认" value="CONFIRMED" />
											<el-option label="已作废" value="VOID" />
										</el-select>
									</el-form-item>
									<el-form-item label="归属">
										<el-select v-model="filters.allocationStatus" placeholder="全部" clearable style="width: 130px">
											<el-option v-for="(label, value) in ALLOCATION_LABEL" :key="value" :label="label" :value="value" />
										</el-select>
									</el-form-item>
									<el-form-item label="项目">
										<el-select v-model="filters.projectId" placeholder="全部" clearable filterable style="width: 200px">
											<el-option v-for="p in projects" :key="p.id" :label="`${p.code} · ${p.name}`" :value="p.id" />
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
								title="无财务收支查看权限"
								description="当前账号没有 mold:cash:list 权限。服务端仍会对每个请求重新鉴权。"
								action-text="返回首页"
								@action="goHome"
							/>
							<div v-else-if="state === 'loading'" class="mold-skeleton" aria-busy="true" aria-label="正在加载收支流水">
								<div v-for="i in 5" :key="i" class="mold-skel-row">
									<div class="mold-skel-block" style="height: 16px; width: 26%" />
									<div class="mold-skel-block" style="height: 16px; width: 20%" />
									<div class="mold-skel-block" style="height: 16px; width: 24%" />
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
								:title="hasFilters ? '没有符合条件的流水' : '还没有收支流水'"
								:description="hasFilters ? '当前筛选条件下没有流水，可重置后重试。' : '新建第一条流水后，报表会按币种汇总。'"
								:action-text="hasFilters ? '重置筛选' : canCreate ? '新建流水' : ''"
								@action="hasFilters ? onReset() : (formVisible = true)"
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
									<el-table-column prop="code" label="流水号" min-width="150" sortable="custom" class-name="col-code">
										<template #default="{ row }">
											<span class="cell-label">流水号</span>
											<div class="cell-value">
												<span class="mold-cash-code">{{ row.code }}</span>
												<el-tag v-if="row.replacesEntryId" size="small" type="warning" effect="plain">更正重录</el-tag>
												<el-tag v-if="row.reversesEntryId" size="small" type="info" effect="plain">冲正</el-tag>
											</div>
										</template>
									</el-table-column>
									<el-table-column prop="occurredOn" label="日期" width="112" sortable="custom">
										<template #default="{ row }">
											<span class="cell-label">日期</span>
											<div class="cell-value">{{ formatDate(row.occurredOn) }}</div>
										</template>
									</el-table-column>
									<el-table-column label="类别" width="120">
										<template #default="{ row }">
											<span class="cell-label">类别</span>
											<div class="cell-value">{{ CATEGORY_LABEL[row.category] || row.category }}</div>
										</template>
									</el-table-column>
									<el-table-column label="往来单位" min-width="140">
										<template #default="{ row }">
											<span class="cell-label">往来单位</span>
											<div class="cell-value">{{ row.counterpartyId ? counterpartyName(row.counterpartyId) : '—' }}</div>
										</template>
									</el-table-column>
									<el-table-column prop="amount" label="金额" width="140" align="right" sortable="custom" class-name="col-num">
										<template #default="{ row }">
											<span class="cell-label">金额</span>
											<div class="cell-value">
												<MoneyText :value="row.amount" :currency="row.currency" :direction="row.direction" />
											</div>
										</template>
									</el-table-column>
									<el-table-column label="归属" width="120">
										<template #default="{ row }">
											<span class="cell-label">归属</span>
											<div class="cell-value">
												{{ ALLOCATION_LABEL[row.allocationStatus] || row.allocationStatus }}
												<span v-if="row.projectId" class="mold-cash-project">{{ projectName(row.projectId) }}</span>
											</div>
										</template>
									</el-table-column>
									<el-table-column label="对账" width="100" align="center">
										<template #default="{ row }">
											<span class="cell-label">对账</span>
											<div class="cell-value">
												<StatusTag kind="reconcile" :value="!!row.reconciledAt" />
											</div>
										</template>
									</el-table-column>
									<el-table-column prop="status" label="状态" width="96" align="center" sortable="custom">
										<template #default="{ row }">
											<span class="cell-label">状态</span>
											<div class="cell-value"><StatusTag kind="cash" :value="row.status" /></div>
										</template>
									</el-table-column>
									<el-table-column label="操作" width="140" align="right" class-name="col-act">
										<template #default="{ row }">
											<el-dropdown trigger="click" @command="(cmd: string) => onRowCommand(cmd, row)">
												<el-button link type="primary" size="small">操作<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
												<template #dropdown>
													<el-dropdown-menu>
														<el-dropdown-item v-if="canUpdate && row.status === 'CONFIRMED' && !row.reconciledAt" command="edit">编辑备注/凭证</el-dropdown-item>
														<el-dropdown-item v-if="canCorrection && row.status === 'CONFIRMED'" command="correct">更正…</el-dropdown-item>
														<el-dropdown-item v-if="canReconcile && row.status === 'CONFIRMED' && !row.reconciledAt" command="reconcile">标记对账</el-dropdown-item>
														<el-dropdown-item v-if="canReconcile && row.reconciledAt" command="unreconcile">取消对账</el-dropdown-item>
														<el-dropdown-item v-if="canReverse && row.status === 'CONFIRMED' && !row.reversesEntryId" command="reverse">冲正</el-dropdown-item>
														<el-dropdown-item v-if="canVoid && row.status === 'CONFIRMED' && !row.reconciledAt" command="void">作废</el-dropdown-item>
														<el-dropdown-item v-if="!hasRowAction(row)" disabled>无可执行操作</el-dropdown-item>
													</el-dropdown-menu>
												</template>
											</el-dropdown>
										</template>
									</el-table-column>
								</el-table>
							</div>

							<div v-if="state === 'ready'" class="mold-pager">
								<div class="mold-pager-total">共 {{ total }} 条流水</div>
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

						<!-- 报表是聚合口径，服务端只放行 mold:cash:viewAll；无该权限时不渲染入口，避免员工点进必 403 的页面 -->
						<el-tab-pane v-if="canViewAll" label="报表" name="reports">
							<div class="mold-filter-bar is-open">
								<el-form :inline="true" @submit.prevent="loadReport">
									<el-form-item label="报表">
										<el-radio-group v-model="reportMode" @change="loadReport">
											<el-radio-button value="summary">现金汇总</el-radio-button>
											<el-radio-button value="project">项目收支</el-radio-button>
										</el-radio-group>
									</el-form-item>
									<el-form-item v-if="reportMode === 'project'" label="项目">
										<el-select v-model="reportProjectId" filterable placeholder="选择项目" style="width: 220px">
											<el-option v-for="p in projects" :key="p.id" :label="`${p.code} · ${p.name}`" :value="p.id" />
										</el-select>
									</el-form-item>
									<el-form-item label="日期">
										<el-date-picker
											v-model="reportRange"
											type="daterange"
											value-format="YYYY-MM-DD"
											range-separator="至"
											start-placeholder="开始"
											end-placeholder="结束"
											style="width: 240px"
										/>
									</el-form-item>
									<el-form-item>
										<span class="mold-report-date-hint">日期同时过滤流水日期与采购日期（已知口径，待业务确认）</span>
									</el-form-item>
									<el-form-item>
										<el-button type="primary" :icon="Search" :loading="reportLoading" @click="loadReport">查询</el-button>
									</el-form-item>
								</el-form>
							</div>

							<div v-if="reportError" class="mold-report-error" role="alert">
								{{ reportError }}
								<el-button link type="primary" @click="loadReport">重试</el-button>
							</div>
							<div v-else-if="reportLoading" class="mold-skeleton" aria-busy="true">
								<div class="mold-skel-block" style="height: 96px; width: 100%; margin-bottom: 12px" />
								<div class="mold-skel-block" style="height: 220px; width: 100%" />
							</div>
							<template v-else-if="report">
								<div v-if="!report.currencies?.length" class="mold-card mold-inline-note">
									该条件下没有已确认流水。报表只统计 status=CONFIRMED 的记录，作废与冲正净额为 0。
								</div>
								<template v-else>
									<div class="mold-report-cards">
										<div v-for="c in report.currencies" :key="c.currency" class="mold-card mold-report-card">
											<div class="mold-card-title">
												<span>{{ c.currency }}</span>
												<span class="mold-card-note">已确认净现金</span>
											</div>
											<div class="mold-report-net"><MoneyText :value="c.netCash" :currency="c.currency" /></div>
											<dl class="mold-kv mold-kv-tight mold-report-kv">
												<div><dt>采购承诺</dt><dd><MoneyText :value="c.purchaseCommitted" :currency="c.currency" /></dd></div>
												<div><dt>付款净额</dt><dd><MoneyText :value="c.paidNet" :currency="c.currency" /></dd></div>
												<div><dt>收款净额</dt><dd><MoneyText :value="c.collectedNet" :currency="c.currency" /></dd></div>
												<div><dt>已对账净额</dt><dd><MoneyText :value="c.reconciledNet" :currency="c.currency" /></dd></div>
												<div><dt>折算系数</dt><dd>{{ c.conversion }}</dd></div>
											</dl>
										</div>
									</div>

									<div class="mold-card mold-report-table">
										<div class="mold-card-title">按类别 <span class="mold-card-note">金额为原币累计</span></div>
										<el-table :data="categoryRows" size="small" border>
											<el-table-column prop="currency" label="币种" width="80" />
											<el-table-column label="类别" min-width="130">
												<template #default="{ row }">{{ CATEGORY_LABEL[row.category] || row.category }}</template>
											</el-table-column>
											<el-table-column label="方向" width="80">
												<template #default="{ row }">{{ row.direction === 'IN' ? '收入' : '支出' }}</template>
											</el-table-column>
											<el-table-column label="金额" width="140" align="right">
												<template #default="{ row }"><MoneyText :value="row.amount" :currency="row.currency" :direction="row.direction" /></template>
											</el-table-column>
											<el-table-column prop="count" label="笔数" width="80" align="right" />
										</el-table>
									</div>

									<div class="mold-card mold-report-table">
										<div class="mold-card-title">按归属 <span class="mold-card-note">项目 / 公共 / 未分配</span></div>
										<el-table :data="report.byAllocation || []" size="small" border empty-text="没有归属数据">
											<el-table-column label="归属" width="120">
												<template #default="{ row }">{{ ALLOCATION_LABEL[row.allocationStatus] || row.allocationStatus }}</template>
											</el-table-column>
											<el-table-column prop="currency" label="币种" width="90" />
											<el-table-column label="净现金" width="150" align="right">
												<template #default="{ row }"><MoneyText :value="row.netCash" :currency="row.currency" /></template>
											</el-table-column>
										</el-table>
									</div>
								</template>
							</template>
						</el-tab-pane>
					</el-tabs>
				</div>
			</div>
		</div>

		<CashFormDialog v-model="formVisible" :projects="projects" :counterparties="counterparties" @saved="onCreated" />

		<!-- 编辑：只开放备注与凭证号（金额/归属走更正） -->
		<el-dialog v-model="editVisible" :title="`编辑 ${editTarget?.code || ''}`" :width="dialogWidth" append-to-body :close-on-click-modal="false" class="mold-dialog">
			<el-alert v-if="editError" type="error" :closable="false" show-icon :title="editError" class="mold-dialog-alert" />
			<el-alert type="info" :closable="false" show-icon title="金额、日期、归属不可直接修改" description="录错请用「更正」生成替换流水；本弹窗只改备注与凭证号。" class="mold-dialog-alert" />
			<el-form label-position="top" @submit.prevent="submitEdit">
				<el-form-item label="凭证号">
					<el-input v-model="editForm.voucherNo" maxlength="64" />
				</el-form-item>
				<el-form-item label="备注">
					<el-input v-model="editForm.note" type="textarea" :rows="3" maxlength="2000" show-word-limit />
				</el-form-item>
				<div class="mold-dialog-actions">
					<el-button @click="editVisible = false">取消</el-button>
					<el-button type="primary" native-type="submit" :loading="acting" :disabled="editNoChanges">
						{{ editNoChanges ? '未修改' : '保存' }}
					</el-button>
				</div>
			</el-form>
		</el-dialog>

		<!-- 作废 / 冲正 -->
		<el-dialog v-model="reasonVisible" :title="reasonKind === 'void' ? '作废流水' : '冲正流水'" :width="dialogWidth" append-to-body :close-on-click-modal="false" class="mold-dialog">
			<el-alert v-if="reasonError" type="error" :closable="false" show-icon :title="reasonError" class="mold-dialog-alert" />
			<el-alert
				type="warning"
				:closable="false"
				show-icon
				:title="reasonKind === 'void' ? '作废保留原记录，审计可查' : '冲正生成一条反向流水，原流水保留'"
				:description="reasonKind === 'void' ? '已对账流水需先取消对账。' : '冲正金额与原流水相同、方向相反，净额归零。'"
				class="mold-dialog-alert"
			/>
			<el-form label-position="top" @submit.prevent="submitReason">
				<el-form-item label="原因（必填）">
					<el-input v-model="reasonText" type="textarea" :rows="3" maxlength="2000" show-word-limit placeholder="写清原因，会写入审计" />
				</el-form-item>
				<div class="mold-dialog-actions">
					<el-button @click="reasonVisible = false">取消</el-button>
					<el-button :type="reasonKind === 'void' ? 'danger' : 'primary'" native-type="submit" :loading="acting" :disabled="!reasonText.trim()">
						确认{{ reasonKind === 'void' ? '作废' : '冲正' }}
					</el-button>
				</div>
			</el-form>
		</el-dialog>

		<!-- 对账 -->
		<el-dialog v-model="reconcileVisible" title="标记已对账" :width="dialogWidth" append-to-body :close-on-click-modal="false" class="mold-dialog">
			<el-alert v-if="reconcileError" type="error" :closable="false" show-icon :title="reconcileError" class="mold-dialog-alert" />
			<el-form label-position="top" @submit.prevent="submitReconcile">
				<el-form-item label="对账参考号（可选）">
					<el-input v-model="reconcileRef" maxlength="128" placeholder="银行流水号 / 对账单编号" />
				</el-form-item>
				<p class="mold-form-hint">标记后流水不可编辑或作废，需先取消对账。</p>
				<div class="mold-dialog-actions">
					<el-button @click="reconcileVisible = false">取消</el-button>
					<el-button type="primary" native-type="submit" :loading="acting">确认对账</el-button>
				</div>
			</el-form>
		</el-dialog>
	</div>
</template>

<script setup lang="ts" name="moldCash">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowDown, ArrowLeft, ArrowRight, Document, Plus, Refresh, RefreshLeft, Search } from '@element-plus/icons-vue';
import StatePanel from '/@/components/mold/StatePanel.vue';
import StatusTag from '/@/components/mold/StatusTag.vue';
import MoneyText from '/@/components/mold/MoneyText.vue';
import CashFormDialog from './components/CashFormDialog.vue';
import { useMoldPermissions } from '/@/api/mold/permissions';
import { fetchProjectPage, type MoldProjectRow } from '/@/api/mold/projects';
import {
	ALLOCATION_LABEL,
	CATEGORY_LABEL,
	fetchCashEntries,
	fetchCashSummary,
	fetchProjectCashReport,
	reconcileCashEntry,
	reverseCashEntry,
	unreconcileCashEntry,
	updateCashEntry,
	voidCashEntry,
	type MoldCashReport,
	type MoldCashRow,
} from '/@/api/mold/cash';
import { fetchCounterparties, type MoldCounterpartyRow } from '/@/api/mold/purchases';
import { describeMoldError } from '/@/api/mold/errors';
import { debounceRefresh, useMoldEvent } from '/@/api/mold/events';
import { useMoldList } from '/@/utils/moldList';
import { formatDate } from '/@/utils/moldFormat';
import { beginIdempotentAction, finishIdempotentAction, stableHash } from '/@/utils/idempotency';

const router = useRouter();
const { can, ensureLoaded } = useMoldPermissions();

const canCreate = computed(() => can('mold:cash:create'));
const canUpdate = computed(() => can('mold:cash:update'));
const canVoid = computed(() => can('mold:cash:void'));
const canReverse = computed(() => can('mold:cash:reverse'));
const canReconcile = computed(() => can('mold:cash:reconcile'));
const canCorrection = computed(() => can('mold:correction:preview'));
/** 报表（现金汇总/项目收支）服务端判 mold:cash:viewAll，仅老板与财务有 */
const canViewAll = computed(() => can('mold:cash:viewAll'));

const tab = ref<'entries' | 'reports'>('entries');
// 权限异步加载/切换账号后，报表 Tab 可能消失：此时必须退回流水 Tab，不能残留 reports 状态
watch(canViewAll, (allowed) => {
	if (!allowed && tab.value === 'reports') tab.value = 'entries';
});
const filterOpen = ref(false);
const dialogWidth = computed(() => (window.innerWidth <= 900 ? '94%' : '520px'));
const tableMaxHeight = computed(() => (window.innerWidth <= 900 ? undefined : 'calc(100vh - 340px)'));

const filters = reactive({
	direction: '',
	category: '',
	status: '',
	allocationStatus: '',
	projectId: '',
});

const list = useMoldList<MoldCashRow>({
	fetch: (query) => fetchCashEntries(query as any),
	filters: () => ({
		direction: filters.direction || undefined,
		category: filters.category || undefined,
		status: filters.status || undefined,
		allocationStatus: filters.allocationStatus || undefined,
		projectId: filters.projectId || undefined,
	}),
	sortable: ['code', 'occurredOn', 'amount', 'status'],
	defaultSort: { key: 'occurredOn', order: 'desc' },
});
const { rows, total, pageNum, pageSize, pageCount, state, loading, errorInfo, errorAction, hasFilters } = list;

const projects = ref<MoldProjectRow[]>([]);
const counterparties = ref<MoldCounterpartyRow[]>([]);

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
const projectName = (id: string) => projectMap.value.get(id)?.name || id.slice(0, 8);

function hasRowAction(row: MoldCashRow) {
	return (
		(canUpdate.value && row.status === 'CONFIRMED' && !row.reconciledAt) ||
		(canCorrection.value && row.status === 'CONFIRMED') ||
		(canReconcile.value && row.status === 'CONFIRMED') ||
		(canReverse.value && row.status === 'CONFIRMED' && !row.reversesEntryId) ||
		(canVoid.value && row.status === 'CONFIRMED' && !row.reconciledAt)
	);
}

/* ------------------------------ 列表操作 ------------------------------ */
function onQuery() {
	list.query();
}
function onReset() {
	filters.direction = '';
	filters.category = '';
	filters.status = '';
	filters.allocationStatus = '';
	filters.projectId = '';
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
	if (tab.value === 'reports' && canViewAll.value) loadReport();
}
function goHome() {
	router.push('/home');
}
function goCorrections(entryId?: string) {
	router.push({ path: '/mold/corrections', query: entryId ? { entryId } : {} });
}
function onTabChange(name: string | number) {
	// 报表 Tab 对无 viewAll 的账号不渲染；即便被程序化切到 reports，也不发报表请求
	if (name === 'reports' && !canViewAll.value) {
		tab.value = 'entries';
		return;
	}
	if (!projects.value.length) loadLookups();
	if (name === 'reports' && !report.value) loadReport();
}

/* ------------------------------ 新建 ------------------------------ */
const formVisible = ref(false);
function onCreated() {
	list.reload();
	report.value = null;
}

/* ------------------------------ 行操作 ------------------------------ */
const acting = ref(false);
const editVisible = ref(false);
const editError = ref('');
const editTarget = ref<MoldCashRow | null>(null);
const editForm = reactive({ note: '', voucherNo: '' });
const editNoChanges = computed(() => {
	if (!editTarget.value) return true;
	return (editTarget.value.note || '') === (editForm.note || '') && (editTarget.value.voucherNo || '') === (editForm.voucherNo || '');
});

function onRowCommand(command: string, row: MoldCashRow) {
	if (command === 'edit') {
		editTarget.value = row;
		editForm.note = row.note || '';
		editForm.voucherNo = row.voucherNo || '';
		editError.value = '';
		editVisible.value = true;
	} else if (command === 'correct') {
		goCorrections(row.id);
	} else if (command === 'reconcile') {
		reconcileTarget.value = row;
		reconcileRef.value = '';
		reconcileError.value = '';
		reconcileVisible.value = true;
	} else if (command === 'unreconcile') {
		unreconcile(row);
	} else if (command === 'reverse') {
		openReason('reverse', row);
	} else if (command === 'void') {
		openReason('void', row);
	}
}

async function submitEdit() {
	if (!editTarget.value || editNoChanges.value) return;
	acting.value = true;
	editError.value = '';
	try {
		await updateCashEntry(
			editTarget.value.id,
			{ note: editForm.note.trim() || null, voucherNo: editForm.voucherNo.trim() || null },
			editTarget.value.version
		);
		ElMessage.success('已保存');
		editVisible.value = false;
		list.reload();
	} catch (err: any) {
		editError.value = describeMoldError(err, '保存失败').message;
	} finally {
		acting.value = false;
	}
}

const reasonVisible = ref(false);
const reasonKind = ref<'void' | 'reverse'>('void');
const reasonText = ref('');
const reasonError = ref('');
const reasonTarget = ref<MoldCashRow | null>(null);

function openReason(kind: 'void' | 'reverse', row: MoldCashRow) {
	reasonKind.value = kind;
	reasonTarget.value = row;
	reasonText.value = '';
	reasonError.value = '';
	reasonVisible.value = true;
}

async function submitReason() {
	if (!reasonTarget.value || !reasonText.value.trim()) return;
	acting.value = true;
	reasonError.value = '';
	const targetRow = reasonTarget.value;
	const reason = reasonText.value.trim();
	const scope = `cash.${reasonKind.value}:${targetRow.id}:${stableHash(reason)}`;
	try {
		const key = beginIdempotentAction(scope);
		if (reasonKind.value === 'void') await voidCashEntry(targetRow.id, reason, key);
		else await reverseCashEntry(targetRow.id, reason, key);
		finishIdempotentAction(scope);
		ElMessage.success(reasonKind.value === 'void' ? '流水已作废' : '冲正流水已生成');
		reasonVisible.value = false;
		list.reload();
		report.value = null;
	} catch (err: any) {
		reasonError.value = describeMoldError(err, '操作失败').message;
	} finally {
		acting.value = false;
	}
}

const reconcileVisible = ref(false);
const reconcileRef = ref('');
const reconcileError = ref('');
const reconcileTarget = ref<MoldCashRow | null>(null);

async function submitReconcile() {
	if (!reconcileTarget.value) return;
	acting.value = true;
	reconcileError.value = '';
	const reconcileBody = { reconciliationRef: reconcileRef.value.trim() || null };
	const scope = `cash.reconcile:${reconcileTarget.value.id}:${stableHash(JSON.stringify(reconcileBody))}`;
	try {
		await reconcileCashEntry(reconcileTarget.value.id, reconcileBody, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success('已标记对账');
		reconcileVisible.value = false;
		list.reload();
	} catch (err: any) {
		reconcileError.value = describeMoldError(err, '对账失败').message;
	} finally {
		acting.value = false;
	}
}

async function unreconcile(row: MoldCashRow) {
	try {
		await ElMessageBox.confirm(`确定取消 ${row.code} 的对账标记？取消后可再次编辑与作废。`, '取消对账', {
			type: 'warning',
			confirmButtonText: '取消对账',
			cancelButtonText: '返回',
		});
	} catch {
		return;
	}
	const scope = `cash.unreconcile:${row.id}`;
	try {
		await unreconcileCashEntry(row.id, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success('已取消对账');
		list.reload();
	} catch (err: any) {
		ElMessage.error(describeMoldError(err, '取消对账失败').message);
	}
}

/* ------------------------------ 报表 ------------------------------ */
const reportMode = ref<'summary' | 'project'>('summary');
const reportProjectId = ref('');
const reportRange = ref<[string, string] | null>(null);
const report = ref<MoldCashReport | null>(null);
const reportLoading = ref(false);
const reportError = ref('');

const categoryRows = computed(() => {
	const out: { currency: string; category: string; direction: string; amount: string; count: number }[] = [];
	(report.value?.currencies || []).forEach((c) => {
		(c.categories || []).forEach((cat) => {
			out.push({ currency: c.currency, category: cat.category, direction: cat.direction, amount: cat.amount, count: cat.count });
		});
	});
	return out;
});

async function loadReport() {
	if (!canViewAll.value) {
		// 无 viewAll 的账号不请求聚合报表（服务端必 403），也不在界面留下报错
		report.value = null;
		reportError.value = '';
		return;
	}
	if (reportMode.value === 'project') {
		// 进页时若接口短暂失败，项目下拉会为空；这里补一次拉取，避免永久空白
		if (!projects.value.length) await loadLookups();
		if (!reportProjectId.value) {
			reportError.value = projects.value.length ? '请选择项目后再查项目收支。' : '项目列表加载失败，请稍后重试。';
			report.value = null;
			return;
		}
	}
	reportLoading.value = true;
	reportError.value = '';
	try {
		const query = {
			dateFrom: reportRange.value?.[0] || undefined,
			dateTo: reportRange.value?.[1] || undefined,
		};
		report.value =
			reportMode.value === 'project'
				? await fetchProjectCashReport({ projectId: reportProjectId.value, ...query })
				: await fetchCashSummary(query);
	} catch (err: any) {
		report.value = null;
		reportError.value = describeMoldError(err, '报表加载失败').message;
	} finally {
		reportLoading.value = false;
	}
}

/* ------------------------------ 初始化 ------------------------------ */
async function loadLookups() {
	const results = await Promise.allSettled([
		fetchProjectPage({ pageNum: 1, pageSize: 100, sort: 'code', order: 'asc' }),
		fetchCounterparties({ pageNum: 1, pageSize: 100, sort: 'name', order: 'asc' }),
	]);
	if (results[0].status === 'fulfilled') projects.value = results[0].value.rows;
	if (results[1].status === 'fulfilled') counterparties.value = results[1].value.rows;
}

onMounted(() => {
	ensureLoaded();
	list.load(true);
	loadLookups();
});

/* SSE：流水/往来/采购变更只刷当前 Tab 的列表，报表 Tab 首次进入时再拉 */
const refreshCashOnEvent = debounceRefresh(() => {
	if (tab.value === 'entries') list.reload();
	else if (canViewAll.value && report.value) loadReport();
}, 700);
useMoldEvent(['cash_entry.created', 'cash_entry.updated', 'cash_entry.voided', 'cash_entry.reversed', 'cash_entry.reconciled', 'cash_entry.unreconciled', 'cash_entry.corrected', 'counterparty.created', 'counterparty.updated', 'purchase.created', 'purchase.confirmed', 'purchase.voided'], refreshCashOnEvent);
</script>

<style scoped lang="scss">
.mold-toolbar-left {
	display: flex;
	flex-wrap: wrap;
	gap: 8px;
}
.mold-cash-code {
	font-weight: 500;
}
.mold-cash-project {
	display: block;
	font-size: 12px;
	color: var(--el-text-color-secondary);
}
.mold-report-cards {
	display: grid;
	grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
	gap: 12px;
	margin-bottom: 12px;
}
.mold-report-card {
	padding: 14px;
}
.mold-report-net {
	font-size: 24px;
	font-weight: 600;
	line-height: 32px;
	margin-bottom: 8px;
}
.mold-report-kv {
	grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
}
.mold-report-table {
	margin-bottom: 12px;
}
.mold-report-error {
	margin-bottom: 10px;
	padding: 8px 10px;
	border-radius: 4px;
	background: var(--el-color-danger-light-9);
	color: var(--el-color-danger);
	font-size: 13px;
}
</style>
