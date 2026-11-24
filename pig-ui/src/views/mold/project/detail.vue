<template>
	<div class="layout-padding">
		<div class="layout-padding-auto layout-padding-view">
			<div ref="scrollRef" class="page-scroll">
				<div class="mold-page mold-detail">
					<div class="mold-detail-back">
						<el-button link type="primary" :icon="ArrowLeft" data-testid="detail-back" @click="goBack">返回公司进度</el-button>
					</div>

					<!-- 加载 -->
					<div v-if="state === 'loading'" class="mold-skeleton" aria-busy="true" aria-label="正在加载项目详情">
						<div class="mold-skel-block" style="height: 32px; width: 42%; margin-bottom: 16px" />
						<div class="mold-skel-block" style="height: 120px; width: 100%; margin-bottom: 12px" />
						<div class="mold-skel-block" style="height: 160px; width: 100%" />
					</div>

					<!-- 无权限 -->
					<StatePanel
						v-else-if="state === 'forbidden'"
						kind="forbidden"
						title="无项目查看权限"
						description="当前账号没有查看项目的权限，或该项目不在你的可见范围内。如需查看，请联系老板开通。"
						action-text="返回公司进度"
						@action="goBack"
					/>

					<!-- 失败（含 404 深链不存在的项目） -->
					<StatePanel
						v-else-if="state === 'error'"
						kind="error"
						:title="errorInfo.title"
						:description="errorInfo.message"
						:request-id="errorInfo.requestId"
						:action-text="errorInfo.title === '项目不存在' ? '返回公司进度' : '重试'"
						@action="errorInfo.title === '项目不存在' ? goBack() : loadDetail()"
					/>

					<template v-else-if="project">
						<!-- 页头 -->
						<div class="mold-detail-head">
							<div class="mold-detail-title">
								<div class="mold-detail-code">{{ project.code }}</div>
								<h1>{{ project.name }}</h1>
								<div class="mold-detail-tags">
									<el-tag :type="statusTone(project.status)" effect="light">{{ statusText(project.status) }}</el-tag>
									<el-tag :type="project.businessType === 'MOLD_SET' ? 'primary' : 'warning'" effect="plain">
										{{ businessTypeText(project.businessType) }}
									</el-tag>
									<span class="mold-detail-version">v{{ project.version }}</span>
								</div>
							</div>
							<div class="mold-detail-actions">
								<ProjectActions :project="project" layout="buttons" @saved="onSaved" @reload="loadDetail" />
							</div>
						</div>

						<!-- 概览 -->
						<div class="mold-detail-grid">
							<div class="mold-detail-card">
								<div class="mold-detail-card-title">基本信息</div>
								<dl class="mold-kv">
									<div><dt>项目负责人</dt><dd>{{ project.owner?.displayName || '—' }}</dd></div>
									<div><dt>业务类型</dt><dd>{{ businessTypeText(project.businessType) }}</dd></div>
									<div><dt>计划交期</dt><dd :class="{ 'is-over': overdue }">{{ formatDate(project.dueDate) }}</dd></div>
									<div><dt>最近更新</dt><dd>{{ formatDateTime(project.lastUpdatedAt) }}</dd></div>
									<div><dt>数据版本</dt><dd>v{{ project.version }}（If-Match）</dd></div>
								</dl>
							</div>

							<!-- 阶段进度（P1-11）：4 类阶段数来自详情 DTO -->
							<div class="mold-detail-card">
								<div class="mold-detail-card-title">
									阶段进度
									<span class="mold-detail-card-note">共 {{ stageCounts.total }} 个阶段</span>
								</div>
								<el-progress
									:percentage="progressPct"
									:stroke-width="10"
									:status="progressPct === 100 ? 'success' : undefined"
									:show-text="false"
								/>
								<div class="mold-stage-counts">
									<div class="mold-stage-count is-notstarted"><span class="n">{{ stageCounts.notStarted }}</span><span class="l">未开始</span></div>
									<div class="mold-stage-count is-progress"><span class="n">{{ stageCounts.inProgress }}</span><span class="l">进行中</span></div>
									<div class="mold-stage-count is-blocked"><span class="n">{{ stageCounts.blocked }}</span><span class="l">受阻</span></div>
									<div class="mold-stage-count is-done"><span class="n">{{ stageCounts.completed }}</span><span class="l">已完成</span></div>
								</div>
								<div v-if="!stagesAvailable" class="mold-detail-pending" data-testid="stages-pending">
									阶段明细加载失败，上方 4 类数量来自项目详情 DTO。
									<el-button link type="primary" size="small" @click="loadStages">重试</el-button>
								</div>
								<ul v-else class="mold-stage-list">
									<li v-for="s in stages" :key="s.id">
										<span class="mold-stage-name">{{ s.sortOrder }}. {{ s.name }}</span>
										<span class="mold-stage-ops">
											<el-tag size="small" effect="plain" :type="stageStatusTone(s.status)">{{ stageStatusText(s.status) }}</el-tag>
											<el-button
												v-if="canVoidStage(s)"
												link
												type="danger"
												size="small"
												:loading="voidingStageId === s.id"
												:data-testid="`stage-void-${s.id}`"
												title="作废建错或多建的未开工阶段（必须填写原因，历史记录保留）"
												@click="onVoidStage(s)"
											>
												作废
											</el-button>
										</span>
									</li>
								</ul>
							</div>

							<!-- 完工状态（P1-12） -->
							<div class="mold-detail-card">
								<div class="mold-detail-card-title">整单完工</div>
								<div class="mold-completion" :class="{ 'is-done': project.status === 'COMPLETED' }">
									<div class="mold-completion-title">{{ completionTitle }}</div>
									<div class="mold-completion-desc">{{ completionDesc }}</div>
								</div>
								<dl class="mold-kv mold-kv-tight">
									<div><dt>完工时间</dt><dd>{{ project.completedAt ? formatDateTime(project.completedAt) : '—' }}</dd></div>
									<div><dt>重新打开</dt><dd>{{ project.reopenedAt ? formatDateTime(project.reopenedAt) : '—' }}</dd></div>
								</dl>
							</div>

							<!-- 未解决异常（契约 6.7）：新建 / 标记处理 / 取消 -->
							<div class="mold-detail-card">
								<div class="mold-detail-card-title">
									未解决异常
									<span class="mold-detail-card-note">{{ openIssues.length }} 个</span>
									<el-button
										v-if="canCreateIssue"
										class="mold-issue-add"
										link
										type="primary"
										size="small"
										data-testid="issue-create"
										@click="openIssueCreate"
									>
										登记异常
									</el-button>
								</div>
								<div class="mold-issue-count" :class="{ 'is-bad': (project.openIssueCount || 0) > 0 }">
									{{ project.openIssueCount || 0 }}
									<span>个未解决</span>
								</div>
								<div v-if="issuesState === 'loading'" class="mold-detail-pending">异常明细加载中…</div>
								<div v-else-if="issuesState === 'error'" class="mold-detail-pending" data-testid="issues-error">
									异常明细加载失败。
									<el-button link type="primary" size="small" @click="loadIssues">重试</el-button>
								</div>
								<ul v-else-if="openIssues.length" class="mold-issue-list">
									<li v-for="it in openIssues" :key="it.id" :data-testid="`issue-row-${it.id}`">
										<div class="mold-issue-head">
											<span class="mold-issue-title">{{ it.title }}</span>
											<el-tag size="small" effect="plain" :type="issueSeverityTone(it.severity)">{{ issueSeverityText(it.severity) }}</el-tag>
											<el-tag size="small" effect="plain" type="info">{{ issueStatusText(it.status) }}</el-tag>
										</div>
										<div class="mold-issue-meta">
											处理人：{{ it.assigneeDisplayName || '—' }} · 登记人：{{ it.reporterDisplayName || '—' }} · {{ formatDateTime(it.occurredAt) }}
										</div>
										<div v-if="it.description" class="mold-issue-desc">{{ it.description }}</div>
										<div v-if="canHandleIssue(it) || canCancelIssue(it)" class="mold-issue-actions">
											<el-button
												v-if="canHandleIssue(it)"
												link
												type="primary"
												size="small"
												:data-testid="`issue-resolve-${it.id}`"
												@click="openIssueResolve(it)"
											>
												标记处理
											</el-button>
											<el-button
												v-if="canCancelIssue(it)"
												link
												type="danger"
												size="small"
												:data-testid="`issue-cancel-${it.id}`"
												@click="openIssueCancel(it)"
											>
												取消
											</el-button>
										</div>
									</li>
								</ul>
								<div v-else class="mold-detail-pending" data-testid="issues-empty">没有未解决异常。</div>
							</div>

							<!-- 成员 -->
							<div class="mold-detail-card">
								<div class="mold-detail-card-title">项目成员</div>
								<div class="mold-member-row">
									<el-avatar :size="32">{{ (project.owner?.displayName || '?').slice(0, 1) }}</el-avatar>
									<div>
										<div class="mold-member-name">{{ project.owner?.displayName || '—' }}</div>
										<div class="mold-member-role">项目负责人</div>
									</div>
								</div>
								<div v-if="membersState === 'loading'" class="mold-detail-pending">成员加载中…</div>
								<div v-else-if="membersState === 'error'" class="mold-detail-pending" data-testid="members-error">
									成员列表加载失败。
									<el-button link type="primary" size="small" @click="loadMembers">重试</el-button>
								</div>
								<ul v-else-if="members.length" class="mold-member-list">
									<li v-for="m in members" :key="m.userId" class="mold-member-row">
										<el-avatar :size="32">{{ (m.displayName || '?').slice(0, 1) }}</el-avatar>
										<div>
											<div class="mold-member-name">{{ m.displayName || m.userId }}</div>
											<div class="mold-member-role">{{ m.role || '成员' }}</div>
										</div>
									</li>
								</ul>
								<div v-else class="mold-detail-pending" data-testid="members-empty">暂无其他成员，仅项目负责人。</div>
							</div>
						</div>

						<!-- 备注 -->
						<div class="mold-detail-card mold-note-card">
							<div class="mold-detail-card-title">备注</div>
							<dl class="mold-kv">
								<div><dt>项目备注</dt><dd class="mold-note-text">{{ project.note || '—' }}</dd></div>
								<div><dt>完工说明</dt><dd class="mold-note-text">{{ project.completionNote || '—' }}</dd></div>
							</dl>
							<p class="mold-detail-source">
								数据源：<code>GET /admin/mold/projects/{{ project.id }}</code> · 版本 v{{ project.version }} ·
								最近更新 {{ formatDateTime(project.lastUpdatedAt) }}
							</p>
						</div>
					</template>
				</div>
			</div>
		</div>

		<!-- 异常：新建 / 标记处理 / 取消（契约 6.7，三者幂等必填） -->
		<el-dialog
			v-model="issueCreateVisible"
			title="登记异常"
			:width="issueDialogWidth"
			:close-on-click-modal="false"
			append-to-body
			class="mold-dialog"
		>
			<el-alert v-if="issueError" type="error" :closable="false" show-icon :title="issueError" class="mold-dialog-alert" />
			<el-form ref="issueCreateFormRef" :model="issueCreateForm" :rules="issueCreateRules" label-position="top" @submit.prevent="submitIssueCreate">
				<el-form-item label="异常标题" prop="title" :error="issueFieldErrors.title">
					<el-input v-model="issueCreateForm.title" maxlength="200" show-word-limit data-testid="issue-title" />
				</el-form-item>
				<el-form-item label="严重度" prop="severity" :error="issueFieldErrors.severity">
					<el-select v-model="issueCreateForm.severity" style="width: 100%" data-testid="issue-severity">
						<el-option label="低" value="LOW" />
						<el-option label="中" value="MEDIUM" />
						<el-option label="高" value="HIGH" />
					</el-select>
				</el-form-item>
				<el-form-item label="问题描述（必填）" prop="description" :error="issueFieldErrors.description">
					<el-input
						v-model="issueCreateForm.description"
						type="textarea"
						:rows="3"
						maxlength="5000"
						show-word-limit
						placeholder="例如：试模时发现浇口拉丝，需重新抛光"
						data-testid="issue-description"
					/>
				</el-form-item>
				<p class="mold-form-hint">异常登记后状态为「待处理」；只有报告人、处理人或项目负责人可标记处理或取消。</p>
				<div class="mold-dialog-actions">
					<el-button @click="issueCreateVisible = false">取消</el-button>
					<el-button type="primary" native-type="submit" :loading="issueSubmitting" data-testid="issue-create-submit">
						登记
					</el-button>
				</div>
			</el-form>
		</el-dialog>

		<el-dialog
			v-model="issueResolveVisible"
			:title="`标记处理：${issueTarget?.title || ''}`"
			:width="issueDialogWidth"
			:close-on-click-modal="false"
			append-to-body
			class="mold-dialog"
		>
			<el-alert v-if="issueError" type="error" :closable="false" show-icon :title="issueError" class="mold-dialog-alert" />
			<el-form label-position="top" @submit.prevent="submitIssueResolve">
				<el-form-item label="处理说明（必填）" :error="issueFieldErrors.resolution">
					<el-input
						v-model="issueResolveForm.resolution"
						type="textarea"
						:rows="3"
						maxlength="2000"
						show-word-limit
						placeholder="例如：已重新抛光并复测合格"
						data-testid="issue-resolution"
					/>
				</el-form-item>
				<div class="mold-dialog-actions">
					<el-button @click="issueResolveVisible = false">取消</el-button>
					<el-button type="primary" native-type="submit" :loading="issueSubmitting" data-testid="issue-resolve-submit">
						标记已解决
					</el-button>
				</div>
			</el-form>
		</el-dialog>

		<el-dialog
			v-model="issueCancelVisible"
			:title="`取消异常：${issueTarget?.title || ''}`"
			:width="issueDialogWidth"
			:close-on-click-modal="false"
			append-to-body
			class="mold-dialog"
		>
			<el-alert v-if="issueError" type="error" :closable="false" show-icon :title="issueError" class="mold-dialog-alert" />
			<el-form label-position="top" @submit.prevent="submitIssueCancel">
				<el-form-item label="取消原因（必填）" :error="issueFieldErrors.reason">
					<el-input
						v-model="issueCancelForm.reason"
						type="textarea"
						:rows="3"
						maxlength="2000"
						show-word-limit
						placeholder="例如：重复登记"
						data-testid="issue-reason"
					/>
				</el-form-item>
				<div class="mold-dialog-actions">
					<el-button @click="issueCancelVisible = false">返回</el-button>
					<el-button type="danger" native-type="submit" :loading="issueSubmitting" data-testid="issue-cancel-submit">
						确认取消
					</el-button>
				</div>
			</el-form>
		</el-dialog>
	</div>
</template>

<script setup lang="ts" name="moldProjectDetail">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowLeft } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import StatePanel from '/@/components/mold/StatePanel.vue';
import ProjectActions from '/@/components/mold/ProjectActions.vue';
import { voidProjectStage, fetchProjectDetail, fetchProjectIssues, fetchProjectMembers, fetchProjectStages, createIssue, resolveIssue, cancelIssue, MoldApiError, type MoldIssueRow, type MoldIssueSeverity, type MoldProjectMember, type MoldProjectRow } from '/@/api/mold/projects';
import { useMoldPermissions } from '/@/api/mold/permissions';
import { describeMoldError } from '/@/api/mold/errors';
import { formatDate, formatDateTime, todayStamp } from '/@/utils/moldFormat';
import { beginIdempotentAction, finishIdempotentAction, stableHash } from '/@/utils/idempotency';

const route = useRoute();
const router = useRouter();
const { can, canWrite, meId, ensureLoaded } = useMoldPermissions();

/* 作废阶段：仅未开工阶段；项目负责人或老板；项目未关闭 */
const voidingStageId = ref('');
function canVoidStage(s: any): boolean {
	const p: any = project.value;
	if (!p || !(p.status === 'ACTIVE' || p.status === 'ON_HOLD')) return false;
	return s?.status === 'NOT_STARTED' && can('mold:stage:create') && canWrite(p, 'mold:project:update');
}
async function onVoidStage(s: any) {
	let reason = '';
	try {
		const res: any = await ElMessageBox.prompt(
			`确定作废阶段「${s.name}」吗？作废后该阶段不再显示、不计入完工检查，操作记录会保留。`,
			'作废阶段',
			{
				confirmButtonText: '确认作废',
				cancelButtonText: '返回',
				inputType: 'textarea',
				inputPlaceholder: '作废原因（必填），例如：阶段建重复了',
				inputValidator: (v: string) => {
					if (!v?.trim()) return '请填写作废原因';
					if (v.trim().length > 500) return '原因不能超过 500 字';
					return true;
				},
				type: 'warning',
				closeOnClickModal: false,
			}
		);
		reason = (res?.value || '').trim();
	} catch {
		return;
	}
	voidingStageId.value = s.id;
	const scope = `stage.void:${s.id}:${s.version}:${stableHash(reason)}`;
	try {
		await voidProjectStage(s.id, reason, s.version, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success('阶段已作废');
	} catch (err: any) {
		ElMessage.error(describeMoldError(err, '作废阶段失败').message || '作废阶段失败');
	} finally {
		voidingStageId.value = '';
		await Promise.all([loadDetail(), loadStages()]);
	}
}

const project = ref<MoldProjectRow | null>(null);
const stages = ref<any[]>([]);
const stagesAvailable = ref(false);
const issues = ref<MoldIssueRow[]>([]);
const issuesState = ref<'loading' | 'ready' | 'error'>('loading');
const members = ref<MoldProjectMember[]>([]);
const membersState = ref<'loading' | 'ready' | 'error'>('loading');
const state = ref<'loading' | 'ready' | 'error' | 'forbidden'>('loading');
const errorInfo = reactive({ title: '加载失败', message: '', requestId: '' });

const openIssues = computed(() => issues.value.filter((it) => it.status === 'OPEN'));

const stageCounts = computed(() => {
	const c = project.value?.stageCounts;
	return {
		total: c?.total || 0,
		notStarted: c?.notStarted || 0,
		inProgress: c?.inProgress || 0,
		blocked: c?.blocked || 0,
		completed: c?.completed || 0,
	};
});
const progressPct = computed(() => {
	const t = stageCounts.value.total;
	return t > 0 ? Math.min(100, Math.round((stageCounts.value.completed / t) * 100)) : 0;
});
const overdue = computed(() => {
	const p = project.value;
	if (!p?.dueDate || p.status === 'COMPLETED' || p.status === 'CANCELLED') return false;
	return p.dueDate < todayStamp();
});

const businessTypeText = (t: string) => ({ MOLD_SET: '整套模具制作', PART_PROCESS: '零件·工序加工' } as Record<string, string>)[t] || t || '—';
const statusText = (s: string) =>
	({ ACTIVE: '进行中', ON_HOLD: '已暂停', COMPLETED: '已完工', CANCELLED: '已取消' } as Record<string, string>)[s] || s || '—';
const statusTone = (s: string) =>
	({ ACTIVE: 'primary', ON_HOLD: 'warning', COMPLETED: 'success', CANCELLED: 'info' } as Record<string, any>)[s] || 'info';
const stageStatusText = (s: string) =>
	({ NOT_STARTED: '未开始', IN_PROGRESS: '进行中', BLOCKED: '受阻', COMPLETED: '已完成' } as Record<string, string>)[s] || s || '—';
const stageStatusTone = (s: string) =>
	({ NOT_STARTED: 'info', IN_PROGRESS: 'primary', BLOCKED: 'danger', COMPLETED: 'success' } as Record<string, any>)[s] || 'info';
const issueSeverityText = (s: string) => ({ LOW: '低', MEDIUM: '中', HIGH: '高' } as Record<string, string>)[s] || s || '—';
const issueSeverityTone = (s: string) =>
	({ LOW: 'info', MEDIUM: 'warning', HIGH: 'danger' } as Record<string, any>)[s] || 'info';
const issueStatusText = (s: string) =>
	({ OPEN: '待处理', RESOLVED: '已解决', CANCELLED: '已取消' } as Record<string, string>)[s] || s || '—';

const completionTitle = computed(() => {
	const p = project.value;
	if (!p) return '';
	if (p.status === 'COMPLETED') return '整单已完工';
	if (p.status === 'CANCELLED') return '项目已取消';
	if (stageCounts.value.total > 0 && stageCounts.value.completed === stageCounts.value.total) return '阶段已完成，待负责人确认';
	return '未完工';
});
const completionDesc = computed(() => {
	const p = project.value;
	if (!p) return '';
	if (p.status === 'COMPLETED') {
		return `负责人已确认完工${p.completedAt ? `（${formatDateTime(p.completedAt)}）` : ''}${p.completionNote ? `：${p.completionNote}` : ''}`;
	}
	if (p.status === 'CANCELLED') return '项目已取消并关闭，不能再完工、编辑或推进阶段。取消原因可在审计日志中查看。';
	const hold = p.status === 'ON_HOLD' ? '项目当前已暂停。' : '';
	if (stageCounts.value.total === 0) return `${hold}还没有有效阶段，至少需要一个阶段才能确认完工。`;
	return `${hold}已完成 ${stageCounts.value.completed}/${stageCounts.value.total} 个阶段，未解决异常 ${p.openIssueCount || 0} 个；两项都满足后由负责人或老板确认。`;
});

async function loadStages() {
	const id = String(route.params.id || '');
	if (!id) return;
	try {
		stages.value = await fetchProjectStages(id);
		stagesAvailable.value = true;
	} catch {
		stages.value = [];
		stagesAvailable.value = false;
	}
}

async function loadIssues() {
	const id = String(route.params.id || '');
	if (!id) return;
	issuesState.value = 'loading';
	try {
		issues.value = await fetchProjectIssues(id);
		issuesState.value = 'ready';
	} catch {
		issues.value = [];
		issuesState.value = 'error';
	}
}

/* ------------------------------ 异常写操作（契约 6.7） ------------------------------ */
const issueDialogWidth = computed(() => (window.innerWidth <= 900 ? '94%' : '480px'));
const issueError = ref('');
const issueSubmitting = ref(false);
const issueFieldErrors = reactive<Record<string, string>>({});
const issueTarget = ref<MoldIssueRow | null>(null);
const issueCreateVisible = ref(false);
const issueResolveVisible = ref(false);
const issueCancelVisible = ref(false);
const issueCreateFormRef = ref<any>();
const issueCreateForm = reactive({ title: '', description: '', severity: 'MEDIUM' as MoldIssueSeverity });
const issueCreateRules = {
	title: [{ required: true, message: '请输入异常标题', trigger: 'blur' }],
	description: [{ required: true, message: '请填写问题描述', trigger: 'blur' }],
	severity: [{ required: true, message: '请选择严重度', trigger: 'change' }],
};
const issueResolveForm = reactive({ resolution: '' });
const issueCancelForm = reactive({ reason: '' });

/** 契约 6.7：create/cancel 六角色可用；resolve 走 mold:issue:handle。项目已关闭时后端 409，按钮不再显示 */
const projectEditable = computed(() => {
	const s = project.value?.status;
	return s === 'ACTIVE' || s === 'ON_HOLD';
});
const canCreateIssue = computed(() => can('mold:issue:create') && projectEditable.value);
/** 对象级：契约「update/resolve/cancel 只允许报告人、处理人、项目负责人」 */
function isIssueHandler(it: MoldIssueRow): boolean {
	const me = meId.value;
	if (!me) return false;
	// 服务端 requireHandler 只认报告人 / 处理人 / 项目负责人，OWNER 角色不额外放行
	return it.reporterId === me || it.assigneeUserId === me || project.value?.ownerUserId === me;
}
const canHandleIssue = (it: MoldIssueRow) => can('mold:issue:handle') && projectEditable.value && isIssueHandler(it);
const canCancelIssue = (it: MoldIssueRow) => can('mold:issue:cancel') && projectEditable.value && isIssueHandler(it);

function clearIssueFeedback() {
	issueError.value = '';
	Object.keys(issueFieldErrors).forEach((k) => delete issueFieldErrors[k]);
}

function openIssueCreate() {
	if (!canCreateIssue.value) return;
	clearIssueFeedback();
	issueCreateForm.title = '';
	issueCreateForm.description = '';
	issueCreateForm.severity = 'MEDIUM';
	issueCreateVisible.value = true;
}

function openIssueResolve(it: MoldIssueRow) {
	if (!canHandleIssue(it)) return;
	clearIssueFeedback();
	issueTarget.value = it;
	issueResolveForm.resolution = '';
	issueResolveVisible.value = true;
}

function openIssueCancel(it: MoldIssueRow) {
	if (!canCancelIssue(it)) return;
	clearIssueFeedback();
	issueTarget.value = it;
	issueCancelForm.reason = '';
	issueCancelVisible.value = true;
}

function handleIssueError(err: any, fallback: string) {
	if (err instanceof MoldApiError) Object.assign(issueFieldErrors, err.fieldErrors || {});
	issueError.value = describeMoldError(err, fallback).message;
}

async function refreshAfterIssueChange() {
	await loadIssues();
	if (project.value) {
		try {
			project.value = await fetchProjectDetail(project.value.id);
		} catch {
			/* 详情刷新失败不影响异常列表 */
		}
	}
}

async function submitIssueCreate() {
	if (issueSubmitting.value) return;
	clearIssueFeedback();
	issueSubmitting.value = true;
	try {
		await issueCreateFormRef.value?.validate();
	} catch {
		issueSubmitting.value = false;
		return;
	}
	const body = {
		projectId: project.value!.id,
		title: issueCreateForm.title.trim(),
		description: issueCreateForm.description.trim(),
		severity: issueCreateForm.severity,
	};
	// 同一份内容复用同一个键：双击/重试只建一条
	const scope = `issue.create:${body.projectId}:${stableHash(JSON.stringify(body))}`;
	try {
		await createIssue(body, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success('异常已登记');
		issueCreateVisible.value = false;
		await refreshAfterIssueChange();
	} catch (err: any) {
		handleIssueError(err, '登记异常失败');
	} finally {
		issueSubmitting.value = false;
	}
}

async function submitIssueResolve() {
	if (issueSubmitting.value) return;
	if (!issueTarget.value) return;
	if (!issueResolveForm.resolution.trim()) {
		issueFieldErrors.resolution = '请填写处理说明';
		return;
	}
	clearIssueFeedback();
	const id = issueTarget.value.id;
	const resolution = issueResolveForm.resolution.trim();
	const scope = `issue.resolve:${id}:${stableHash(resolution)}`;
	issueSubmitting.value = true;
	try {
		await resolveIssue(id, resolution, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success('异常已标记为已解决');
		issueResolveVisible.value = false;
		await refreshAfterIssueChange();
	} catch (err: any) {
		handleIssueError(err, '标记处理失败');
	} finally {
		issueSubmitting.value = false;
	}
}

async function submitIssueCancel() {
	if (issueSubmitting.value) return;
	if (!issueTarget.value) return;
	if (!issueCancelForm.reason.trim()) {
		issueFieldErrors.reason = '请填写取消原因';
		return;
	}
	clearIssueFeedback();
	const id = issueTarget.value.id;
	const reason = issueCancelForm.reason.trim();
	const scope = `issue.cancel:${id}:${stableHash(reason)}`;
	issueSubmitting.value = true;
	try {
		await cancelIssue(id, reason, beginIdempotentAction(scope));
		finishIdempotentAction(scope);
		ElMessage.success('异常已取消');
		issueCancelVisible.value = false;
		await refreshAfterIssueChange();
	} catch (err: any) {
		handleIssueError(err, '取消异常失败');
	} finally {
		issueSubmitting.value = false;
	}
}

async function loadMembers() {
	const id = String(route.params.id || '');
	if (!id) return;
	membersState.value = 'loading';
	try {
		members.value = await fetchProjectMembers(id);
		membersState.value = 'ready';
	} catch {
		members.value = [];
		membersState.value = 'error';
	}
}

async function loadDetail() {
	state.value = 'loading';
	errorInfo.title = '加载失败';
	errorInfo.message = '';
	errorInfo.requestId = '';
	const id = String(route.params.id || '');
	if (!id) {
		state.value = 'error';
		errorInfo.title = '项目不存在';
		errorInfo.message = '地址缺少项目 ID。';
		return;
	}
	try {
		project.value = await fetchProjectDetail(id);
		state.value = 'ready';
		// 子资源并行加载：任一失败只影响该卡片，不拖垮详情
		void loadStages();
		void loadIssues();
		void loadMembers();
	} catch (err: any) {
		project.value = null;
		if (err instanceof MoldApiError && err.status === 404) {
			state.value = 'error';
			errorInfo.title = '项目不存在';
			errorInfo.message = '项目可能已删除，或链接中的 ID 不正确。';
			errorInfo.requestId = err.requestId || '';
		} else if (err instanceof MoldApiError && err.status === 403) {
			state.value = 'forbidden';
		} else if (err instanceof MoldApiError && err.status === 401) {
			state.value = 'error';
			errorInfo.title = '登录已过期';
			errorInfo.message = '请重新登录后再打开详情页。';
			errorInfo.requestId = err.requestId || '';
		} else {
			state.value = 'error';
			errorInfo.message = err?.message || '服务暂时不可用，请稍后重试。';
			errorInfo.requestId = err?.requestId || '';
		}
	}
}

function onSaved(row: MoldProjectRow) {
	project.value = row;
	loadDetail();
}

function goBack() {
	router.push('/mold/projects');
}

onMounted(() => {
	ensureLoaded();
	loadDetail();
});
</script>

<style scoped lang="scss">
.mold-detail {
	max-width: 1180px;
}
.mold-detail-back {
	margin-bottom: 4px;
}
.mold-detail-head {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 16px;
	flex-wrap: wrap;
	margin-bottom: 16px;
}
.mold-detail-title {
	min-width: 0;
	h1 {
		margin: 2px 0 8px;
		font-size: 24px;
		line-height: 32px;
		font-weight: 650;
	}
}
.mold-detail-code {
	font-size: 12px;
	line-height: 18px;
	color: var(--el-text-color-secondary);
	font-variant-numeric: tabular-nums;
}
.mold-detail-tags {
	display: flex;
	align-items: center;
	gap: 8px;
	flex-wrap: wrap;
}
.mold-detail-version {
	font-size: 12px;
	color: var(--el-text-color-secondary);
}
.mold-detail-actions {
	display: flex;
	gap: 8px;
	flex-wrap: wrap;
}
.mold-detail-grid {
	display: grid;
	grid-template-columns: repeat(2, minmax(0, 1fr));
	gap: 12px;
}
.mold-detail-card {
	background: var(--el-bg-color);
	border: 1px solid var(--el-border-color-lighter);
	border-radius: var(--mold-radius-card);
	padding: 14px 16px;
	min-width: 0;
}
.mold-detail-card-title {
	display: flex;
	align-items: baseline;
	justify-content: space-between;
	gap: 8px;
	font-size: 14px;
	line-height: 22px;
	font-weight: 600;
	margin-bottom: 10px;
}
.mold-detail-card-note {
	font-size: 12px;
	font-weight: 400;
	color: var(--el-text-color-secondary);
}
.mold-kv {
	margin: 0;
	display: grid;
	grid-template-columns: repeat(2, minmax(0, 1fr));
	gap: 8px 16px;
	div {
		min-width: 0;
	}
	dt {
		font-size: 12px;
		line-height: 18px;
		color: var(--el-text-color-secondary);
	}
	dd {
		margin: 2px 0 0;
		font-size: 14px;
		line-height: 22px;
		word-break: break-word;
	}
}
.mold-kv-tight {
	margin-top: 10px;
}
.mold-stage-counts {
	display: grid;
	grid-template-columns: repeat(4, minmax(0, 1fr));
	gap: 8px;
	margin-top: 12px;
}
.mold-stage-count {
	background: var(--el-fill-color-light);
	border-radius: 6px;
	padding: 8px;
	text-align: center;
	.n {
		display: block;
		font-size: 20px;
		line-height: 26px;
		font-weight: 650;
		font-variant-numeric: tabular-nums;
	}
	.l {
		display: block;
		font-size: 12px;
		line-height: 18px;
		color: var(--el-text-color-secondary);
	}
	&.is-progress .n {
		color: var(--el-color-primary);
	}
	&.is-blocked .n {
		color: var(--el-color-danger);
	}
	&.is-done .n {
		color: var(--el-color-success);
	}
}
.mold-completion-title {
	font-size: 18px;
	line-height: 26px;
	font-weight: 650;
}
.mold-completion.is-done .mold-completion-title {
	color: var(--el-color-success);
}
.mold-completion-desc {
	font-size: 13px;
	line-height: 20px;
	color: var(--el-text-color-regular);
}
.mold-issue-count {
	font-size: 28px;
	line-height: 36px;
	font-weight: 650;
	font-variant-numeric: tabular-nums;
	span {
		font-size: 13px;
		font-weight: 400;
		color: var(--el-text-color-secondary);
		margin-left: 4px;
	}
	&.is-bad {
		color: var(--el-color-danger);
	}
}
.mold-detail-pending {
	margin-top: 10px;
	font-size: 12px;
	line-height: 18px;
	color: var(--el-text-color-secondary);
	background: var(--el-fill-color-lighter);
	border-radius: 6px;
	padding: 8px 10px;
}
.mold-member-row {
	display: flex;
	align-items: center;
	gap: 10px;
}
.mold-member-list {
	list-style: none;
	margin: 8px 0 0;
	padding: 0;
	li {
		padding: 6px 0;
		border-top: 1px dashed var(--el-border-color-lighter);
	}
}
.mold-issue-list {
	list-style: none;
	margin: 8px 0 0;
	padding: 0;
	li {
		padding: 8px 0;
		border-top: 1px dashed var(--el-border-color-lighter);
	}
}
.mold-issue-head {
	display: flex;
	align-items: center;
	gap: 8px;
	flex-wrap: wrap;
}
.mold-issue-title {
	font-size: 14px;
	line-height: 20px;
	font-weight: 600;
	min-width: 0;
	overflow-wrap: anywhere;
}
.mold-issue-meta {
	margin-top: 4px;
	font-size: 12px;
	line-height: 18px;
	color: var(--el-text-color-secondary);
}
.mold-issue-desc {
	margin-top: 4px;
	font-size: 13px;
	line-height: 20px;
	color: var(--el-text-color-regular);
	overflow-wrap: anywhere;
}
.mold-issue-add {
	margin-left: auto;
}
.mold-issue-actions {
	display: flex;
	gap: 4px;
	margin-top: 4px;
}
.mold-member-name {
	font-size: 14px;
	line-height: 20px;
	font-weight: 600;
}
.mold-member-role {
	font-size: 12px;
	line-height: 18px;
	color: var(--el-text-color-secondary);
}
.mold-stage-ops {
	display: inline-flex;
	align-items: center;
	gap: 6px;
}
.mold-stage-list {
	list-style: none;
	margin: 10px 0 0;
	padding: 0;
	li {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 8px;
		padding: 6px 0;
		border-top: 1px dashed var(--el-border-color-lighter);
		font-size: 13px;
		line-height: 20px;
	}
	.mold-stage-name {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
}
.mold-note-card {
	margin-top: 12px;
}
.mold-note-text {
	white-space: pre-wrap;
}
.mold-detail-source {
	margin: 12px 0 0;
	font-size: 12px;
	line-height: 18px;
	color: var(--el-text-color-secondary);
	code {
		word-break: break-all;
	}
}
.mold-skel-block {
	background: var(--el-fill-color);
	border-radius: 6px;
}
@media (max-width: 900px) {
	.mold-detail-head {
		flex-direction: column;
	}
	.mold-detail-actions {
		width: 100%;
		:deep(.mold-actions) {
			width: 100%;
			display: flex;
		}
		:deep(.el-button) {
			flex: 1;
		}
	}
	.mold-detail-grid {
		grid-template-columns: minmax(0, 1fr);
	}
	.mold-kv {
		grid-template-columns: minmax(0, 1fr);
	}
	.mold-stage-counts {
		grid-template-columns: repeat(2, minmax(0, 1fr));
	}
}
</style>
