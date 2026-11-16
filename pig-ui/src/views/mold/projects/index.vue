<template>
	<div class="layout-padding">
		<div class="layout-padding-auto layout-padding-view">
			<!-- 页面滚动容器：Pig 壳固定视口 + overflow:hidden，每页必须自带滚动（规范 6.1） -->
			<div ref="scrollRef" class="page-scroll">
				<div class="mold-page">
					<!-- 页头 -->
					<div class="mold-page-head">
						<div>
							<h1>公司进度</h1>
							<p>
								全部项目的基础进度；金额与财务附件不在本页显示。
								<span v-if="state !== 'forbidden' && state !== 'error'" class="mold-page-total">
									共 {{ state === 'loading' ? '—' : total }} 条
								</span>
							</p>
						</div>
						<div class="mold-page-head-actions">
							<el-button class="mold-mobile-filter-btn" :icon="Search" @click="mobileFilterOpen = !mobileFilterOpen">
								{{ mobileFilterOpen ? '收起筛选' : '筛选' }}
							</el-button>
							<el-button circle :icon="Refresh" aria-label="刷新列表" :loading="loading" @click="reload" />
						</div>
					</div>

					<!-- 数据源提示：mock 必须显式标识，不允许冒充真实数据 -->
					<div v-if="usingMock" class="mold-notice" role="status">
						<el-icon><WarningFilled /></el-icon>
						<span>DEV 降级示例数据（mock）：真实接口不可达，仅用于本地核对交互；页面字段与契约一致，恢复接口后自动切回真实数据。</span>
					</div>

					<!-- 统计卡：按本页行计算（P1-02）；无权限/失败时不显示 0 -->
					<el-row v-if="state !== 'forbidden' && state !== 'error'" :gutter="16">
						<el-col v-for="s in stats" :key="s.key" :xs="12" :sm="12" :md="6">
							<div class="mold-stat">
								<div class="mold-stat-label">{{ s.label }}</div>
								<div class="mold-stat-value" :class="s.tone">
									<span v-if="state === 'loading'" class="mold-skel-block" style="display: inline-block; width: 56px; height: 28px" />
									<template v-else>{{ s.value }}<span class="mold-stat-unit">{{ s.unit }}</span></template>
								</div>
								<div class="mold-stat-foot">{{ s.foot }}</div>
							</div>
						</el-col>
					</el-row>

					<!-- 筛选：390 默认折叠，点「筛选」展开；页面级失败/无权限时不渲染 -->
					<div v-if="state !== 'forbidden' && state !== 'error'" class="mold-filter-bar" :class="{ 'is-open': mobileFilterOpen }">
						<el-form :inline="true" :model="filters" @submit.prevent="onQuery">
							<el-form-item label="项目编号/名称">
								<el-input v-model="filters.q" placeholder="输入编号或名称" clearable style="width: 200px" @keyup.enter="onQuery" />
							</el-form-item>
							<el-form-item label="业务类型">
								<el-select v-model="filters.businessType" placeholder="全部" clearable style="width: 150px">
									<el-option label="整套模具制作" value="MOLD_SET" />
									<el-option label="零件·工序加工" value="PART_PROCESS" />
								</el-select>
							</el-form-item>
							<el-form-item label="项目状态">
								<el-select v-model="filters.status" placeholder="全部" clearable style="width: 130px">
									<el-option label="进行中" value="ACTIVE" />
									<el-option label="已暂停" value="ON_HOLD" />
									<el-option label="已完工" value="COMPLETED" />
								</el-select>
							</el-form-item>
							<el-form-item>
								<el-button type="primary" :icon="Search" @click="onQuery">查询</el-button>
								<el-button :icon="RefreshLeft" @click="onReset">重置</el-button>
							</el-form-item>
						</el-form>
					</div>

					<!-- 工具栏 -->
					<div v-if="state !== 'forbidden' && state !== 'error'" class="mold-toolbar">
						<el-button v-if="canCreate" type="primary" :icon="Plus" data-testid="project-create" @click="onCreate">新建项目</el-button>
						<el-tooltip content="导出已勾选的项目（当前页勾选，逐行写 CSV）" placement="top">
							<span>
								<el-button
									plain
									type="primary"
									:icon="Download"
									:disabled="selectedRows.length === 0"
									:loading="exportingSelected"
									data-testid="export-selected"
									@click="onExportSelected"
								>
									导出所选{{ selectedRows.length ? `（${selectedRows.length}）` : '' }}
								</el-button>
							</span>
						</el-tooltip>
						<el-tooltip content="导出当前筛选结果（调用后端 CSV 导出，一次生成全部行）" placement="top">
							<el-button
								plain
								type="primary"
								:icon="Download"
								:loading="exporting"
								data-testid="export-filtered"
								@click="onExportFiltered"
							>
								导出当前筛选结果
							</el-button>
						</el-tooltip>
						<div v-if="exportInfo" class="mold-export-note" role="status" data-testid="export-info">{{ exportInfo }}</div>
						<div class="mold-toolbar-right">
							<el-tooltip content="刷新" placement="top">
								<el-button circle :icon="Refresh" aria-label="刷新" :loading="loading" @click="reload" />
							</el-tooltip>
							<el-tooltip content="筛选" placement="top">
								<el-button class="mold-desktop-only" circle :icon="Search" aria-label="显示或隐藏筛选" @click="mobileFilterOpen = !mobileFilterOpen" />
							</el-tooltip>
							<el-popover v-if="isDesktop" placement="bottom-end" :width="220" trigger="click">
								<template #reference>
									<el-button circle :icon="Operation" aria-label="列设置" />
								</template>
								<div class="mold-col-picker">
									<div class="mold-col-picker-title">显示列（项目名称固定显示）</div>
									<el-checkbox
										v-for="c in pickableColumns"
										:key="c.key"
										:model-value="!hiddenKeys.includes(c.key)"
										:disabled="c.key === 'name'"
										@change="(v: any) => toggleColumn(c.key, !!v)"
									>
										{{ c.label }}
									</el-checkbox>
								</div>
							</el-popover>
							<el-select v-if="isDesktop" v-model="density" style="width: 96px" aria-label="表格密度" @change="saveDensity">
								<el-option label="紧凑" value="compact" />
								<el-option label="标准" value="standard" />
								<el-option label="宽松" value="loose" />
							</el-select>
						</div>
					</div>

					<!-- 四态：无权限 -->
					<StatePanel
						v-if="state === 'forbidden'"
						kind="forbidden"
						title="无公司进度查看权限"
						description="当前账号没有 mold:project:list 权限。服务端仍会对每个请求重新鉴权，前端隐藏入口不是安全控制。"
						action-text="返回首页"
						@action="goHome"
					/>

					<!-- 四态：加载骨架 -->
					<div v-else-if="state === 'loading'" class="mold-skeleton" aria-busy="true" aria-label="正在加载项目列表">
						<div v-for="i in 6" :key="i" class="mold-skel-row">
							<div class="mold-skel-block" style="height: 16px; width: 34%" />
							<div class="mold-skel-block" style="height: 16px; width: 18%" />
							<div class="mold-skel-block" style="height: 16px; width: 22%" />
						</div>
					</div>

					<!-- 四态：失败 -->
					<StatePanel
						v-else-if="state === 'error'"
						kind="error"
						:title="errorInfo.title"
						:description="errorInfo.message"
						:request-id="errorInfo.requestId"
						:action-text="errorActionText"
						@action="onErrorAction"
					/>

					<!-- 四态：空（区分「没有数据」与「筛选没命中」） -->
					<StatePanel
						v-else-if="state === 'empty'"
						kind="empty"
						:title="hasFilters ? '没有符合条件的项目' : '还没有项目'"
						:description="hasFilters ? '当前筛选条件下没有项目，可重置后重试。' : '新建第一个项目后，进度会显示在这里。'"
						:action-text="hasFilters ? '重置筛选' : canCreate ? '新建项目' : ''"
						@action="hasFilters ? onReset() : onCreate()"
					/>

					<!-- 列表：同一份 el-table DOM，≤900px 转卡片 -->
					<div v-else class="mold-list" ref="listRef" :style="{ '--mold-row-pad-y': rowPadY }" @keydown="onListKeydown">
						<p v-if="isNarrowDesktop" class="mold-scroll-hint" data-testid="scroll-hint">
							表格可左右滚动查看更多列；「操作」列固定在右侧，始终可用。
						</p>
						<el-table
							ref="tableRef"
							v-loading="loading"
							:data="rows"
							:border="isDesktop"
							:stripe="isDesktop"
							:show-overflow-tooltip="isDesktop"
							:max-height="isDesktop ? tableMaxHeight : undefined"
							:scrollbar-always-on="isNarrowDesktop"
							row-key="id"
							:default-sort="{ prop: 'code', order: 'ascending' }"
							style="width: 100%"
							:aria-busy="loading ? 'true' : 'false'"
							@sort-change="onSortChange"
							@row-click="onRowClick"
							@selection-change="onSelectionChange"
						>
							<el-table-column v-if="isDesktop" type="selection" width="44" align="center" reserve-selection />
							<el-table-column
								v-for="col in visibleColumns"
								:key="col.key"
								:prop="col.prop"
								:label="col.label"
								:width="colWidths[col.key] ?? col.width"
								:sortable="col.sortable ? 'custom' : false"
								:align="col.align || 'left'"
								:class-name="`col-${col.key}`"
								:show-overflow-tooltip="isDesktop && !!col.ellipsis"
							>
								<!-- 列宽手柄：role=separator，Tab 可达；方向键 ±16 / Shift ±64 / Home 复位；
								     Enter/空格不触发排序（G-13） -->
								<template #header>
									<span class="mold-th-label">{{ col.label }}</span>
									<span
										class="mold-col-resizer"
										role="separator"
										aria-orientation="vertical"
										tabindex="0"
										:aria-label="`调整「${col.label}」列宽：方向键 ±16，Shift+方向键 ±64，Home 复位`"
										:aria-valuenow="colWidths[col.key] ?? col.width"
										aria-valuemin="72"
										aria-valuemax="480"
										:data-col="col.key"
										@pointerdown.stop.prevent="onResizeStart(col, $event)"
										@click.stop
										@dblclick.stop="resetColWidth(col.key)"
										@keydown="onResizerKeydown(col, $event)"
									/>
								</template>
								<template #default="{ row }">
									<span class="cell-label">{{ col.label }}</span>
									<div class="cell-value">
										<template v-if="col.key === 'code'">{{ row.code }}</template>
										<!-- 项目名称：进入详情页（P1-01/P1-07） -->
										<template v-else-if="col.key === 'name'">
											<el-button link type="primary" class="mold-name-link" :title="`${row.name} · 打开详情`" @click.stop="goDetail(row)">
												{{ row.name }}
											</el-button>
										</template>
										<template v-else-if="col.key === 'type'">
											<el-tag :type="row.businessType === 'MOLD_SET' ? 'primary' : 'warning'" effect="plain" size="small">
												{{ businessTypeText(row.businessType) }}
											</el-tag>
										</template>
										<template v-else-if="col.key === 'status'">
											<el-tag :type="statusTone(row.status)" effect="light" size="small">{{ statusText(row.status) }}</el-tag>
										</template>
										<template v-else-if="col.key === 'owner'">{{ row.owner?.displayName || '—' }}</template>
										<template v-else-if="col.key === 'progress'">
											<div class="mold-progress">
												<el-progress :percentage="progressPct(row)" :stroke-width="8" :show-text="false" :status="progressPct(row) === 100 ? 'success' : undefined" />
												<span class="mold-progress-text">{{ row.stageCounts?.completed ?? 0 }} / {{ row.stageCounts?.total ?? 0 }}</span>
											</div>
										</template>
										<template v-else-if="col.key === 'due'">
											<span class="mold-due" :class="{ 'is-over': isOverdue(row) }">
												{{ formatDate(row.dueDate) }}
												<span class="mold-due-note">{{ completionNote(row) }}</span>
											</span>
										</template>
										<template v-else-if="col.key === 'issues'">
											<span :class="{ 'mold-issue-bad': (row.openIssueCount || 0) > 0 }">
												{{ (row.openIssueCount || 0) > 0 ? `${row.openIssueCount} 个` : '—' }}
											</span>
										</template>
										<template v-else-if="col.key === 'updated'">
											<span :title="formatDateTime(row.lastUpdatedAt)">{{ formatDateTime(row.lastUpdatedAt) }}</span>
										</template>
									</div>
								</template>
							</el-table-column>
							<!-- 操作列：详情 + 写操作（编辑/完工/阶段/负责人按 capabilities + 负责人过滤） -->
							<el-table-column label="操作" :width="isDesktop ? 88 : undefined" align="right" :fixed="isDesktop ? 'right' : false" class-name="col-act">
								<template #default="{ row }">
									<!-- 桌面：单一「操作」菜单，避免窄列里两个按钮互相挤成省略号 -->
									<el-button v-if="!isDesktop" link type="primary" @click.stop="goDetail(row)">详情</el-button>
									<ProjectActions :project="row" layout="dropdown" compact @saved="onSaved" @reload="reload" @detail="goDetail(row)" />
								</template>
							</el-table-column>
						</el-table>
					</div>

					<!-- 分页：桌面 offset 分页；390 上一页/下一页 + 第 n/m 页 -->
					<div v-if="state === 'ready'" class="mold-pager">
						<div class="mold-pager-total">共 {{ total }} 条</div>
						<el-pagination
							class="mold-pager-desktop"
							:current-page="pageNum"
							:page-size="pageSize"
							:total="total"
							layout="prev, pager, next, jumper"
							background
							@current-change="onPageChange"
						/>
						<div class="mold-pager-mobile">
							<el-button :icon="ArrowLeft" :disabled="pageNum <= 1" @click="onPageChange(pageNum - 1)">上一页</el-button>
							<span class="mold-pager-info">第 {{ pageNum }} / {{ pageCount }} 页 · 共 {{ total }} 条</span>
							<el-button :disabled="pageNum >= pageCount" @click="onPageChange(pageNum + 1)">
								下一页<el-icon class="el-icon--right"><ArrowRight /></el-icon>
							</el-button>
						</div>
					</div>
				</div>
			</div>
		</div>

		<!-- 新建项目（契约 6.5 POST /projects） -->
		<ProjectCreateDialog v-model="createVisible" @created="onCreated" />
	</div>
