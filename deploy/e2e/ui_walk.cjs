// 浏览器逐页实操：6 个角色登录 → 访问每个业务页 → 收集控制台错误、接口错误、页面错误提示、按钮可见性；
// 再用 PM 在详情页真实点击 暂停 / 恢复 / 作废阶段 / 取消。不打印口令或令牌。
const path = require('path');
const fs = require('fs');
const { chromium } = require(path.join(process.env.HOME, '.npm/_npx/e41f203b7505f1fb/node_modules/playwright'));

const BASE = 'http://127.0.0.1:8080';
const API = BASE + '/api/admin';
const ROOT = '/Users/ellen/Desktop/编程项目/Company-System';
const ACC = JSON.parse(fs.readFileSync(ROOT + '/deploy/e2e/.accounts.json', 'utf8'));
const OUT = path.join(__dirname, 'ui_walk_out');
fs.mkdirSync(OUT, { recursive: true });
const TAG = 'UI-' + Date.now().toString().slice(-8);

const PAGES = [
	['公司进度', '/mold/projects'],
	['我的工作', '/mold/my-stages'],
	['采购台账', '/mold/purchases'],
	['财务收支', '/mold/cash'],
	['更正记录', '/mold/corrections'],
	['阶段历史', '/mold/stages'],
	['账号管理', '/mold/accounts'],
	['审计日志', '/mold/audit'],
];

const report = { roles: {}, flows: [], problems: [] };
const problem = (where, what) => {
	report.problems.push(`${where}: ${what}`);
	console.log(`  !! ${where}: ${what}`);
};

