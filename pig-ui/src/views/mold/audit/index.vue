<template>
	<div class="layout-padding">
		<div class="layout-padding-auto layout-padding-view">
			<div ref="scrollRef" class="page-scroll">
				<div class="mold-page">
					<div class="mold-page-head">
						<div>
							<h1>审计日志</h1>
							<p>
								业务审计只追加不修改；每条含操作人、请求 ID 与变更前后快照，用于追责与对账。
								<span v-if="state !== 'forbidden' && state !== 'error'" class="mold-page-total">
									共 {{ state === 'loading' ? '—' : total }} 条
								</span>
							</p>
						</div>
						<div class="mold-page-head-actions">
							<el-button class="mold-mobile-filter-btn" :icon="Search" @click="filterOpen = !filterOpen">
								{{ filterOpen ? '收起筛选' : '筛选' }}
							</el-button>
							<el-button circle :icon="Refresh" aria-label="刷新审计日志" :loading="loading" @click="reload" />
						</div>
					</div>

					<div class="mold-filter-bar" :class="{ 'is-open': filterOpen }">
						<el-form :inline="true" @submit.prevent="onQuery">
							<el-form-item label="对象类型">
								<el-select v-model="filters.entityType" placeholder="全部" clearable filterable allow-create style="width: 160px">
									<el-option v-for="t in ENTITY_TYPES" :key="t" :label="t" :value="t" />
								</el-select>
							</el-form-item>
							<el-form-item label="动作">
								<el-select v-model="filters.action" placeholder="全部" clearable filterable allow-create style="width: 140px">
									<el-option v-for="a in ACTIONS" :key="a" :label="a" :value="a" />
								</el-select>
							</el-form-item>
							<el-form-item label="时间">
								<el-date-picker
									v-model="filters.dateRange"
									type="daterange"
									value-format="YYYY-MM-DD"
									range-separator="至"
									start-placeholder="开始"
									end-placeholder="结束"
									style="width: 240px"
								/>
							</el-form-item>
							<el-form-item label="对象 ID">
								<el-input v-model="filters.entityId" placeholder="完整 UUID" clearable style="width: 240px" />
							</el-form-item>
							<el-form-item label="操作人 ID">
								<el-input v-model="filters.actorId" placeholder="完整 UUID" clearable style="width: 240px" />
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
						title="无审计日志查看权限"
						description="当前账号没有 mold:audit:list 权限（仅 OWNER）。服务端仍会对每个请求重新鉴权。"
						action-text="返回首页"
						@action="goHome"
					/>
					<div v-else-if="state === 'loading'" class="mold-skeleton" aria-busy="true" aria-label="正在加载审计日志">
						<div v-for="i in 6" :key="i" class="mold-skel-row">
							<div class="mold-skel-block" style="height: 16px; width: 22%" />
							<div class="mold-skel-block" style="height: 16px; width: 26%" />
							<div class="mold-skel-block" style="height: 16px; width: 20%" />
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
						:title="hasFilters ? '没有符合条件的审计记录' : '还没有审计记录'"
						:description="hasFilters ? '当前筛选条件下没有记录，可重置后重试。' : '任何业务写操作都会在此留下记录。'"
						:action-text="hasFilters ? '重置筛选' : ''"
						@action="onReset"
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
							<el-table-column prop="createdAt" label="时间" width="160" sortable="custom">
								<template #default="{ row }">
									<span class="cell-label">时间</span>
									<div class="cell-value">{{ formatDateTime(row.createdAt) }}</div>
								</template>
							</el-table-column>
							<el-table-column prop="entityType" label="对象类型" width="140" sortable="custom">
								<template #default="{ row }">
									<span class="cell-label">对象类型</span>
									<div class="cell-value"><el-tag size="small" effect="plain">{{ row.entityType }}</el-tag></div>
								</template>
							</el-table-column>
							<el-table-column label="对象 ID" min-width="140">
								<template #default="{ row }">
									<span class="cell-label">对象 ID</span>
									<div class="cell-value mold-audit-id" :title="row.entityId">{{ shortId(row.entityId) }}</div>
								</template>
							</el-table-column>
							<el-table-column prop="action" label="动作" width="130" sortable="custom">
								<template #default="{ row }">
									<span class="cell-label">动作</span>
									<div class="cell-value">{{ row.action }}</div>
								</template>
							</el-table-column>
							<el-table-column label="操作人" width="150">
								<template #default="{ row }">
									<span class="cell-label">操作人</span>
									<div class="cell-value">{{ row.actorId ? actorName(row.actorId) : '—' }}</div>
								</template>
							</el-table-column>
							<el-table-column label="请求 ID" min-width="140">
								<template #default="{ row }">
									<span class="cell-label">请求 ID</span>
									<div class="cell-value mold-audit-id" :title="row.requestId || ''">{{ row.requestId || '—' }}</div>
								</template>
							</el-table-column>
							<el-table-column label="操作" width="80" align="right" class-name="col-act">
								<template #default="{ row }">
									<el-button link type="primary" size="small" @click.stop="openDetail(row)">快照</el-button>
								</template>
							</el-table-column>
						</el-table>
					</div>

					<div v-if="state === 'ready'" class="mold-pager">
						<div class="mold-pager-total">共 {{ total }} 条审计</div>
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

		<el-drawer v-model="detailVisible" :size="drawerSize" :with-header="false" append-to-body @closed="detailRow = null">
			<div class="mold-audit-detail">
				<div class="mold-audit-detail-head">
					<div>
						<div class="mold-audit-detail-kicker">{{ detailRow?.entityType }} · {{ detailRow?.action }}</div>
						<h2>{{ formatDateTime(detailRow?.createdAt) }}</h2>
					</div>
					<el-button text @click="detailVisible = false">关闭</el-button>
				</div>
				<dl v-if="detailRow" class="mold-kv mold-kv-tight">
					<div><dt>对象 ID</dt><dd class="mold-audit-id">{{ detailRow.entityId }}</dd></div>
					<div><dt>操作人</dt><dd>{{ detailRow.actorId || '—' }}</dd></div>
					<div><dt>请求 ID</dt><dd class="mold-audit-id">{{ detailRow.requestId || '—' }}</dd></div>
					<div><dt>记录 ID</dt><dd>{{ detailRow.id }}</dd></div>
					<div v-if="detailRow.ip"><dt>来源 IP</dt><dd class="mold-audit-id">{{ detailRow.ip }}</dd></div>
					<div v-if="detailRow.userAgent"><dt>客户端</dt><dd class="mold-audit-id">{{ detailRow.userAgent }}</dd></div>
				</dl>
				<el-tabs v-if="detailRow" v-model="detailTab">
					<el-tab-pane label="变更前" name="before">
						<pre class="mold-audit-json">{{ pretty(detailRow.beforeData) }}</pre>
					</el-tab-pane>
					<el-tab-pane label="变更后" name="after">
						<pre class="mold-audit-json">{{ pretty(detailRow.afterData) }}</pre>
					</el-tab-pane>
					<el-tab-pane label="差异" name="diff">
						<pre class="mold-audit-json">{{ pretty(detailRow.diff) }}</pre>
					</el-tab-pane>
				</el-tabs>
				<p class="mold-form-hint">快照可能包含业务字段；页面不显示口令、密钥等敏感信息（服务端已脱敏）。</p>
			</div>
		</el-drawer>
	</div>