</template>

<script setup lang="ts" name="moldProjects">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useEventListener } from '@vueuse/core';
import { ElMessage } from 'element-plus';
import { ArrowLeft, ArrowRight, Download, Operation, Plus, Refresh, RefreshLeft, Search } from '@element-plus/icons-vue';
import StatePanel from '/@/components/mold/StatePanel.vue';
import ProjectActions from '/@/components/mold/ProjectActions.vue';
import ProjectCreateDialog from '/@/components/mold/ProjectCreateDialog.vue';
import { useMoldPermissions } from '/@/api/mold/permissions';
import {
	exportProjectsCsv,
	fetchMockProjectPage,
	fetchProjectPage,
	MoldApiError,
	newRequestId,
	type MoldProjectPage,
	type MoldProjectRow,
} from '/@/api/mold/projects';
import { describeMoldError } from '/@/api/mold/errors';
import { debounceRefresh, useMoldEvent } from '/@/api/mold/events';
import { buildCsv, downloadBlob, downloadCsv, formatDate, formatDateTime, todayStamp } from '/@/utils/moldFormat';

const route = useRoute();
const router = useRouter();
const { can, ensureLoaded } = useMoldPermissions();
const canCreate = computed(() => can('mold:project:create'));

/* ------------------------------ 断点 ------------------------------ */
const viewportWidth = ref(typeof window === 'undefined' ? 1440 : window.innerWidth);
const viewportHeight = ref(typeof window === 'undefined' ? 900 : window.innerHeight);
useEventListener(window, 'resize', () => {
	viewportWidth.value = window.innerWidth;
	viewportHeight.value = window.innerHeight;
	updateTableHeight();
});
const isDesktop = computed(() => viewportWidth.value > 900);
/** 900–1100：表格需要内部横滚（列宽合计超出容器），给一条可发现的文字提示 */
const isNarrowDesktop = computed(() => viewportWidth.value > 900 && viewportWidth.value <= 1100);
/**
 * 1024 档：表格容器内部双向滚动，横向条常驻可见。
 * 用 el-table 自身 max-height 让 body wrapper 成为滚动容器，不改 .page-scroll 的 overflow-x。
 * 高度按「表格在视口里的实际顶部」动态计算，保证横向条落在视口内（不用先滚页面才找得到）。
 * 900 以下转卡片，不设 max-height。
 */
