<template>
	<div class="mold-state" :class="`is-${kind}`" role="status" :aria-live="kind === 'error' ? 'assertive' : 'polite'">
		<el-icon class="mold-state-icon">
			<Lock v-if="kind === 'forbidden'" />
			<WarningFilled v-else-if="kind === 'error'" />
			<FolderOpened v-else />
		</el-icon>
		<div class="mold-state-title">{{ title }}</div>
		<div v-if="description" class="mold-state-desc">{{ description }}</div>
		<div v-if="requestId" class="mold-state-request">
			请求 ID：{{ requestId }}
			<el-button link type="primary" size="small" @click="copyRequestId">复制</el-button>
		</div>
		<div v-if="actionText || secondaryText" class="mold-state-actions">
			<el-button v-if="actionText" type="primary" :loading="loading" @click="$emit('action')">{{ actionText }}</el-button>
			<el-button v-if="secondaryText" @click="$emit('secondary')">{{ secondaryText }}</el-button>
		</div>
	</div>
</template>

<script setup lang="ts" name="MoldStatePanel">
import { ElMessage } from 'element-plus';

const props = defineProps<{
	kind: 'empty' | 'error' | 'forbidden';
	title: string;
	description?: string;
	requestId?: string;
	actionText?: string;
	secondaryText?: string;
	loading?: boolean;
}>();
defineEmits<{ (e: 'action'): void; (e: 'secondary'): void }>();

const copyRequestId = async () => {
	try {
		await navigator.clipboard.writeText(props.requestId || '');
		ElMessage.success('请求 ID 已复制');
	} catch {
		ElMessage.warning('浏览器不允许访问剪贴板，请手动选中复制');
	}
};
</script>
