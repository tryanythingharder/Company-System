<template>
	<div class="layout-padding">
		<div class="layout-padding-auto layout-padding-view">
			<div ref="scrollRef" class="page-scroll">
				<div class="mold-page">
					<div class="mold-page-head">
						<div>
							<h1>我的工作</h1>
							<p>
								我是责任人 / 成员的阶段，按项目分组；只能推进自己被指派的阶段。
								<span v-if="state !== 'forbidden' && state !== 'error'" class="mold-page-total">
									共 {{ state === 'loading' ? '—' : total }} 个阶段
								</span>
							</p>
						</div>
						<div class="mold-page-head-actions">
							<el-button class="mold-mobile-filter-btn" :icon="Search" @click="filterOpen = !filterOpen">
								{{ filterOpen ? '收起筛选' : '筛选' }}
							</el-button>
							<el-button circle :icon="Refresh" aria-label="刷新我的工作" :loading="loading" @click="reload" />
						</div>
					</div>

					<el-row v-if="state !== 'forbidden' && state !== 'error'" :gutter="16">
						<el-col v-for="s in stats" :key="s.key" :xs="12" :sm="12" :md="6">
							<div class="mold-stat">
								<div class="mold-stat-label">{{ s.label }}</div>
								<div class="mold-stat-value" :class="s.tone">{{ s.value }}<span class="mold-stat-unit">个</span></div>
								<div class="mold-stat-foot">{{ s.foot }}</div>
							</div>
						</el-col>
					</el-row>

					<div v-if="state !== 'forbidden' && state !== 'error'" class="mold-filter-bar" :class="{ 'is-open': filterOpen }">
						<el-form :inline="true" @submit.prevent="onQuery">
							<el-form-item label="阶段状态">
								<el-select v-model="filters.status" placeholder="全部" clearable style="width: 140px">
									<el-option label="未开始" value="NOT_STARTED" />
									<el-option label="进行中" value="IN_PROGRESS" />
									<el-option label="受阻" value="BLOCKED" />
									<el-option label="已完成" value="COMPLETED" />
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
						title="无我的工作权限"
						description="当前账号没有 mold:project:list / mold:stage:mine 权限。服务端仍会对每个请求重新鉴权。"
						action-text="返回首页"
						@action="goHome"
					/>
					<div v-else-if="state === 'loading'" class="mold-skeleton" aria-busy="true" aria-label="正在加载我的阶段">
						<div v-for="i in 4" :key="i" class="mold-skel-block" style="height: 96px; width: 100%; margin-bottom: 10px" />
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
						title="暂时没有分配给你的阶段"
						:description="hasFilters ? '当前筛选下没有阶段，可重置后重试。' : '等项目负责人把阶段责任人指派给你后，这里会显示可推进的工作。'"
						:action-text="hasFilters ? '重置筛选' : ''"
						@action="onReset"
					/>

					<div v-else class="mold-mywork">
						<section v-for="group in groups" :key="group.projectId" class="mold-work-group" :aria-label="`项目 ${group.projectName}`">
							<header class="mold-work-group-head">
								<div class="mold-work-project">
									<el-button link type="primary" class="mold-work-project-name" @click="goProject(group.projectId)">
										{{ group.projectName }}
									</el-button>
									<span class="mold-work-project-code">{{ group.projectCode }}</span>
								</div>
								<div class="mold-work-group-meta">
									<span>{{ group.stages.length }} 个阶段</span>
									<span v-if="group.blocked">· {{ group.blocked }} 个受阻</span>
								</div>
							</header>

							<ul class="mold-work-list">
								<li v-for="stage in group.stages" :key="stage.id" class="mold-work-item">
									<div class="mold-work-main">
										<div class="mold-work-title">
											<span class="mold-work-sort">{{ stage.sortOrder }}.</span>
											<span class="mold-work-name">{{ stage.name }}</span>
											<StatusTag kind="stage" :value="stage.status" />
										</div>
										<div class="mold-work-meta">
											<span>计划 {{ formatDate(stage.plannedStart) }} ~ {{ formatDate(stage.plannedEnd) }}</span>
											<span v-if="stage.actualEnd">完成于 {{ formatDate(stage.actualEnd) }}</span>
											<span v-if="stage.note" class="mold-work-note" :title="stage.note">{{ stage.note }}</span>
										</div>
									</div>
									<div class="mold-work-actions">
										<el-button link type="primary" size="small" @click="goHistory(stage)">历史</el-button>
										<el-button
											v-for="action in stage.allowedActions || []"
											:key="action.action"
											size="small"
											:type="action.action === 'BLOCK' ? 'warning' : action.action === 'COMPLETE' ? 'success' : 'primary'"
											:plain="action.action !== 'COMPLETE'"
											@click="openTransition(stage, action)"
										>
											{{ actionText(action.action) }}
										</el-button>
									</div>
								</li>
							</ul>
						</section>

						<div class="mold-pager">
							<div class="mold-pager-total">共 {{ total }} 个阶段</div>
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
		</div>

		<!-- 推进阶段 -->
		<el-dialog
			v-model="transitionVisible"
			:title="`${pendingAction ? actionText(pendingAction.action) : ''}：${pendingStage?.name || ''}`"
			:width="dialogWidth"
			:close-on-click-modal="false"
			append-to-body
			class="mold-dialog"
		>
			<el-alert v-if="transitionError" type="error" :closable="false" show-icon :title="transitionError" class="mold-dialog-alert" />
			<el-form label-position="top" @submit.prevent="submitTransition">
				<el-form-item label="当前状态">
					<StatusTag v-if="pendingStage" kind="stage" :value="pendingStage.status" />
				</el-form-item>
				<el-form-item :label="pendingAction?.reasonRequired ? '受阻原因（必填）' : '说明（可选）'" :error="transitionFieldError">
					<el-input
						v-model="transitionNote"
						type="textarea"
						:rows="3"
						maxlength="2000"
						show-word-limit
						:placeholder="pendingAction?.reasonRequired ? '说明受阻原因，便于项目负责人处理' : '可选，写入阶段历史'"
					/>
				</el-form-item>
				<p class="mold-form-hint">提交后写入阶段时间线并产生事件；同一次提交带幂等键，重复点击不会重复推进。</p>
				<div class="mold-dialog-actions">
					<el-button @click="transitionVisible = false">取消</el-button>
					<el-button
						type="primary"
						native-type="submit"
						:loading="transitionSubmitting"
						:disabled="!!pendingAction?.reasonRequired && !transitionNote.trim()"
					>
						确认{{ pendingAction ? actionText(pendingAction.action) : '' }}
					</el-button>
				</div>
			</el-form>
		</el-dialog>
	</div>