const tableMaxHeight = ref(320);
function updateTableHeight() {
	if (!isDesktop.value) return;
	const el = tableRef.value?.$el as HTMLElement | undefined;
	if (!el) return;
	const top = Math.max(0, el.getBoundingClientRect().top);
	tableMaxHeight.value = Math.max(240, Math.floor(viewportHeight.value - top - 16));
}

/* ------------------------------ 列定义（标签唯一来源） ------------------------------ */
type ColumnDef = {
	key: string;
	label: string;
	prop: string;
	width: number;
	align?: 'left' | 'center' | 'right';
	sortable?: boolean;
	ellipsis?: boolean;
};

const allColumns: ColumnDef[] = [
	{ key: 'code', label: '项目编号', prop: 'code', width: 144, sortable: true },
	{ key: 'name', label: '项目名称', prop: 'name', width: 180, sortable: true },
	{ key: 'type', label: '类型', prop: 'businessType', width: 124, align: 'center', sortable: true },
	{ key: 'status', label: '状态', prop: 'status', width: 82, align: 'center', sortable: true },
	{ key: 'owner', label: '负责人', prop: 'owner.displayName', width: 72, align: 'center' },
	{ key: 'progress', label: '阶段进度', prop: 'stageCounts', width: 96 },
	{ key: 'due', label: '计划交期', prop: 'dueDate', width: 104, align: 'center' },
	{ key: 'issues', label: '未解决异常', prop: 'openIssueCount', width: 92, align: 'center' },
	{ key: 'updated', label: '最近更新', prop: 'lastUpdatedAt', width: 140, align: 'center', sortable: true },
];