</template>

<script setup lang="ts" name="moldAudit">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ArrowLeft, ArrowRight, Refresh, RefreshLeft, Search } from '@element-plus/icons-vue';
import StatePanel from '/@/components/mold/StatePanel.vue';
import { useMoldPermissions } from '/@/api/mold/permissions';
import { fetchAuditLogs, type MoldAuditRow } from '/@/api/mold/audit';
import { debounceRefresh, useMoldEvent } from '/@/api/mold/events';
import { fetchDirectory } from '/@/api/mold/users';
import { useMoldList } from '/@/utils/moldList';
import { formatDateTime } from '/@/utils/moldFormat';

const router = useRouter();
const { ensureLoaded } = useMoldPermissions();

const ENTITY_TYPES = ['PROJECT', 'STAGE', 'ISSUE', 'PURCHASE', 'PURCHASE_RECEIPT', 'PURCHASE_INVOICE', 'CASH_ENTRY', 'COUNTERPARTY', 'ATTACHMENT', 'USER'];
const ACTIONS = [
	'CREATE',
	'UPDATE',
	'SET_ROLES',
	'RESET_PASSWORD',
	'CONFIRM',
	'VOID',
	'REVERSE',
	'RECONCILE',
	'UNRECONCILE',
	'COMPLETE',
	'TRANSITION',
	'DELETE',
	'DOWNLOAD',
];

