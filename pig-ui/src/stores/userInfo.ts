import { Cookie, Local, Session, STORAGE_KEYS } from '/@/utils/storage';
import { getUserInfo, login, loginByMobile, loginBySocial, refreshTokenApi, rememberTokenExpiry, SocialLoginEnum } from '/@/api/login/index';
import { useMessage } from '/@/hooks/message';

/**
 * @function useUserInfo
 * @returns {UserInfosStore}
 */
export const useUserInfo = defineStore('userInfo', {
	state: (): UserInfosState => ({
		userInfos: {
			userName: '',
				photo: '',
				time: 0,
				roles: [],
				authBtnList: [],
				deptId: '',
				deptName: '',
				deptList: [],
		},
	}),

	actions: {
		/**
		 * 登录方法
		 * @function login
		 * @async
		 * @param {Object} data - 登录数据
		 * @returns {Promise<Object>}
		 */
		async login(data: any) {
			return new Promise((resolve, reject) => {
				login(data)
					.then((res: any) => {
						// /admin/auth/login 出参：{ code, msg, data: { accessToken, token: { refreshToken }, mustChangePassword, ... } }
						const payload = res?.data ?? res;
						const accessToken = payload?.accessToken || payload?.token?.accessToken || payload?.access_token;
						if (!accessToken) {
							reject({ msg: '登录响应缺少令牌', httpStatus: 0 });
							return;
						}
						Session.set('token', accessToken);
						const refreshToken = payload?.token?.refreshToken || payload?.refreshToken || payload?.refresh_token;
						if (refreshToken) Session.set('refresh_token', refreshToken);
						rememberTokenExpiry(payload?.token?.expiresIn);
						resolve(payload);
					})
					.catch((err) => {
						console.error('[login] 登录失败:', err); // eslint-disable-line no-console
						// 提示文案由登录页统一处理（区分口令错误 / 429 / 账号锁定 / 网络故障），这里只透传
						reject(err);
					});
			});
		},

		/**
		 * 手机登录方法
		 * @function loginByMobile
		 * @async
		 * @param {Object} data - 登录数据
		 * @returns {Promise<Object>}
		 */
		async loginByMobile(data: { mobile: string; code: string }) {
			return new Promise((resolve, reject) => {
				loginByMobile(data.mobile, data.code)
					.then((res) => {
						// 存储token 信息
						Session.set('token', res.access_token);
						Session.set('refresh_token', res.refresh_token);
						resolve(res);
					})
					.catch((err) => {
						console.error('[login] 登录失败:', err); // eslint-disable-line no-console
						useMessage().error(err?.msg || '系统异常请联系管理员');
						reject(err);
					});
			});
		},

		/**
		 * 社交账号登录方法
		 * @function loginBySocial
		 * @async
		 * @param {SocialLoginEnum} state - 状态
		 * @param {string} code - 代码
		 * @returns {Promise<Object>}
		 */
		async loginBySocial(state: SocialLoginEnum, code: string) {
			return new Promise((resolve, reject) => {
				loginBySocial(state, code)
					.then((res) => {
						// 存储token 信息
						Session.set('token', res.access_token);
						Session.set('refresh_token', res.refresh_token);
						resolve(res);
					})
					.catch((err) => {
						console.error('[login] 登录失败:', err); // eslint-disable-line no-console
						useMessage().error(err?.msg || '系统异常请联系管理员');
						reject(err);
					});
			});
		},

		/**
		 * 刷新token方法（走 /admin/auth/refresh，出参为 R 信封：data.accessToken / data.token.refreshToken）
		 * @function refreshToken
		 * @async
		 * @returns {Promise<any>}
		 */
		async refreshToken() {
			return new Promise((resolve, reject) => {
				const refreshToken = Session.get('refresh_token');
				if (!refreshToken) {
					reject({ msg: '缺少刷新令牌' });
					return;
				}
				refreshTokenApi(refreshToken)
					.then((res: any) => {
						const payload = res?.data ?? res;
						const accessToken = payload?.accessToken || payload?.token?.accessToken;
						if (!accessToken) {
							reject({ msg: '刷新响应缺少令牌' });
							return;
						}
						Session.set(STORAGE_KEYS.TOKEN, accessToken);
						const nextRefresh = payload?.token?.refreshToken;
						if (nextRefresh) Session.set(STORAGE_KEYS.REFRESH_TOKEN, nextRefresh);
						rememberTokenExpiry(payload?.token?.expiresIn);
						resolve(payload);
					})
					.catch((err) => {
						reject(err);
					});
			});
		},

		/**
		 * 获取用户信息方法
		 * @function setUserInfos
		 * @async
		 */
			async setUserInfos() {
				await getUserInfo().then((res) => {
					// 解构后台返回数据
					const { deptList = [], deptId, roleList, permissions } = res.data;

					const userInfo: any = {
						user: res.data,
						time: new Date().getTime(),
						roles: roleList,
						authBtnList: permissions,
						deptId: deptId || '',
						deptList
					};
					this.userInfos = userInfo;

					//设置部门（使用主部门）
					this.updateDeptInfo(deptId);
				});
			},

			/**
			 * 更新部门信息方法
		 * @function updateDeptInfo
		 * @param {string} deptId - 部门ID
		 */
		updateDeptInfo(deptId: string) {
			this.userInfos.deptId = deptId;

			// 保存部门信息到本地
			Session.set(STORAGE_KEYS.DEPT_ID, deptId);
			Local.set(STORAGE_KEYS.DEPT_ID, deptId);
			Cookie.set(STORAGE_KEYS.DEPT_ID, deptId);
		},
	},
});