const COLUMN_PREF_KEY = 'mold.pro.columns.projects';
const COL_WIDTH_KEY = 'mold.pro.colWidths.projects';
const DENSITY_PREF_KEY = 'mold.pro.density.projects';
const COL_MIN_WIDTH = 72;
const COL_MAX_WIDTH = 480;

function readColWidths(): Record<string, number> {
	try {
		const raw = localStorage.getItem(COL_WIDTH_KEY);
		if (!raw) return {};
		const parsed = JSON.parse(raw);
		if (!parsed || typeof parsed !== 'object') return {};
		const out: Record<string, number> = {};
		for (const [key, value] of Object.entries(parsed)) {
			const n = Number(value);
			if (Number.isFinite(n)) out[key] = Math.min(COL_MAX_WIDTH, Math.max(COL_MIN_WIDTH, Math.round(n)));
		}
		return out;
	} catch {
		return {};
	}
}

/** 列宽（可拖拽 / 键盘调整），初始值来自列定义，用户调整后持久化 */
const colWidths = reactive<Record<string, number>>({});
allColumns.forEach((c) => (colWidths[c.key] = c.width));
Object.assign(colWidths, readColWidths());

function persistColWidths() {
	try {
		localStorage.setItem(COL_WIDTH_KEY, JSON.stringify(colWidths));
	} catch {
		/* 隐私模式忽略 */
	}
}

function setColWidth(key: string, width: number) {
	colWidths[key] = Math.min(COL_MAX_WIDTH, Math.max(COL_MIN_WIDTH, Math.round(width)));
	persistColWidths();
	tableRef.value?.doLayout?.();
}

function resetColWidth(key: string) {
	const def = allColumns.find((c) => c.key === key);
	if (def) setColWidth(key, def.width);
}

/* 拖拽调整列宽（pointer events；只改列宽，不触发排序） */
let resizing: { key: string; startX: number; startWidth: number } | null = null;