async function api(method, p, token, body, extra = {}) {
	const h = { Accept: 'application/json', ...extra };
	if (body !== undefined) h['Content-Type'] = 'application/json';
	if (token) h.Authorization = 'Bearer ' + token;
	const r = await fetch(API + p, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
	let j = null;
	try { j = await r.json(); } catch {}
	return { status: r.status, data: j && j.data, msg: j && j.msg };
}
const idem = () => 'ui-' + Math.random().toString(36).slice(2) + Date.now();

async function apiLogin(role) {
	const a = ACC[role];
	const r = await api('POST', '/auth/login', null, { loginName: a.loginName, password: a.password });
	if (r.status !== 200) throw new Error(role + ' API 登录失败 ' + r.status);
	return r.data.accessToken;
}

async function uiLogin(page, role) {
	const a = ACC[role];
	await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' });
	const user = page.locator('.login-content-form input').nth(0);
	const pwd = page.locator('.login-content-form input[type="password"]').first();
	await user.fill(a.loginName);
	await pwd.fill(a.password);
	const btn = page.locator('.login-content-form button').filter({ hasText: /登\s*录/ }).first();
	if (await btn.count()) await btn.click();
	else await pwd.press('Enter');
	await page.waitForFunction(() => !location.hash.startsWith('#/login'), null, { timeout: 20000 }).catch(() => {});
	await page.waitForLoadState('networkidle').catch(() => {});
	return page.url();
}

function attach(page, bucket) {
	page.on('console', (m) => {
		if (m.type() === 'error') bucket.console.push(m.text().slice(0, 200));
	});
	page.on('pageerror', (e) => bucket.console.push('pageerror: ' + String(e.message).slice(0, 200)));
	page.on('response', (r) => {
		const u = r.url();
		if (u.includes('/api/') && r.status() >= 400) bucket.http.push(`${r.status()} ${r.request().method()} ${u.replace(BASE, '').split('?')[0]}`);
	});
}

async function visibleTexts(page, sel) {
	return page.locator(sel).evaluateAll((els) =>
		els.filter((e) => e.offsetParent !== null).map((e) => (e.innerText || '').trim()).filter(Boolean).slice(0, 5)
	);
}

async function dropdownItems(page) {
	const more = page.locator('[data-testid="project-more"]').first();
	if (!(await more.count())) return null;
	await more.click();
	await page.waitForTimeout(400);
	const items = await page.locator('.el-dropdown-menu:visible .el-dropdown-menu__item').allInnerTexts();
	await page.keyboard.press('Escape');
	await page.waitForTimeout(200);
	return items.map((s) => s.trim());
}

(async () => {
	const pmTok = await apiLogin('PM');
	const pr = await api('POST', '/mold/projects', pmTok,
		{ code: TAG, name: `${TAG} 界面实操项目`, businessType: 'MOLD_SET', ownerUserId: ACC.PM.userId },
		{ 'Idempotency-Key': idem() });
	if (![200, 201].includes(pr.status)) throw new Error('建项目失败 ' + pr.status + ' ' + pr.msg);
	const pid = pr.data.id;
	for (const [n, i] of [['UI 建错的阶段', 1], ['UI 编程', 2]]) {
		await api('POST', `/mold/projects/${pid}/stages`, pmTok, { name: n, sortOrder: i }, { 'Idempotency-Key': idem() });
	}
	console.log('UI 项目已建：' + TAG);

	const browser = await chromium.launch({ channel: 'chrome', headless: true });

	for (const role of ['OWNER', 'PM', 'PROGRAMMER', 'MACHINIST', 'FINANCE', 'VIEWER']) {
		console.log(`\n== ${role}`);
		const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
		const page = await ctx.newPage();
		const R = { console: [], http: [], pages: {} };
		attach(page, R);
		const after = await uiLogin(page, role);
		R.loginLanding = after.replace(BASE, '');
		console.log('  登录后落地：' + R.loginLanding);
		if (after.includes('/login')) problem(role, '登录后仍停在登录页');
		if (after.includes('change-password')) problem(role, '登录后被要求改密（测试账号不应如此）');
		await page.locator('.el-menu-horizontal-warp .el-menu-item, .el-menu-horizontal-warp .el-sub-menu__title').first().waitFor({ timeout: 10000 }).catch(() => {});
		await page.waitForTimeout(500);
		const menu = await page.locator('.el-menu-horizontal-warp .el-menu-item, .el-menu-horizontal-warp .el-sub-menu__title, .el-menu--vertical .el-menu-item').allInnerTexts().catch(() => []);
		R.menu = [...new Set(menu.map((s) => s.trim()).filter(Boolean))];
		console.log('  菜单：' + R.menu.join(' / '));

		for (const [title, p] of PAGES) {
			const h0 = R.http.length, c0 = R.console.length;
			await page.goto(BASE + '/#' + p, { waitUntil: 'networkidle' }).catch(() => {});
			await page.waitForTimeout(2000);
			const url = page.url().replace(BASE, '');
			const errs = await visibleTexts(page, '.el-alert--error, .el-message--error, .mold-state-panel.is-error, [data-testid$="-error"]');
			const rowsN = await page.locator('.el-table__body tr:visible').count();
			const info = { url, errors: errs, rows: rowsN, http: R.http.slice(h0), console: R.console.slice(c0) };
			R.pages[title] = info;
			const redirected = !url.includes(p);
			console.log(`  ${title} → ${redirected ? '跳转 ' + url : '打开'}，表格行 ${rowsN}` +
				(errs.length ? `，错误提示：${errs.join(' | ')}` : '') +
				(info.http.length ? `，接口错误：${info.http.join('; ')}` : '') +
				(info.console.length ? `，控制台：${info.console.length} 条` : ''));
			const inMenu = R.menu.some((m) => m.includes(title));
			if (!redirected && !inMenu && !errs.length && rowsN > 0) problem(role, `菜单里没有「${title}」，但直达 URL 能看到数据`);
			if (inMenu && (info.http.some((x) => x.startsWith('5')) || info.console.some((x) => x.startsWith('pageerror'))))
				problem(role, `「${title}」有服务端错误或脚本异常：${[...info.http, ...info.console].join('; ')}`);
			if (info.http.some((x) => x.startsWith('403'))) problem(role, `「${title}」页面请求了无权限的接口：${info.http.join('; ')}`);
			if (info.console.length) problem(role, `「${title}」控制台报错：${info.console.join('; ')}`);
			await page.screenshot({ path: `${OUT}/${role}_${title}.png` });
		}

		await page.goto(BASE + '/#/mold/project/' + pid, { waitUntil: 'networkidle' });
		await page.waitForTimeout(800);
		const btn = {
			edit: await page.locator('[data-testid="project-edit"]:visible').count(),
			complete: await page.locator('[data-testid="project-complete"]:visible').count(),
			voidStage: await page.locator('[data-testid^="stage-void-"]:visible').count(),
			more: await dropdownItems(page),
			createIssue: await page.locator('button:visible', { hasText: /登记异常|报异常|新建异常/ }).count(),
		};
		R.detailButtons = btn;
		console.log('  详情页按钮：' + JSON.stringify(btn));
		await page.screenshot({ path: `${OUT}/${role}_详情.png`, fullPage: true });
		const mgr = role === 'OWNER' || role === 'PM';
		if (mgr && (!btn.edit || btn.voidStage < 2)) problem(role, '负责人/老板在详情页缺少编辑或作废阶段按钮');
		if (mgr && !(btn.more || []).includes('暂停项目')) problem(role, '更多菜单缺少「暂停项目」');
		if (mgr && !(btn.more || []).includes('取消项目')) problem(role, '更多菜单缺少「取消项目」');
		if ((btn.more || []).includes('变更负责人') !== (role === 'OWNER')) problem(role, '「变更负责人」可见性不符合“仅老板”');
		if (!mgr && (btn.edit || btn.complete || btn.voidStage || (btn.more || []).some((x) => /暂停|恢复|取消|添加阶段/.test(x))))
			problem(role, '非负责人看到了写操作按钮：' + JSON.stringify(btn));
		if (role === 'VIEWER' && btn.createIssue) problem(role, '只读角色看到了登记异常按钮');
		report.roles[role] = R;
		await ctx.close();
	}

	console.log('\n== PM 详情页真实操作');
	const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
	const page = await ctx.newPage();
	const F = { console: [], http: [] };
	attach(page, F);
	await uiLogin(page, 'PM');
	const go = async () => {
		await page.goto(BASE + '/#/mold/project/' + pid, { waitUntil: 'networkidle' });
		await page.waitForTimeout(600);
	};
	const pageText = async () => (await page.locator('body').innerText().catch(() => '')).replace(/\s+/g, ' ');
	const lastMsg = async (want) => {
		const m = want ? page.locator('.el-message', { hasText: want }).last() : page.locator('.el-message').last();
		await m.waitFor({ timeout: 6000 }).catch(() => {});
		return (await m.innerText().catch(() => '')).trim();
	};
	async function menuAction(label, reason, expectBox, want) {
		await page.locator('[data-testid="project-more"]').first().click();
		await page.waitForTimeout(300);
		await page.locator('.el-dropdown-menu:visible .el-dropdown-menu__item', { hasText: label }).click();
		const box = page.locator('.el-message-box:visible');
		await box.waitFor({ timeout: 5000 });
		const text = (await box.innerText()).replace(/\s+/g, ' ');
		if (expectBox && !text.includes(expectBox)) problem('PM 操作', `「${label}」确认框文案缺少「${expectBox}」：${text}`);
		let v = '';
		if (reason) {
			await box.locator('.el-message-box__btns button', { hasText: '确认' }).click();
			await page.waitForTimeout(300);
			v = (await box.locator('.el-message-box__errormsg').innerText().catch(() => '')).trim();
			if (!v) problem('PM 操作', `「${label}」未填原因也能提交`);
			await box.locator('textarea').fill(reason);
		}
		await box.locator('.el-message-box__btns button', { hasText: '确认' }).click();
		const msg = await lastMsg(want);
		await page.waitForTimeout(800);
		return { text, validation: v, msg };
	}
	const statusIn = (t) => (t.match(/(进行中|已暂停|已完工|已取消)/g) || []).slice(0, 3).join(',');

	await go();
	let r = await menuAction('暂停项目', '界面实操：客户暂缓', '暂停项目', '项目已暂停');
	await go();
	F.hold = { ...r, statusWords: statusIn(await pageText()) };
	console.log('  暂停：' + JSON.stringify(F.hold));
	if (!/已暂停/.test(r.msg + F.hold.statusWords)) problem('PM 操作', '暂停后没有看到“已暂停”');

	r = await menuAction('恢复项目', null, null, '项目已恢复');
	await go();
	F.resume = { ...r, statusWords: statusIn(await pageText()) };
	console.log('  恢复：' + JSON.stringify(F.resume));
	if (!/已恢复|进行中/.test(r.msg + F.resume.statusWords)) problem('PM 操作', '恢复后没有看到“进行中”');

	const before = await page.locator('.mold-stage-list li').count();
	await page.locator('[data-testid^="stage-void-"]').first().click();
	const box = page.locator('.el-message-box:visible');
	await box.waitFor({ timeout: 5000 });
	const vtext = (await box.innerText()).replace(/\s+/g, ' ');
	await box.locator('.el-message-box__btns button', { hasText: '确认' }).click();
	await page.waitForTimeout(300);
	const vval = (await box.locator('.el-message-box__errormsg').innerText().catch(() => '')).trim();
	await box.locator('textarea').fill('界面实操：阶段建重复了');
	await box.locator('.el-message-box__btns button', { hasText: '确认' }).click();
	const vmsg = await lastMsg('阶段已作废');
	await page.waitForFunction((n) => document.querySelectorAll('.mold-stage-list li').length === n - 1, before, { timeout: 8000 }).catch(() => {});
	const afterN = await page.locator('.mold-stage-list li').count();
	F.void = { text: vtext, validation: vval, msg: vmsg, stagesBefore: before, stagesAfter: afterN };
	console.log('  作废阶段：' + JSON.stringify(F.void));
	if (!vval) problem('PM 操作', '作废阶段未填原因也能提交');
	if (afterN !== before - 1) problem('PM 操作', `作废后阶段数没有减少（${before}→${afterN}）`);

	r = await menuAction('取消项目', '界面实操：客户撤单', '不能再编辑', '项目已取消');
	await go();
	F.cancel = { ...r, statusWords: statusIn(await pageText()) };
	F.cancel.leftButtons = {
		edit: await page.locator('[data-testid="project-edit"]:visible').count(),
		voidStage: await page.locator('[data-testid^="stage-void-"]:visible').count(),
		more: await dropdownItems(page),
	};
	F.cancel.completionCard = (await page.locator('.mold-detail-card', { hasText: '整单完工' }).innerText().catch(() => '')).replace(/\s+/g, ' ');
	console.log('  取消：' + JSON.stringify(F.cancel));
	if (!/项目已取消/.test(F.cancel.completionCard)) problem('PM 操作', '已取消项目的完工卡片仍显示未完工说明：' + F.cancel.completionCard);
	if (!/已取消/.test(r.msg + F.cancel.statusWords)) problem('PM 操作', '取消后没有看到“已取消”');
	if (F.cancel.leftButtons.edit || F.cancel.leftButtons.voidStage) problem('PM 操作', '已取消项目仍显示编辑/作废按钮');
	if ((F.cancel.leftButtons.more || []).some((x) => /暂停|恢复|取消项目|添加阶段/.test(x))) problem('PM 操作', '已取消项目仍显示状态操作：' + F.cancel.leftButtons.more);
	await page.screenshot({ path: `${OUT}/PM_取消后详情.png`, fullPage: true });
	if (F.http.some((x) => !x.startsWith('4'))) problem('PM 操作', '操作过程中出现服务端错误：' + F.http.join('; '));
	if (F.console.some((x) => x.startsWith('pageerror'))) problem('PM 操作', '操作过程中有脚本异常：' + F.console.join('; '));
	report.flows.push(F);
	await ctx.close();
	await browser.close();

	fs.writeFileSync(OUT + '/report.json', JSON.stringify(report, null, 2));
	console.log(`\n问题 ${report.problems.length} 条` + (report.problems.length ? '：\n- ' + report.problems.join('\n- ') : ''));
})().catch((e) => {
	console.error('脚本异常：' + (e && e.stack ? e.stack.split('\n').slice(0, 3).join(' | ') : e));
	process.exit(2);
});
