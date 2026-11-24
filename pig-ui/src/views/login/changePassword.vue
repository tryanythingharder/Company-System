<template>
	<div class="mold-pwd-page">
		<div class="mold-pwd-card">
			<div class="mold-pwd-brand">
				<div class="mold-pwd-logo">模</div>
				<div>
					<h1>{{ selfService ? '修改密码' : '修改初始密码' }}</h1>
					<p class="mold-pwd-sub" v-if="selfService">账号 {{ accountName || '—' }}：修改后当前登录保持有效，下次登录使用新密码。</p>
					<p class="mold-pwd-sub" v-else>账号 {{ accountName || '—' }} 需要先设置新密码，之后才能进入系统。</p>
				</div>
			</div>

			<el-alert
				v-if="!selfService"
				class="mold-pwd-alert"
				type="warning"
				:closable="false"
				show-icon
				title="首次登录需要先修改密码"
				description="这是管理员设置的初始密码或重置后的临时密码。改完之前，系统里的其他功能都无法使用。"
			/>

			<el-alert
				v-if="pageError"
				class="mold-pwd-alert"
				:type="pageErrorKind === 'network' ? 'warning' : 'error'"
				:closable="false"
				show-icon
				:title="pageErrorTitle"
				:description="pageError"
				data-testid="pwd-page-error"
			/>

			<el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
				<el-form-item label="当前密码" prop="oldPassword" :error="fieldErrors.oldPassword">
					<el-input
						v-model="form.oldPassword"
						type="password"
						show-password
						autocomplete="current-password"
						placeholder="输入当前（初始）密码"
						data-testid="pwd-old"
					/>
				</el-form-item>
				<el-form-item label="新密码" prop="newPassword" :error="fieldErrors.newPassword">
					<el-input
						v-model="form.newPassword"
						type="password"
						show-password
						autocomplete="new-password"
						placeholder="12–128 个字符，不能与登录名/显示名相同"
						data-testid="pwd-new"
					/>
				</el-form-item>
				<el-form-item label="再次输入新密码" prop="confirmPassword" :error="fieldErrors.confirmPassword">
					<el-input
						v-model="form.confirmPassword"
						type="password"
						show-password
						autocomplete="new-password"
						placeholder="再输入一次新密码"
						data-testid="pwd-confirm"
					/>
				</el-form-item>

				<ul class="mold-pwd-rules">
					<li>长度 12–128 个字符</li>
					<li>不得与登录名或显示名相同，不得是常见口令</li>
					<li>不能是单一字符重复，也不能是连续 8 位字符序列</li>
				</ul>

				<div class="mold-pwd-actions">
					<el-button v-if="selfService" link type="primary" data-testid="pwd-cancel" @click="cancel">返回</el-button>
					<el-button v-else link type="primary" data-testid="pwd-logout" @click="logout">退出登录</el-button>
					<el-button type="primary" native-type="submit" :loading="submitting" data-testid="pwd-submit">{{
						selfService ? '保存新密码' : '保存并进入系统'
					}}</el-button>
				</div>
			</el-form>
		</div>
	</div>
</template>

<script setup lang="ts" name="moldChangePassword">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { changeOwnPassword, fetchMe } from '/@/api/mold/auth';
import { logout as apiLogout } from '/@/api/login';
import { clearMustChangeFlag, setMustChangeFlag } from '/@/api/mold/authEvents';
import { describeMoldError } from '/@/api/mold/errors';
import { MoldApiError } from '/@/api/mold/projects';
import { Session } from '/@/utils/storage';

const route = useRoute();
const router = useRouter();

/**
 * 自助改密模式（?mode=self）：已登录用户从顶栏「修改密码」进入。
 * 背景：顶栏原「个人中心」走上游 /admin/user/personal/password，绕过本系统的口令规则与审计，
 * 且其资料保存接口直接 500，已下线；改密统一走 POST /auth/password。
 */
const selfService = computed(() => route.query.mode === 'self');

const formRef = ref<any>();
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' });
const submitting = ref(false);
const accountName = ref('');
const pageError = ref('');
const pageErrorTitle = ref('');
const pageErrorKind = ref<'error' | 'network'>('error');
const fieldErrors = reactive<Record<string, string>>({});

const rules = {
	oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
	newPassword: [
		{ required: true, message: '请输入新密码', trigger: 'blur' },
		{ min: 12, max: 128, message: '密码需 12 至 128 字符', trigger: 'blur' },
		{
			validator: (_r: any, value: string, cb: (e?: Error) => void) => {
				if (value && form.oldPassword && value === form.oldPassword) cb(new Error('新密码不能与当前密码相同'));
				else cb();
			},
			trigger: 'blur',
		},
	],
	confirmPassword: [
		{ required: true, message: '请再次输入新密码', trigger: 'blur' },
		{
			validator: (_r: any, value: string, cb: (e?: Error) => void) => {
				if (value && value !== form.newPassword) cb(new Error('两次输入的新密码不一致'));
				else cb();
			},
			trigger: 'blur',
		},
	],
};