function onResizeMove(e: PointerEvent) {
	if (!resizing) return;
	setColWidth(resizing.key, resizing.startWidth + (e.clientX - resizing.startX));
}

function stopResize() {
	resizing = null;
	window.removeEventListener('pointermove', onResizeMove);
	window.removeEventListener('pointerup', stopResize);
}

function onResizeStart(col: ColumnDef, e: PointerEvent) {
	resizing = { key: col.key, startX: e.clientX, startWidth: colWidths[col.key] ?? col.width };
	window.addEventListener('pointermove', onResizeMove);
	window.addEventListener('pointerup', stopResize);
}

/** 键盘操作：Arrow ±16，Shift+Arrow ±64，Home 复位，Enter/空格只阻止冒泡（不排序） */
function onResizerKeydown(col: ColumnDef, e: KeyboardEvent) {
	const step = e.shiftKey ? 64 : 16;
	if (e.key === 'ArrowRight') {
		e.preventDefault();
		e.stopPropagation();
		setColWidth(col.key, (colWidths[col.key] ?? col.width) + step);
	} else if (e.key === 'ArrowLeft') {
		e.preventDefault();
		e.stopPropagation();
		setColWidth(col.key, (colWidths[col.key] ?? col.width) - step);
	} else if (e.key === 'Home') {
		e.preventDefault();
		e.stopPropagation();
		resetColWidth(col.key);
	} else if (e.key === 'Enter' || e.key === ' ') {
		e.preventDefault();
		e.stopPropagation();
	} else if (e.key === 'Escape') {
		(e.target as HTMLElement)?.blur?.();
	}
}

function readHiddenKeys(): string[] {
	try {
		const raw = localStorage.getItem(COLUMN_PREF_KEY);
		if (!raw) return [];
		const parsed = JSON.parse(raw);
		return Array.isArray(parsed) ? parsed.filter((k: string) => k !== 'name') : [];
	} catch {
		return [];
	}
}

const hiddenKeys = ref<string[]>(readHiddenKeys());
const pickableColumns = computed(() => allColumns);
const visibleColumns = computed(() => allColumns.filter((c) => !hiddenKeys.value.includes(c.key)));

function toggleColumn(key: string, visible: boolean) {
	if (key === 'name') return;
	const next = new Set(hiddenKeys.value);
	if (visible) next.delete(key);
	else next.add(key);
	hiddenKeys.value = Array.from(next);
	try {
		localStorage.setItem(COLUMN_PREF_KEY, JSON.stringify(hiddenKeys.value));
	} catch {
		/* 隐私模式忽略 */
	}
}

/* ------------------------------ 密度 ------------------------------ */
type Density = 'compact' | 'standard' | 'loose';
const density = ref<Density>((localStorage.getItem(DENSITY_PREF_KEY) as Density) || 'standard');
const rowPadY = computed(() => (density.value === 'compact' ? '9px' : density.value === 'loose' ? '17px' : '13px'));

function saveDensity() {
	try {
		localStorage.setItem(DENSITY_PREF_KEY, density.value);
	} catch {
		/* 隐私模式忽略 */
	}
}

/* ------------------------------ 查询状态 ------------------------------ */
const scrollRef = ref<HTMLElement>();
const tableRef = ref<any>();
const listRef = ref<HTMLElement>();
const rows = ref<MoldProjectRow[]>([]);
const total = ref(0);
const pageNum = ref(1);
// DEV 才允许用 query 覆盖，便于核对 422 越界分页；生产固定 20
const devPageSize = import.meta.env.DEV ? Number(route.query.pageSize) : 0;
const pageSize = ref(devPageSize > 0 ? devPageSize : 20);
const sortKey = ref('');
const sortOrder = ref<'asc' | 'desc'>('asc');
const state = ref<'loading' | 'ready' | 'empty' | 'error' | 'forbidden'>('loading');
const loading = ref(false);
const exporting = ref(false);
const exportingSelected = ref(false);
const usingMock = ref(false);
const mobileFilterOpen = ref(false);
const errorAction = ref<'retry' | 'login'>('retry');
const errorInfo = reactive({ title: '加载失败', message: '', requestId: '' });
const exportInfo = ref('');
const selectedRows = ref<MoldProjectRow[]>([]);

const filters = reactive({
	q: String(route.query.q || ''),
	businessType: '',
	status: '',
});

const hasFilters = computed(() => !!(filters.q || filters.businessType || filters.status));
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)));
const errorActionText = computed(() => (errorAction.value === 'login' ? '重新登录' : '重试'));

/* ------------------------------ 统计（本页行） ------------------------------ */
const stats = computed(() => {
	const pageRows = rows.value;
	return [
		{ key: 'total', label: '本页项目', value: pageRows.length, unit: '个', foot: `第 ${pageNum.value} 页 · 共 ${total.value} 条`, tone: '' },
		{ key: 'active', label: '本页进行中', value: pageRows.filter((r) => r.status === 'ACTIVE').length, unit: '个', foot: '本页 · 进行中', tone: '' },
		{ key: 'done', label: '本页已完工', value: pageRows.filter((r) => r.status === 'COMPLETED').length, unit: '个', foot: '本页 · 已完工', tone: 'is-ok' },
		{
			key: 'blocked',
			label: '本页有受阻阶段',
			value: pageRows.filter((r) => (r.blockedStageCount ?? r.stageCounts?.blocked ?? 0) > 0).length,
			unit: '个',
			foot: '本页 · 有受阻阶段',
			tone: 'is-bad',
		},
	];
});

/* ------------------------------ 文案与格式化 ------------------------------ */
const businessTypeText = (t: string) => ({ MOLD_SET: '整套模具制作', PART_PROCESS: '零件·工序加工' } as Record<string, string>)[t] || t || '—';
const statusText = (s: string) =>
	({ ACTIVE: '进行中', ON_HOLD: '已暂停', COMPLETED: '已完工', CANCELLED: '已取消' } as Record<string, string>)[s] || s || '—';
const statusTone = (s: string) =>
	({ ACTIVE: 'primary', ON_HOLD: 'warning', COMPLETED: 'success', CANCELLED: 'info' } as Record<string, any>)[s] || 'info';

const progressPct = (row: MoldProjectRow) => {
	const totalStages = row.stageCounts?.total || 0;
	const done = row.stageCounts?.completed || 0;
	return totalStages > 0 ? Math.min(100, Math.round((done / totalStages) * 100)) : 0;
};

const completionNote = (row: MoldProjectRow) => {
	if (row.status === 'COMPLETED') return '已确认';
	if ((row.stageCounts?.total || 0) > 0 && row.stageCounts.completed === row.stageCounts.total) return '阶段已完成待确认';
	return '未确认';
};

const isOverdue = (row: MoldProjectRow) => {
	if (!row.dueDate || row.status === 'COMPLETED' || row.status === 'CANCELLED') return false;
	return row.dueDate < todayStamp();
};

/* ------------------------------ 数据加载 ------------------------------ */
const demoState = computed(() => (import.meta.env.DEV ? String(route.query.demo || '') : ''));
const mockAllowed = computed(
	() => import.meta.env.DEV && (import.meta.env.VITE_MOLD_MOCK_FALLBACK === 'true' || String(route.query.mock || '') === '1')
);
let reqSeq = 0;

function baseQuery() {
	return {
		q: filters.q.trim() || undefined,
		businessType: filters.businessType || undefined,
		status: filters.status || undefined,
		sort: sortKey.value || undefined,
		order: sortOrder.value,
	};
}

function localSort(list: MoldProjectRow[]): MoldProjectRow[] {
	if (!sortKey.value) return list.sort((a, b) => a.code.localeCompare(b.code) || a.id.localeCompare(b.id));
	const key = sortKey.value as keyof MoldProjectRow;
	const dir = sortOrder.value === 'desc' ? -1 : 1;
	return list.sort((a, b) => {
		const av = String((a as any)[key] ?? '');
		const bv = String((b as any)[key] ?? '');
		return av === bv ? a.id.localeCompare(b.id) : av > bv ? dir : -dir;
	});
}

function filterByQ(list: MoldProjectRow[]): MoldProjectRow[] {
	const q = filters.q.trim().toLowerCase();
	if (!q) return list;
	return list.filter((r) => r.code.toLowerCase().includes(q) || r.name.toLowerCase().includes(q));
}

async function loadPage(): Promise<{ page: MoldProjectPage; mock: boolean }> {
	try {
		// 07 已实现服务端 q / status / businessType / sort，全部下推，不再拉全量本地过滤
		return { page: await fetchProjectPage({ pageNum: pageNum.value, pageSize: pageSize.value, ...baseQuery() }), mock: false };
	} catch (err) {
		// 仅 DEV 开关：真实接口不可达时降级 mock，页面顶部必须显示提示条
		if (mockAllowed.value && err instanceof MoldApiError && (err.status === 0 || err.status === 404)) {
			const mock = await fetchMockProjectPage({ pageNum: pageNum.value, pageSize: pageSize.value, ...baseQuery() });
			return { page: mock, mock: true };
		}
		throw err;
	}
}

