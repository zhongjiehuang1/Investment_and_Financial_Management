/* Optional local browser smoke test. All accounts, API responses and operations are synthetic. */
const {chromium} = require('playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {randomUUID} = require('node:crypto');
const root = path.resolve(__dirname, '../../main/JavaWeb/WebAPP');
const userGeneration = randomUUID();
const assets = [1, 2].map(databaseId => ({id: 7, databaseId, generation: randomUUID(),
    investmentPlatform: `Node ${databaseId === 1 ? 'One' : 'Two'}`, investmentType: 'STOCK',
    riskType: 'VERY_LOW_AND_LOW_RISK_TYPE', currency: 'GBP', realTimeAmount: '10.00',
    investmentAmount: '10.00', buyInAmount: '10.00', amountSold: '0.00', cashWithdrawal: '0.00',
    initialEarnings: '0.00', managementFee: '0.00', handlingFee: '0.00', earnings: '0.00', fees: '0.00'}));
const transactions = assets.map((asset, index) => ({id: 7, databaseId: asset.databaseId, generation: randomUUID(),
    type: 'income', platform: asset.investmentPlatform, platformGeneration: asset.generation,
    name: `Record ${index === 0 ? 'One' : 'Two'}`, amount: '1.00', currency: 'GBP', date: '2026-01-02',
    year: 2026, monthOfYear: 1, dateOfMonth: 2, canEdit: true, canDelete: true, isTransfer: false}));
const writes = [], statusQueries = [], results = new Map(), errors = [];
let loseNextEdit = false;
const response = (res, data) => { res.writeHead(200, {'Content-Type': 'application/json', 'Cache-Control': 'no-store'}); res.end(JSON.stringify(data)); };
const pageData = items => ({status: 'success', userGeneration, items, total: items.length, page: 1, pageSize: 100, hasNext: false});
const report = {status: 'success', userGeneration, currencyCurrentChosen: 'GBP', netWorth: '20.00',
    investmentCount: 2, cashCount: 0, assetCount: 2, income: '2.00', expense: '0.00', monthlyIncome: '2.00',
    monthlyExpense: '0.00', investmentEarnings: '0.00', fees: '0.00', earnings: '0.00', priorEarnings: '0.00',
    cycleEarnings: '0.00', otherPassiveIncome: '0.00', currencyExposure: [], riskDistribution: [], monthly: [], recordCount: 2};
const server = http.createServer(async (req, res) => {
    const endpoint = new URL(req.url, 'http://localhost').pathname;
    if (endpoint === '/api/server') return response(res, {serverId: 2});
    if (endpoint === '/api/cluster-health') return response(res, {status: 'ready'});
    if (req.method === 'POST') {
        let raw = ''; for await (const chunk of req) raw += chunk;
        const input = req.headers['content-type'].includes('json') ? JSON.parse(raw || '{}') : Object.fromEntries(new URLSearchParams(raw));
        if (endpoint === '/viewAllCurrentAssets') return response(res, pageData(input.kind === 'investment' ? assets : []));
        if (endpoint === '/getIncomeAndExpenditureData') return response(res, pageData(transactions));
        if (endpoint === '/getPlatforms') return response(res, pageData(assets.map(item => ({...item, name: item.investmentPlatform, kind: 'investment'}))));
        if (endpoint === '/generatingFinancialReport' || endpoint === '/getCashflowSummary') return response(res, report);
        if (endpoint === '/operationStatus') {
            statusQueries.push(input.operationId);
            return response(res, {status: 'success', operationId: input.operationId, userGeneration,
                state: results.has(input.operationId) ? 'committed' : 'absent', result: results.get(input.operationId)});
        }
        if (['/editInvestmentAsset', '/deleteInvestmentAsset', '/deleteIncomeOrExpense'].includes(endpoint)) {
            const operationId = req.headers['x-iafm-operation-id'];
            assert.ok(operationId); assert.equal(req.headers['x-iafm-user-generation'], userGeneration);
            if (!results.has(operationId)) {
                writes.push({endpoint, input, operationId});
                results.set(operationId, {status: 'success', userGeneration, operationId, operationStatus: 'committed'});
            }
            return response(res, results.get(operationId));
        }
        res.writeHead(404); res.end('Unexpected mock endpoint'); return;
    }
    let filename = path.join(root, endpoint === '/' ? 'index.html' : endpoint);
    if (!filename.startsWith(root + path.sep) || !fs.existsSync(filename) || !fs.statSync(filename).isFile()) { res.writeHead(404); res.end(); return; }
    res.setHeader('Content-Type', ({'.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.webmanifest': 'application/manifest+json'})[path.extname(filename)] || 'application/octet-stream');
    res.end(fs.readFileSync(filename));
});

(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH || undefined});
    try {
        const page = await browser.newPage({viewport: {width: 1360, height: 900}});
        page.on('pageerror', error => errors.push(error.message));
        await page.route('**/editInvestmentAsset', async route => {
            if (!loseNextEdit) return route.continue();
            loseNextEdit = false;
            await route.fetch(); // The server commits, then the response is lost before the page receives it.
            await route.abort('failed');
        });
        await page.addInitScript(generation => {
            sessionStorage.setItem('userName', 'Synthetic test'); sessionStorage.setItem('userAccount', 'synthetic@example.invalid');
            sessionStorage.setItem('userGeneration', generation);
            localStorage.setItem('finance-workspace.cookie-choice', JSON.stringify({preferences: false, at: Date.now()}));
        }, userGeneration);
        await page.goto(`http://127.0.0.1:${server.address().port}/#assets`, {waitUntil: 'networkidle'});
        await page.locator('[data-edit-investment]').nth(1).click();
        assert.equal(await page.locator('#investmentPlatform').inputValue(), 'Node Two');
        await page.locator('#realTimeAmount').fill('12.00'); await page.locator('#saveAsset').click();
        await page.locator('#assetForm').waitFor({state: 'hidden'});
        assert.equal(writes[0].input.databaseId, '2'); assert.equal(writes[0].input.generation, assets[1].generation);
        await page.locator('[data-delete-investment]').nth(0).click();
        await Promise.all([page.waitForResponse(reply => new URL(reply.url()).pathname === '/deleteInvestmentAsset'), page.locator('#confirmAction').click()]);
        await page.waitForFunction(() => !Object.keys(localStorage).some(key => key.startsWith('finance-pending-operation:')));
        assert.equal(writes[1].input.databaseId, '1'); assert.equal(writes[1].input.generation, assets[0].generation);

        await page.locator('[data-edit-investment]').nth(1).click();
        await page.locator('#realTimeAmount').fill('13.00'); loseNextEdit = true;
        await Promise.all([page.waitForEvent('requestfailed', request => new URL(request.url()).pathname === '/editInvestmentAsset'), page.locator('#saveAsset').click()]);
        await page.waitForFunction(() => document.querySelector('#saveAsset') && !document.querySelector('#saveAsset').disabled);
        const lost = writes.at(-1); const beforeRetry = writes.length;
        assert.equal(lost.input.realTimeAmount, '13.00');
        const persisted = await page.evaluate(() => Object.entries(localStorage).filter(([key]) => key.startsWith('finance-pending-operation:')).map(([, value]) => value));
        assert.equal(persisted.length, 1); assert.equal(persisted[0].includes('13.00'), false); assert.equal(persisted[0].includes('Node Two'), false);
        await page.locator('#saveAsset').click(); await page.locator('#assetForm').waitFor({state: 'hidden'});
        assert.equal(writes.length, beforeRetry); assert.equal(statusQueries.at(-1), lost.operationId);

        await page.locator('[data-nav="transactions"]').first().click();
        await page.locator('[data-edit-tx]').nth(1).click(); assert.equal(await page.locator('#name').inputValue(), 'Record Two');
        await page.locator('[data-close-modal]').first().click();
        await page.locator('[data-delete-tx]').nth(1).click();
        await Promise.all([page.waitForResponse(reply => new URL(reply.url()).pathname === '/deleteIncomeOrExpense'), page.locator('#confirmAction').click()]);
        await page.waitForFunction(() => !Object.keys(localStorage).some(key => key.startsWith('finance-pending-operation:')));
        assert.equal(writes.at(-1).input.databaseId, '2'); assert.equal(writes.at(-1).input.generation, transactions[1].generation);
        await page.setViewportSize({width: 390, height: 844});
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
        assert.deepEqual(errors, []);
        console.log('PASS: actual finance UI resolves colliding asset/transaction IDs by node+generation, preserves only operation metadata, recovers a lost commit response without applying the mutation twice, and remains usable on mobile.');
    } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });
