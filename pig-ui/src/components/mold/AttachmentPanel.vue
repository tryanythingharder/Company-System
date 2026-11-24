<template>
	<div class="mold-attach" :aria-busy="loading ? 'true' : 'false'">
		<div class="mold-attach-head">
			<span class="mold-attach-title">{{ title }}</span>
			<span class="mold-attach-count">{{ rows.length }} 个</span>
		</div>

		<div v-if="error" class="mold-attach-error" role="alert">{{ error }}</div>

		<div v-if="loading" class="mold-attach-loading">
			<div v-for="i in 2" :key="i" class="mold-skel-block" style="height: 40px; width: 100%; margin-bottom: 6px" />
		</div>
		<ul v-else-if="rows.length" class="mold-attach-list">
			<li v-for="a in rows" :key="a.id" class="mold-attach-item">
				<div class="mold-attach-main">
					<div class="mold-attach-name" :title="a.fileName">{{ a.fileName }}</div>
					<div class="mold-attach-meta">
						<span>{{ categoryText(a.category) }}</span>
						<span>{{ formatSize(a.sizeBytes) }}</span>
						<span>{{ formatDateTime(a.uploadedAt) }}</span>
						<StatusTag kind="sensitivity" :value="a.sensitivity" effect="plain" />
					</div>
				</div>
				<div class="mold-attach-actions">
					<el-button v-if="canDownload" link type="primary" size="small" @click="onDownload(a)">下载</el-button>
					<el-button v-if="canDelete" link type="danger" size="small" @click="onDelete(a)">删除</el-button>
				</div>
			</li>
		</ul>
		<p v-else class="mold-attach-empty">还没有附件。{{ canUpload ? '可选择文件上传。' : '' }}</p>

		<div v-if="canUpload" class="mold-attach-upload">
			<el-select v-model="category" size="small" style="width: 132px" aria-label="附件类别">
				<el-option v-for="c in categories" :key="c.value" :label="c.label" :value="c.value" />
			</el-select>
			<input
				ref="fileRef"
				type="file"
				class="mold-attach-file"
				:accept="ATTACHMENT_ACCEPT"
				aria-label="选择附件文件"
				@change="onFileChange"
			/>
			<el-button type="primary" size="small" :loading="uploading" :disabled="!file" @click="onUpload">
				{{ uploading ? `上传中 ${uploadPercent}%` : '上传' }}
			</el-button>
		</div>
		<el-progress
			v-if="uploading"
			class="mold-attach-progress"
			:percentage="uploadPercent"
			:stroke-width="6"
			:show-text="false"
			aria-label="附件上传进度"
		/>
		<p class="mold-attach-hint">允许 JPG/PNG/HEIC/PDF，单个不超过 10 MiB；采购/财务类附件自动标记为财务级，下载需要财务查看权限。</p>
	</div>
</template>

<script setup lang="ts" name="MoldAttachmentPanel">
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import StatusTag from '/@/components/mold/StatusTag.vue';
import { useMoldPermissions } from '/@/api/mold/permissions';
import {
	ATTACHMENT_ACCEPT,
	deleteAttachment,
	describeAttachmentError,
	downloadAttachment,
	fetchAttachments,
	uploadAttachment,
	validateAttachmentFile,
	type MoldAttachmentCategory,
	type MoldAttachmentOwner,
	type MoldAttachmentRow,
} from '/@/api/mold/attachments';
import { debounceRefresh, useMoldEvent } from '/@/api/mold/events';
import { formatDateTime } from '/@/utils/moldFormat';
import { beginIdempotentAction, finishIdempotentAction } from '/@/utils/idempotency';

const props = withDefaults(
	defineProps<{
		ownerType: MoldAttachmentOwner;
		ownerId: string;
		title?: string;
		/** 采购/财务类归属：后端强制 FINANCIAL，前端同步展示 */
		financial?: boolean;
		/**
		 * PROJECT/STAGE/ISSUE 的对象级写权限来源（服务端口径：
		 * PROJECT 看项目负责人；STAGE 看项目负责人或阶段负责人；
		 * ISSUE 看项目负责人、登记人或处理人）。采购与流水类归属不需要传。
		 */
		subject?: {
			ownerUserId?: string | null;
			responsibleUserId?: string | null;
			reporterUserId?: string | null;
			handlerUserId?: string | null;
		} | null;
	}>(),
	{ title: '附件', financial: false, subject: null }
);