async function loadData(firstLoad = false) {
	const seq = ++reqSeq;
	if (firstLoad) state.value = 'loading';
	else loading.value = true;
	errorInfo.title = '加载失败';
	errorInfo.message = '';
	errorInfo.requestId = '';
	errorAction.value = 'retry';

	try {
		if (demoState.value === 'forbidden') throw new MoldApiError('无公司进度查看权限', 403, 'FORBIDDEN');
		if (demoState.value === 'error') throw new MoldApiError('网络请求超时，请检查网络后重试。', 0, 'NETWORK_ERROR', newRequestId());
		if (demoState.value === '401') throw new MoldApiError('登录已过期', 401, 'UNAUTHORIZED', newRequestId());

		let result: { page: MoldProjectPage; mock: boolean };
		if (demoState.value === 'empty') result = { page: await fetchMockProjectPage({ pageNum: 1, pageSize: pageSize.value, ...baseQuery() }, true), mock: true };
		else result = await loadPage();

		if (seq !== reqSeq) return;
		const page = result.page;
		usingMock.value = result.mock;
		rows.value = page.rows;
		total.value = page.total;
		state.value = page.rows.length > 0 ? 'ready' : 'empty';
		selectedRows.value = [];
		tableRef.value?.clearSelection?.();

		// 越界页：服务端返回空 rows + 正确 total，回到最后一页（规范 6.1）
		if (page.rows.length === 0 && page.total > 0 && pageNum.value > pageCount.value) {
			pageNum.value = pageCount.value;
			if (seq === reqSeq) loadData(false);
		}
	} catch (err: any) {
		if (seq !== reqSeq) return;
		usingMock.value = false;
		// 403 不是登录失效：不登出、不清空已加载数据；只有普通 403 才显示无权限页
		if (err instanceof MoldApiError && err.status === 403 && err.code !== 'PASSWORD_CHANGE_REQUIRED') {
			state.value = 'forbidden';
			return;
		}
		const view = describeMoldError(err, '服务暂时不可用，请稍后重试。');
		state.value = 'error';
		errorAction.value = view.action === 'login' ? 'login' : 'retry';
		errorInfo.title = view.title;
		errorInfo.message = view.message;
		errorInfo.requestId = view.requestId;
	} finally {
		if (seq === reqSeq) {
			loading.value = false;
			await nextTick();
			updateTableHeight();
			tableRef.value?.doLayout?.();
		}
	}
}

function reload() {
	loadData(false);
}

function onQuery() {
	pageNum.value = 1;
	exportInfo.value = '';
	loadData(false);
}

function onReset() {
	filters.q = '';
	filters.businessType = '';
	filters.status = '';
	pageNum.value = 1;
	exportInfo.value = '';
	loadData(false);
}

function onPageChange(next: number) {
	if (next < 1 || next > pageCount.value) return;
	pageNum.value = next;
	loadData(false);
	scrollRef.value?.scrollTo({ top: 0, behavior: 'auto' });
}

function onSortChange({ prop, order }: { prop: string; order: string | null }) {
	if (!order) {
		sortKey.value = '';
		sortOrder.value = 'asc';
	} else {
		sortKey.value = prop;
		sortOrder.value = order === 'descending' ? 'desc' : 'asc';
	}
	pageNum.value = 1;
	loadData(false);
}

function onErrorAction() {
	if (errorAction.value === 'login') {
		router.push('/login');
		return;
	}
	reload();
}

/* ------------------------------ 行为：详情 / 写操作 ------------------------------ */
function goDetail(row: MoldProjectRow) {
	if (usingMock.value || row.id.startsWith('mock-')) {
		ElMessage.info('示例数据没有详情页；真实接口恢复后可用');
		return;
	}
	router.push(`/mold/project/${encodeURIComponent(row.id)}`);
}

function onRowClick(row: MoldProjectRow, _column: any, event: MouseEvent) {
	const target = event.target as HTMLElement;
	if (target?.closest('button, a, input, label, .el-checkbox, .el-dropdown')) return;
	goDetail(row);
}

/* 行键盘：Enter/空格进详情（替换第一切片的预览抽屉） */
function onListKeydown(e: KeyboardEvent) {
	if (e.key !== 'Enter' && e.key !== ' ') return;
	const el = e.target as HTMLElement;
	if (el?.closest?.('button, a, input, label, .el-dropdown')) return;
	const tr = el?.closest?.('tr.el-table__row') as HTMLElement | null;
	if (!tr) return;
	const all = [...(tr.parentElement?.querySelectorAll('tr.el-table__row') || [])];
	const row = rows.value[all.indexOf(tr)];
	if (!row) return;
	e.preventDefault();
	goDetail(row);
}

function syncRowFocusability() {
	nextTick(() => {
		const trs = listRef.value?.querySelectorAll<HTMLElement>('tbody tr.el-table__row') || [];
		trs.forEach((tr, i) => {
			const row = rows.value[i];
			tr.tabIndex = 0;
			tr.setAttribute('aria-label', row ? `打开项目 ${row.name} 详情` : '打开项目详情');
		});
	});
}
watch([rows, loading], syncRowFocusability);

function onSelectionChange(selection: MoldProjectRow[]) {
	selectedRows.value = selection;
}

function onSaved(row: MoldProjectRow) {
	rows.value = rows.value.map((r) => (r.id === row.id ? row : r));
	exportInfo.value = '';
	loadData(false);
}

/* ------------------------------ 导出（D1：真导全量） ------------------------------ */
function toCsv(rowsToWrite: MoldProjectRow[]) {
	return buildCsv(
		['项目编号', '项目名称', '类型', '负责人', '已完成阶段', '阶段总数', '交期', '状态'],
		rowsToWrite.map((r) => [
			r.code,
			r.name,
			businessTypeText(r.businessType),
			r.owner?.displayName || '',
			r.stageCounts?.completed ?? 0,
			r.stageCounts?.total ?? 0,
			r.dueDate || '',
			statusText(r.status),
		])
	);
}