</template>

<script setup lang="ts" name="moldMyStages">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { ArrowLeft, ArrowRight, Refresh, RefreshLeft, Search } from '@element-plus/icons-vue';
import StatePanel from '/@/components/mold/StatePanel.vue';
import StatusTag from '/@/components/mold/StatusTag.vue';
import { useMoldPermissions } from '/@/api/mold/permissions';
import { fetchProjectPage, MoldApiError, type MoldProjectRow } from '/@/api/mold/projects';
import { fetchMyStages, transitionStage, type MoldStageAction, type MoldStageRow } from '/@/api/mold/stages';
import { debounceRefresh, useMoldEvent } from '/@/api/mold/events';
import { describeMoldError } from '/@/api/mold/errors';
import { useMoldList } from '/@/utils/moldList';
import { formatDate } from '/@/utils/moldFormat';
import { beginIdempotentAction, finishIdempotentAction, stableHash } from '/@/utils/idempotency';

const router = useRouter();
const { ensureLoaded } = useMoldPermissions();

const filters = reactive({ status: '' });
const filterOpen = ref(false);
const projects = ref<MoldProjectRow[]>([]);

const list = useMoldList<MoldStageRow>({
	fetch: (query) => fetchMyStages(query as any),
	filters: () => ({ status: filters.status || undefined }),
	sortable: ['name', 'status', 'sortOrder', 'updatedAt'],
	defaultSort: { key: 'id', order: 'asc' },
});
const { rows, total, pageNum, pageSize, pageCount, state, loading, errorInfo, errorAction, hasFilters } = list;