const { can, isOwnerRole, meId } = useMoldPermissions();

/**
 * 上传按钮显隐（2026-11-13 拍板口径）：OWNER 可传任意归属；
 * PURCHASE* 看 mold:purchase:update；CASH_ENTRY 看 mold:cash:create；
 * 其余按对象负责人。服务端仍逐请求鉴权，这里只是体验层。
 */
const canUpload = computed(() => {
	if (!can('mold:attachment:upload')) return false;
	if (isOwnerRole.value) return true;
	if (!meId.value) return false;
	switch (props.ownerType) {
		case 'PURCHASE':
		case 'PURCHASE_RECEIPT':
		case 'PURCHASE_INVOICE':
			return can('mold:purchase:update');
		case 'CASH_ENTRY':
			return can('mold:cash:create');
		case 'PROJECT':
			return props.subject?.ownerUserId === meId.value;
		case 'STAGE':
			return props.subject?.ownerUserId === meId.value || props.subject?.responsibleUserId === meId.value;
		case 'ISSUE':
			return (
				props.subject?.ownerUserId === meId.value ||
				props.subject?.reporterUserId === meId.value ||
				props.subject?.handlerUserId === meId.value
			);
		default:
			return false;
	}
});
const canDownload = computed(() => can('mold:attachment:download') && (!props.financial || can('mold:cash:list')));
const canDelete = computed(() => can('mold:attachment:delete'));

const categories: { value: MoldAttachmentCategory; label: string }[] = [
	{ value: 'PURCHASE_ORDER', label: '采购单' },
	{ value: 'DELIVERY_NOTE', label: '送货单' },
	{ value: 'INVOICE', label: '发票' },
	{ value: 'PAYMENT_VOUCHER', label: '付款凭证' },
	{ value: 'CONTRACT', label: '合同' },
	{ value: 'OTHER', label: '其他' },
];

const rows = ref<MoldAttachmentRow[]>([]);
const loading = ref(false);
const uploading = ref(false);
const uploadPercent = ref(0);
const error = ref('');
const category = ref<MoldAttachmentCategory>('OTHER');
const file = ref<File | null>(null);
const fileRef = ref<HTMLInputElement>();

const categoryText = (value: string) => categories.find((c) => c.value === value)?.label || value;