async function collectAllFiltered() {
	if (usingMock.value) {
		const all = await fetchMockProjectPage({ pageNum: 1, pageSize: 1000, ...baseQuery() });
		const filtered = localSort(filterByQ(all.rows));
		return { rows: filtered, total: filtered.length, pagesFetched: 1, pageSize: 1000 };
	}
	// 真实接口：导出由后端一次生成，这里不再逐页拼接
	return null;
}

async function onExportSelected() {
	if (!selectedRows.value.length) return;
	exportingSelected.value = true;
	try {
		const list = selectedRows.value.slice();
		const csv = toCsv(list);
		downloadCsv(`projects-selected-${todayStamp()}.csv`, csv);
		exportInfo.value = `已导出所选 ${list.length} 行（当前页勾选）`;
		ElMessage.success(`已导出所选 ${list.length} 个项目`);
	} catch (err: any) {
		ElMessage.error(err?.message || '导出失败，请重试');
	} finally {
		exportingSelected.value = false;
	}
}

async function onExportFiltered() {
	exporting.value = true;
	try {
		if (usingMock.value) {
			const all = await collectAllFiltered();
			downloadCsv(`projects-${todayStamp()}.csv`, toCsv(all?.rows || []));
			exportInfo.value = `DEV 示例数据：本地导出 ${all?.rows.length || 0} 行（真实接口恢复后走后端 CSV）`;
			ElMessage.success('示例数据已导出（本地）');
			return;
		}
		// 修复轮：直接调用后端 GET /admin/mold/projects/export，不再逐页拼
		const { blob, filename } = await exportProjectsCsv(baseQuery());
		downloadBlob(filename, blob);
		exportInfo.value = `已调用后端导出 CSV：${filename}（${(blob.size / 1024).toFixed(1)} KB，当前筛选条件）`;
		ElMessage.success('已导出当前筛选结果（后端生成）');
	} catch (err: any) {
		ElMessage.error(describeMoldError(err, '导出失败，请重试').message);
	} finally {
		exporting.value = false;
	}
}

/* ------------------------------ 其他入口 ------------------------------ */
const createVisible = ref(false);

function onCreate() {
	createVisible.value = true;
}

/** 新建成功：先刷列表，再直接进详情（契约 6.5 POST /projects） */
function onCreated(row: MoldProjectRow) {
	createVisible.value = false;
	pageNum.value = 1;
	loadData(false);
	router.push(`/mold/project/${encodeURIComponent(row.id)}`);
}

function goHome() {
	router.push('/home');
}

/* ------------------------------ 生命周期 ------------------------------ */
watch(
	() => route.query.q,
	(q) => {
		const next = String(q || '');
		if (next !== filters.q) {
			filters.q = next;
			pageNum.value = 1;
			loadData(false);
		}
	}
);

watch(isDesktop, async () => {
	await nextTick();
	updateTableHeight();
	tableRef.value?.doLayout?.();
});

onMounted(() => {
	ensureLoaded();
	loadData(true);
	nextTick(updateTableHeight);
});

/* SSE：项目/阶段/异常变更只重取当前页列表，不整页刷新 */
const refreshProjectsOnEvent = debounceRefresh(() => loadData(false), 800);
useMoldEvent(['project.created', 'project.completed', 'project.members.updated', 'project.owner.transferred', 'stage.created', 'stage.updated', 'stage.transitioned', 'issue.created', 'issue.updated'], refreshProjectsOnEvent);
</script>

<style scoped lang="scss">
.mold-col-picker {
	display: flex;
	flex-direction: column;
	gap: 6px;
	.mold-col-picker-title {
		font-size: 12px;
		line-height: 18px;
		color: var(--el-text-color-secondary);
		margin-bottom: 2px;
	}
}
.mold-name-link {
	max-width: 100%;
	justify-content: flex-start;
	text-align: left;
	white-space: normal;
}
.mold-issue-bad {
	color: var(--el-color-danger);
	font-weight: 600;
}
.mold-skel-row {
	display: flex;
	gap: 12px;
	align-items: center;
	padding: 14px 12px;
	background: var(--el-bg-color);
	border: 1px solid var(--el-border-color-lighter);
	border-radius: var(--mold-radius-card);
	margin-bottom: 8px;
}
.mold-export-note {
	font-size: 12px;
	line-height: 18px;
	color: var(--el-text-color-secondary);
	flex-basis: 100%;
}
/* 列宽手柄：视觉 6px，命中区 14px；键盘聚焦时显示主色条 */
.mold-th-label {
	display: inline-block;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
	vertical-align: middle;
}
.mold-col-resizer {
	position: absolute;
	top: 0;
	right: -7px;
	width: 14px;
	height: 100%;
	cursor: col-resize;
	z-index: 2;
	background: transparent;
	touch-action: none;
	&::after {
		content: '';
		position: absolute;
		top: 20%;
		left: 6px;
		width: 2px;
		height: 60%;
		border-radius: 1px;
		background: var(--el-border-color);
		transition: background-color 0.15s ease;
	}
	&:hover::after,
	&:focus-visible::after {
		background: var(--el-color-primary);
	}
	&:focus-visible {
		outline: 2px solid var(--el-color-primary);
		outline-offset: -2px;
	}
}
</style>
