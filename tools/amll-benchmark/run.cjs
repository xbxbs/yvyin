const { chromium } = require('playwright');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

(async () => {
  const directory = process.env.BENCHMARK_DIR || '/root/amll-benchmark/site';
  const output = process.env.BENCHMARK_OUTPUT || '/root/amll-benchmark/output';
  fs.mkdirSync(output, {recursive:true});
  const types = {'.html':'text/html','.js':'text/javascript','.css':'text/css','.json':'application/json','.jpg':'image/jpeg','.ttf':'font/ttf'};
  const server = http.createServer((request, response) => {
    const file = path.join(directory, request.url === '/' ? 'index.html' : request.url.split('?')[0]);
    if (!file.startsWith(directory + '/')) { response.writeHead(403).end(); return; }
    fs.readFile(file, (error, data) => {
      if (error) { response.writeHead(404).end(); return; }
      response.setHeader('Content-Type', types[path.extname(file)] || 'application/octet-stream');
      response.end(data);
    });
  });
  await new Promise(resolve => server.listen(8795, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch({headless:true,args:['--no-sandbox','--disable-dev-shm-usage','--no-zygote','--disable-gpu']});
    const context = await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:2});
    const page = await context.newPage();
    const failures = [];
    page.on('pageerror', error => { failures.push(error.message); console.error(error.message); });
    await page.goto('http://127.0.0.1:8795');
    await page.waitForFunction(() => window.ready, {timeout:30000});
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(1200);
    for (const timestamp of [34500, 36500, 39900, 42400]) {
      await page.evaluate(next => window.benchmark.seek(next), timestamp);
      await page.waitForTimeout(1500);
      await page.screenshot({path:path.join(output,`amll-${timestamp}.png`)});
    }
    await page.evaluate(() => window.benchmark.seek(35500));
    await page.waitForTimeout(1200);
    await page.evaluate(() => window.benchmark.play());
    const samples = [];
    for (let frame = 0; frame < 28; frame++) {
      await page.waitForTimeout(100);
      samples.push(await page.evaluate(() => ({stamp:document.querySelector('#stamp').textContent,rows:window.benchmark.sample()})));
      await page.screenshot({path:path.join(output,`motion-${String(frame).padStart(2,'0')}.png`)});
    }
    fs.writeFileSync(path.join(output,'motion-samples.json'),JSON.stringify({engine:'@applemusic-like-lyrics/core@0.6.0',viewport:[390,844],failures,samples},null,2));
    if (failures.length) throw new Error(`AMLL page errors: ${failures.join('; ')}`);
    console.log(JSON.stringify({output,failures,screenshots:32}));
  } finally {
    if (browser) await browser.close();
    server.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