function formatSize(bytes: number) {
	if (!bytes) return '0 B';
	if (bytes < 1024) return `${bytes} B`;
	if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
	return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

async function load() {
	if (!props.ownerId) return;
	loading.value = true;
	error.value = '';
	try {
		rows.value = await fetchAttachments(props.ownerType, props.ownerId);
	} catch (err: any) {
		error.value = describeAttachmentError(err, '附件列表加载失败');
	} finally {
		loading.value = false;
	}
}

function onFileChange(event: Event) {
	const input = event.target as HTMLInputElement;
	const picked = input.files?.[0] || null;
	if (!picked) {
		file.value = null;
		return;
	}
	const invalid = validateAttachmentFile(picked);
	if (invalid) {
		ElMessage.warning(invalid);
		input.value = '';
		file.value = null;
		return;
	}
	file.value = picked;
}

async function onUpload() {
	if (!file.value) return;
	const picked = file.value;
	uploading.value = true;
	uploadPercent.value = 0;
	error.value = '';
	// 同一文件 + 同一归属 + 同一类别：重试复用同一键，不产生两条附件
	const scope = `attachment.upload:${props.ownerType}:${props.ownerId}:${category.value}:${picked.name}:${picked.size}:${picked.lastModified}`;
	try {
		await uploadAttachment(
			props.ownerType,
			props.ownerId,
			picked,
			category.value,
			props.financial ? 'FINANCIAL' : 'NORMAL',
			beginIdempotentAction(scope),
			(percent) => {
				uploadPercent.value = percent;
			}
		);
		finishIdempotentAction(scope);
		ElMessage.success('附件已上传');
		file.value = null;
		if (fileRef.value) fileRef.value.value = '';
		await load();
	} catch (err: any) {
		error.value = describeAttachmentError(err, '附件上传失败');
	} finally {
		uploading.value = false;
	}
}

async function onDownload(row: MoldAttachmentRow) {
	try {
		await downloadAttachment(row);
	} catch (err: any) {
		ElMessage.error(describeAttachmentError(err, '下载失败'));
	}
}

async function onDelete(row: MoldAttachmentRow) {
	const mustReason = row.sensitivity === 'FINANCIAL' || props.financial;
	let reason: string | undefined;
	if (mustReason) {
		try {
			const { value } = await ElMessageBox.prompt(
				`删除财务附件必须填写原因（1–500 字）。删除后列表里不再显示，但文件和原因会留档备查。`,
				`删除财务附件「${row.fileName}」`,
				{
					confirmButtonText: '删除',
					cancelButtonText: '取消',
					inputType: 'textarea',
					inputPlaceholder: '例如：发票重复上传，作废后重传',
					inputValidator: (val: string) => {
						const text = (val || '').trim();
						if (!text) return '请填写删除原因';
						if (text.length > 500) return '原因不能超过 500 字';
						return true;
					},
				}
			);
			reason = (value || '').trim();
		} catch {
			return;
		}
	} else {
		try {
			await ElMessageBox.confirm(`确定删除附件「${row.fileName}」？删除后列表里不再显示，文件会留档备查。`, '删除附件', {
				type: 'warning',
				confirmButtonText: '删除',
				cancelButtonText: '取消',
			});
		} catch {
			return;
		}
	}
	try {
		await deleteAttachment(row.id, reason);
		ElMessage.success('附件已删除');
		await load();
	} catch (err: any) {
		ElMessage.error(describeAttachmentError(err, '删除失败'));
	}
}

// 附件增删后只刷新本面板（事件信封不带名称，按附件事件统一刷新当前归属）
const refreshOnEvent = debounceRefresh(load, 700);
useMoldEvent(['attachment.created', 'attachment.deleted'], refreshOnEvent);

watch(() => [props.ownerType, props.ownerId], load);
onMounted(load);
</script>

<style scoped lang="scss">
.mold-attach {
	border: 1px solid var(--el-border-color-lighter);
	border-radius: var(--mold-radius-card);
	padding: 12px 14px;
	background: var(--el-bg-color);
}
.mold-attach-head {
	display: flex;
	align-items: baseline;
	gap: 8px;
	margin-bottom: 8px;
}
.mold-attach-title {
	font-weight: 600;
	font-size: 14px;
}
.mold-attach-count {
	font-size: 12px;
	color: var(--el-text-color-secondary);
}
.mold-attach-error {
	margin-bottom: 8px;
	padding: 6px 8px;
	border-radius: 4px;
	background: var(--el-color-danger-light-9);
	color: var(--el-color-danger);
	font-size: 12px;
	line-height: 18px;
}
.mold-attach-list {
	list-style: none;
	margin: 0;
	padding: 0;
}
.mold-attach-item {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 10px;
	padding: 8px 0;
	border-top: 1px solid var(--el-border-color-lighter);
	&:first-child {
		border-top: 0;
	}
}
.mold-attach-main {
	min-width: 0;
	flex: 1;
}
.mold-attach-name {
	font-size: 13px;
	line-height: 20px;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}
.mold-attach-meta {
	display: flex;
	flex-wrap: wrap;
	gap: 8px;
	font-size: 11px;
	line-height: 18px;
	color: var(--el-text-color-secondary);
}
.mold-attach-actions {
	display: flex;
	flex: none;
}
.mold-attach-empty {
	margin: 4px 0 10px;
	font-size: 12px;
	color: var(--el-text-color-secondary);
}
.mold-attach-upload {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	gap: 8px;
	margin-top: 10px;
}
.mold-attach-file {
	flex: 1;
	min-width: 160px;
	max-width: 100%;
	font-size: 12px;
}
.mold-attach-progress {
	margin-top: 6px;
	max-width: 100%;
}
.mold-attach-hint {
	margin: 8px 0 0;
	font-size: 11px;
	line-height: 17px;
	color: var(--el-text-color-secondary);
}
</style>