const stats = computed(() => {
	const page = rows.value;
	return [
		{ key: 'total', label: '本页阶段', value: page.length, foot: `第 ${pageNum.value} 页`, tone: '' },
		{ key: 'doing', label: '进行中', value: page.filter((s) => s.status === 'IN_PROGRESS').length, foot: '可标记完成或受阻', tone: '' },
		{ key: 'blocked', label: '受阻', value: page.filter((s) => s.status === 'BLOCKED').length, foot: '需要处理', tone: 'is-bad' },
		{ key: 'done', label: '已完成', value: page.filter((s) => s.status === 'COMPLETED').length, foot: '本页', tone: 'is-ok' },
	];
});

const projectMap = computed(() => {
	const map = new Map<string, MoldProjectRow>();
	projects.value.forEach((p) => map.set(p.id, p));
	return map;
});

const groups = computed(() => {
	const map = new Map<string, { projectId: string; projectName: string; projectCode: string; stages: MoldStageRow[]; blocked: number }>();
	for (const stage of rows.value) {
		const project = projectMap.value.get(stage.projectId);
		let group = map.get(stage.projectId);
		if (!group) {
			group = {
				projectId: stage.projectId,
				projectName: project?.name || '未命名项目',
				projectCode: project?.code || stage.projectId.slice(0, 8),
				stages: [],
				blocked: 0,
			};
			map.set(stage.projectId, group);
		}
		group.stages.push(stage);
		if (stage.status === 'BLOCKED') group.blocked += 1;
	}
	return [...map.values()].sort((a, b) => a.projectCode.localeCompare(b.projectCode));
});

const ACTION_TEXT: Record<string, string> = { START: '开始', BLOCK: '标记受阻', RESUME: '解除受阻', COMPLETE: '完成' };
const actionText = (action: string) => ACTION_TEXT[action] || action;

/* ------------------------------ 推进 ------------------------------ */
const transitionVisible = ref(false);
const transitionSubmitting = ref(false);
const transitionError = ref('');
const transitionFieldError = ref('');
const transitionNote = ref('');
const pendingStage = ref<MoldStageRow | null>(null);
const pendingAction = ref<MoldStageAction | null>(null);
const dialogWidth = computed(() => (window.innerWidth <= 900 ? '94%' : '480px'));

function openTransition(stage: MoldStageRow, action: MoldStageAction) {
	pendingStage.value = stage;
	pendingAction.value = action;
	transitionNote.value = '';
	transitionError.value = '';
	transitionFieldError.value = '';
	transitionVisible.value = true;
}