function targetPath(): string {
	const redirect = String(route.query.redirect || '');
	if (redirect && !redirect.startsWith('/change-password')) return redirect;
	return '/home';
}

async function submit() {
	const valid = await formRef.value?.validate().catch(() => false);
	if (!valid) return;
	submitting.value = true;
	pageError.value = '';
	Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k]);
	try {
		await changeOwnPassword(form.oldPassword, form.newPassword);
		// 改密成功后必须重新拉 /auth/me，再进入系统（不能只信前端标记）
		const me = await fetchMe();
		if (me.mustChangePassword) {
			pageErrorTitle.value = '改密未生效';
			pageErrorKind.value = 'error';
			pageError.value = '服务端仍要求修改密码，请重试或联系管理员。';
			return;
		}
		clearMustChangeFlag();
		setMustChangeFlag(false);
		ElMessage.success('密码已修改，正在进入系统');
		await router.replace(targetPath());
	} catch (err: any) {
		if (err instanceof MoldApiError) {
			Object.assign(fieldErrors, err.fieldErrors || {});
			if (err.status === 401) {
				// 拦截器已清会话；这里只补提示
				pageErrorTitle.value = '登录已失效';
				pageErrorKind.value = 'error';
				pageError.value = '请重新登录后再改密。';
				return;
			}
			if (err.status === 403 && err.code === 'PASSWORD_CHANGE_REQUIRED') {
				pageErrorTitle.value = '需要先修改初始密码';
				pageErrorKind.value = 'error';
				pageError.value = '服务端仍要求改密，请重新提交。';
				return;
			}
		}
		const view = describeMoldError(err, '改密失败，请重试');
		pageErrorTitle.value = view.title;
		pageErrorKind.value = view.kind === 'network' ? 'network' : 'error';
		pageError.value = view.message;
	} finally {
		submitting.value = false;
	}
}

function cancel() {
	if (window.history.length > 1) router.back();
	else router.replace('/home');
}

async function logout() {
	try {
		await apiLogout();
	} catch {
		// 后端注销失败不阻塞本地退出
	} finally {
		Session.clear();
		clearMustChangeFlag();
		router.replace('/login');
	}
}

onMounted(async () => {
	// 守卫已核对过一次；这里只取显示名，并确认确实需要改密
	try {
		const me = await fetchMe();
		accountName.value = me.user?.displayName || me.user?.loginName || '';
		if (!me.mustChangePassword && !selfService.value) {
			router.replace(targetPath());
		}
	} catch (err) {
		const view = describeMoldError(err, '无法核对登录会话');
		pageErrorTitle.value = view.title;
		pageErrorKind.value = view.kind === 'network' ? 'network' : 'error';
		pageError.value = view.message;
	}
});
</script>

<style scoped lang="scss">
.mold-pwd-page {
	min-height: 100vh;
	display: flex;
	align-items: center;
	justify-content: center;
	padding: 24px;
	background: var(--el-bg-color-page, #f5f7fa);
}
.mold-pwd-card {
	width: 100%;
	max-width: 520px;
	background: var(--el-bg-color);
	border: 1px solid var(--el-border-color-lighter);
	border-radius: 12px;
	padding: 28px;
	box-shadow: 0 12px 32px rgb(0 0 0 / 8%);
}
.mold-pwd-brand {
	display: flex;
	align-items: center;
	gap: 14px;
	margin-bottom: 18px;
	h1 {
		font-size: 20px;
		line-height: 28px;
		margin: 0;
	}
}
.mold-pwd-logo {
	width: 44px;
	height: 44px;
	border-radius: 10px;
	display: flex;
	align-items: center;
	justify-content: center;
	font-weight: 700;
	font-size: 20px;
	color: #fff;
	background: var(--el-color-primary);
	flex: none;
}
.mold-pwd-sub {
	margin: 4px 0 0;
	font-size: 13px;
	line-height: 20px;
	color: var(--el-text-color-secondary);
}
.mold-pwd-alert {
	margin-bottom: 14px;
}
.mold-pwd-rules {
	margin: 0 0 16px;
	padding-left: 20px;
	font-size: 12px;
	line-height: 20px;
	color: var(--el-text-color-secondary);
}
.mold-pwd-actions {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 12px;
}
@media (max-width: 900px) {
	.mold-pwd-card {
		padding: 20px;
	}
}
</style>
