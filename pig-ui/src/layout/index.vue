<template>
	<!-- G-13：首个 Tab 为「跳到主内容」。必须放在布局最前，早于侧栏/顶栏 -->
	<a class="mold-skip-link" href="#mold-main-content" @click.prevent="focusMain">跳到主内容</a>
	<CommandPalette ref="paletteRef" />
	<component :is="layouts[themeConfig.layout]" />
</template>

<script setup lang="ts" name="layout">
import { useThemeConfig } from '/@/stores/themeConfig';
import { Local } from '/@/utils/storage';
import mittBus from '/@/utils/mitt';
import { useEventListener } from '@vueuse/core';
import CommandPalette from '/@/components/mold/CommandPalette.vue';
import { moldEvents } from '/@/api/mold/events';
import { useMoldPermissions } from '/@/api/mold/permissions';

const layouts: Record<string, ReturnType<typeof defineAsyncComponent>> = {
	defaults: defineAsyncComponent(() => import('/@/layout/main/defaults.vue')),
	classic: defineAsyncComponent(() => import('/@/layout/main/classic.vue')),
	transverse: defineAsyncComponent(() => import('/@/layout/main/transverse.vue')),
	columns: defineAsyncComponent(() => import('/@/layout/main/columns.vue')),
};

const { themeConfig } = storeToRefs(useThemeConfig());

/* G-13 命令面板：⌘/Ctrl + K 随时唤起（跳页面 + 搜项目名，全键盘可操作） */
const paletteRef = ref<any>();
const onGlobalKeydown = (e: KeyboardEvent) => {
	if (!(e.metaKey || e.ctrlKey) || e.altKey) return;
	if (e.key !== 'k' && e.key !== 'K') return;
	e.preventDefault();
	paletteRef.value?.open();
};
useEventListener(window, 'keydown', onGlobalKeydown);

// 键盘「跳到主内容」：把焦点移到内容区，Tab 从这里继续（G-13）
const focusMain = () => {
	document.getElementById('mold-main-content')?.focus();
};

const onLayoutResize = () => {
	if (!Local.get('oldLayout')) Local.set('oldLayout', themeConfig.value.layout);
	const clientWidth = document.body.clientWidth;
	if (clientWidth < 1000) {
		themeConfig.value.isCollapse = false;
		mittBus.emit('layoutMobileResize', { layout: 'defaults', clientWidth });
	} else {
		mittBus.emit('layoutMobileResize', {
			layout: Local.get('oldLayout') || themeConfig.value.layout,
			clientWidth,
		});
	}
};

onBeforeMount(() => {
	onLayoutResize();
});

/* 业务事件流：登录后只建一条连接；财务权限用 FINANCE，否则 BASIC（契约 6.10.4） */
const { ensureLoaded: ensureMoldPermissions, can: canMold } = useMoldPermissions();
onMounted(async () => {
	await ensureMoldPermissions();
	moldEvents.start(canMold('mold:cash:viewAll') ? 'FINANCE' : 'BASIC');
});
onUnmounted(() => moldEvents.stop());

useEventListener(window, 'resize', onLayoutResize);
</script>
