// usage: NODE_PATH=<edgeai>/dashboard/node_modules GU=<grafana user> GP=<grafana password> node grafana-shot.cjs <from_ms> <to_ms> <out.png>
// Grafana의 "NVIDIA DCGM Exporter (HAMi 실험)" 대시보드(grafana.com 12239 가져오기, uid nvidia-dcgm-exporter)를 캡처한다.
const { chromium } = require('@playwright/test');
const GRAFANA = process.env.GRAFANA_URL || 'http://10.110.238.52';
(async () => {
  const [from, to, out] = process.argv.slice(2);
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 1600, height: 2000 }, locale: 'ko-KR', timezoneId: 'Asia/Seoul' });
  const p = await ctx.newPage();
  await p.goto(`${GRAFANA}/login`);
  await p.fill('input[name=user]', process.env.GU);
  await p.fill('input[name=password]', process.env.GP);
  await p.click('button[type=submit]');
  await p.waitForURL(u => !u.pathname.startsWith('/login'));
  await p.goto(`${GRAFANA}/d/nvidia-dcgm-exporter/x?orgId=1&from=${from}&to=${to}&var-instance=10.244.8.34:9400&var-gpu=All&kiosk&theme=light`, { waitUntil: 'networkidle' });
  await p.waitForTimeout(4000);
  await p.screenshot({ path: out, fullPage: true });
  await b.close();
})();