const filterOpen = ref(false);
const tableMaxHeight = computed(() => (window.innerWidth <= 900 ? undefined : 'calc(100vh - 320px)'));
const drawerSize = computed(() => (window.innerWidth <= 900 ? '100%' : '620px'));

const filters = reactive({
	entityType: '',
	action: '',
	entityId: '',
	actorId: '',
	dateRange: null as [string, string] | null,
});

const list = useMoldList<MoldAuditRow>({
	fetch: (query) => fetchAuditLogs(query as any),
	filters: () => ({
		entityType: filters.entityType || undefined,
		action: filters.action || undefined,
		entityId: filters.entityId.trim() || undefined,
		actorId: filters.actorId.trim() || undefined,
		dateFrom: filters.dateRange?.[0] || undefined,
		dateTo: filters.dateRange?.[1] || undefined,
	}),
	sortable: ['id', 'createdAt', 'entityType', 'action'],
	defaultSort: { key: 'createdAt', order: 'desc' },
});
const { rows, total, pageNum, pageSize, pageCount, state, loading, errorInfo, errorAction, hasFilters } = list;

const actorNames = ref<Record<string, string>>({});
const actorName = (id: string) => actorNames.value[id] || shortId(id);
const shortId = (id?: string | null) => (id ? (id.length > 14 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id) : '—');

function onQuery() {
	list.query();
}
function onReset() {
	filters.entityType = '';
	filters.action = '';
	filters.entityId = '';
	filters.actorId = '';
	filters.dateRange = null;
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

/* 任何业务写操作都会新增审计行：事件到达只刷当前审计列表，不整页重载 */
const refreshOnEvent = debounceRefresh(() => list.reload(), 800);
useMoldEvent('*', refreshOnEvent);

/* ------------------------------ 快照 ------------------------------ */
const detailVisible = ref(false);
const detailRow = ref<MoldAuditRow | null>(null);
const detailTab = ref('before');

function openDetail(row: MoldAuditRow) {
	detailRow.value = row;
	detailTab.value = row.beforeData ? 'before' : row.afterData ? 'after' : 'diff';
	detailVisible.value = true;
}

function pretty(value: unknown) {
	if (value === null || value === undefined) return '—';
	if (typeof value === 'string') {
		try {
			return JSON.stringify(JSON.parse(value), null, 2);
		} catch {
			return value;
		}
	}
	try {
		return JSON.stringify(value, null, 2);
	} catch {
		return String(value);
	}
}

async function loadActors() {
	try {
		const { rows: users } = await fetchDirectory();
		const map: Record<string, string> = {};
		users.forEach((u) => (map[u.id] = u.displayName));
		actorNames.value = map;
	} catch {
		actorNames.value = {};
	}
}

onMounted(() => {
	ensureLoaded();
	list.load(true);
	loadActors();
});
</script>

<style scoped lang="scss">
.mold-audit-id {
	font-family: var(--el-font-family-mono, monospace);
	font-size: 12px;
	overflow-wrap: anywhere;
}
.mold-audit-detail {
	padding: 16px 18px;
}
.mold-audit-detail-head {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 10px;
	margin-bottom: 12px;
	h2 {
		margin: 2px 0 0;
		font-size: 18px;
		line-height: 26px;
	}
}
.mold-audit-detail-kicker {
	font-size: 12px;
	color: var(--el-text-color-secondary);
}
.mold-audit-json {
	margin: 8px 0 0;
	padding: 10px 12px;
	max-height: 52vh;
	overflow: auto;
	border: 1px solid var(--el-border-color-lighter);
	border-radius: 6px;
	background: var(--el-fill-color-lighter);
	font-size: 12px;
	line-height: 18px;
	white-space: pre-wrap;
	overflow-wrap: anywhere;
}
@media (max-width: 900px) {
	.mold-audit-detail {
		padding: 12px;
	}
}
</style>
