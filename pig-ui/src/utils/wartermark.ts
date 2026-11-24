// 页面添加水印效果
const setWatermark = (str: string) => {
	const id = '1.23452384164.123412416';
	if (document.getElementById(id) !== null) document.body.removeChild(<HTMLElement>document.getElementById(id));
	const can = document.createElement('canvas');
	can.width = 420;
	can.height = 280;
	const cans = <CanvasRenderingContext2D>can.getContext('2d');
	cans.rotate((-20 * Math.PI) / 180);
	cans.font = '13px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif';
	// 很淡：日常阅读基本察觉不到，截图放大后仍可辨认来源账号
	cans.fillStyle = 'rgba(148, 163, 184, 0.10)';
	cans.textBaseline = 'middle';
	cans.fillText(str, can.width / 10, can.height / 2);
	const div = document.createElement('div');
	div.id = id;
	div.style.pointerEvents = 'none';
	div.style.top = '0px';
	div.style.left = '0px';
	div.style.position = 'fixed';
	div.style.zIndex = '10000000';
	div.style.width = '100vw';
	div.style.height = '100vh';
	div.style.background = `url(${can.toDataURL('image/png')}) left top repeat`;
	document.body.appendChild(div);
	return id;
};

/**
 * 页面添加水印效果
 * @method set 设置水印
 * @method del 删除水印
 */
const watermark = {
	// 设置水印
	set: (str: string) => {
		let id = setWatermark(str);
		if (document.getElementById(id) === null) id = setWatermark(str);
	},
	// 删除水印
	del: () => {
		let id = '1.23452384164.123412416';
		if (document.getElementById(id) !== null) document.body.removeChild(<HTMLElement>document.getElementById(id));
	},
};

// 导出方法
export default watermark;