async function submitTransition() {
	if (!pendingStage.value || !pendingAction.value) return;
	if (pendingAction.value.reasonRequired && !transitionNote.value.trim()) {
		transitionFieldError.value = '标记受阻必须填写原因';
		return;
	}
	transitionSubmitting.value = true;
	transitionError.value = '';
	transitionFieldError.value = '';
	try {
		const body = {
			toStatus: pendingAction.value.targetStatus,
			note: transitionNote.value.trim() || null,
		};
		// 同一用户动作（阶段 + 目标状态 + 说明）复用同一 Idempotency-Key：
		// 双击或失败重试交给服务端重放首次响应，不会重复流转。
		const scope = `stage.transition:${pendingStage.value.id}:${stableHash(JSON.stringify(body))}`;
		await transitionStage(pendingStage.value.id, body, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success(`已${actionText(pendingAction.value.action)}`);
		transitionVisible.value = false;
		list.reload();
	} catch (err: any) {
		if (err instanceof MoldApiError && err.fieldErrors?.note) transitionFieldError.value = err.fieldErrors.note;
		transitionError.value = describeMoldError(err, '推进失败，请重试').message;
	} finally {
		transitionSubmitting.value = false;
	}
}

/* ------------------------------ 其他 ------------------------------ */
async function loadProjects() {
	try {
		const page = await fetchProjectPage({ pageNum: 1, pageSize: 100, sort: 'code', order: 'asc' });
		projects.value = page.rows;
	} catch {
		// 项目目录失败不影响阶段列表；分组回退显示项目 id 前 8 位
	}
}

function onQuery() {
	list.query();
}
function onReset() {
	filters.status = '';
	list.reset();
}
function onErrorAction() {
	if (errorAction.value === 'login') router.push('/login');
	else list.reload();
}
function goHome() {
	router.push('/home');
}
function goProject(id: string) {
	router.push(`/mold/project/${encodeURIComponent(id)}`);
}
function goHistory(stage: MoldStageRow) {
	router.push({
		path: `/mold/stages/${encodeURIComponent(stage.id)}`,
		query: { projectId: stage.projectId, name: stage.name },
	});
}

onMounted(() => {
	ensureLoaded();
	loadProjects();
	list.load(true);
});

/* SSE：阶段状态变化只刷「我的工作」列表 */
const refreshMyStagesOnEvent = debounceRefresh(() => list.reload(), 700);
useMoldEvent(['stage.created', 'stage.updated', 'stage.transitioned', 'project.members.updated'], refreshMyStagesOnEvent);
</script>

<style scoped lang="scss">
.mold-mywork {
	display: flex;
	flex-direction: column;
	gap: 12px;
}
.mold-work-group {
	border: 1px solid var(--el-border-color-lighter);
	border-radius: var(--mold-radius-card);
	background: var(--el-bg-color);
	overflow: hidden;
}
.mold-work-group-head {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	justify-content: space-between;
	gap: 8px;
	padding: 10px 14px;
	background: var(--el-fill-color-lighter);
	border-bottom: 1px solid var(--el-border-color-lighter);
}
.mold-work-project {
	display: flex;
	align-items: baseline;
	gap: 8px;
	min-width: 0;
}
.mold-work-project-name {
	font-size: 15px;
	font-weight: 600;
	max-width: 100%;
	white-space: normal;
	text-align: left;
}
.mold-work-project-code {
	font-size: 12px;
	color: var(--el-text-color-secondary);
}
.mold-work-group-meta {
	font-size: 12px;
	color: var(--el-text-color-secondary);
}
.mold-work-list {
	list-style: none;
	margin: 0;
	padding: 0;
}
.mold-work-item {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 12px;
	padding: 12px 14px;
	border-top: 1px solid var(--el-border-color-lighter);
	&:first-child {
		border-top: 0;
	}
}
.mold-work-main {
	min-width: 0;
	flex: 1;
}
.mold-work-title {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	gap: 8px;
	font-size: 14px;
	line-height: 22px;
}
.mold-work-sort {
	color: var(--el-text-color-secondary);
}
.mold-work-name {
	font-weight: 600;
	overflow-wrap: anywhere;
}
.mold-work-meta {
	display: flex;
	flex-wrap: wrap;
	gap: 10px;
	margin-top: 2px;
	font-size: 12px;
	line-height: 18px;
	color: var(--el-text-color-secondary);
}
.mold-work-note {
	max-width: 100%;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}
.mold-work-actions {
	display: flex;
	flex-wrap: wrap;
	gap: 4px;
	flex: none;
	align-items: center;
}
@media (max-width: 900px) {
	.mold-work-item {
		flex-direction: column;
	}
	.mold-work-actions {
		width: 100%;
		justify-content: flex-end;
	}
}
</style>
