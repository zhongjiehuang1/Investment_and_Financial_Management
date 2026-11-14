(() => {
    'use strict';
    const base = new URL('../../', document.currentScript.src);
    const codes = ['USD', 'GBP', 'CNY', 'EUR', 'JPY', 'HKD', 'AUD', 'CAD', 'CHF', 'SGD'];
    function preferencesAllowed() {
        try {
            const choice = JSON.parse(localStorage.getItem('finance-workspace.cookie-choice'));
            return choice?.preferences === true && Number.isFinite(choice.at) && Date.now() >= choice.at && Date.now() - choice.at < 180 * 24 * 60 * 60 * 1000;
        } catch (_) { return false; }
    }
    let lang = preferencesAllowed() && localStorage.getItem('finance-workspace.lang') === 'zh' ? 'zh' : 'en';
    let snapshot = null, failed = false;
    const $ = id => document.getElementById(id);
    const copy = {
        en: {title: 'Daily exchange rates', intro: 'Daily reference rates from the European Central Bank by default. The actual source and reference date are shown below. No new ECB quote is published on weekends or market holidays; these are not trading prices.', back: '← Finance Workspace', eyebrow: 'IAFM · CURRENCY REFERENCE', base: 'Base currency', loading: 'Loading daily rates…', unavailable: 'Daily rates are temporarily unavailable. Please try again later.', updated: 'Reference date (UTC)', fetched: 'Checked (UTC)', source: 'Source', stale: 'An earlier reference date is shown; no newer quote is assumed.', footer: 'Values are displayed to two decimal places. Currency calculations retain the full provider precision. Your bank or payment provider may apply a different rate and fees.'},
        zh: {title: '每日汇率', intro: '默认使用欧洲中央银行每日参考汇率，下方显示实际来源和参考日期。周末及市场休假日没有新的欧洲央行报价；这些数据不是实时交易价格。', back: '← 财务工作台', eyebrow: 'IAFM · 货币汇率参考', base: '基准货币', loading: '正在读取每日汇率…', unavailable: '每日汇率暂时不可用，请稍后再试。', updated: '参考日期（UTC）', fetched: '核查时间（UTC）', source: '来源', stale: '当前显示较早的参考日期，不会假定已有新报价。', footer: '前端数值保留两位小数，货币计算使用数据源的完整精度。银行或支付机构可能采用不同汇率并收取费用。'}
    };
    function render() {
        const text = copy[lang];
        document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
        window.FinanceConnectionStatus?.setLanguage(lang);
        document.documentElement.dataset.theme = (preferencesAllowed() && localStorage.getItem('finance-workspace.theme')) || 'dark';
        document.title = `${text.title} — IAFM`;
        for (const [id, key] of Object.entries({ratesTitle: 'title', ratesIntro: 'intro', backLink: 'back', eyebrow: 'eyebrow', baseLabel: 'base', ratesFooter: 'footer'})) $(id).textContent = text[key];
        $('ratesLang').textContent = lang === 'zh' ? 'English' : '中文';
        $('ratesLang').setAttribute('aria-label', lang === 'zh' ? '切换到英语' : 'Switch to Chinese');
        $('baseCurrency').setAttribute('aria-label', text.base);
        $('ratesGrid').replaceChildren();
        if (!snapshot) { $('ratesStatus').textContent = failed ? text.unavailable : text.loading; return; }
        const date = new Date(snapshot.asOf+'T00:00:00Z').toLocaleDateString(lang === 'zh' ? 'zh-CN' : 'en-GB', {timeZone: 'UTC'});
        const fetched = new Date(snapshot.fetchedAt).toLocaleString(lang === 'zh' ? 'zh-CN' : 'en-GB', {timeZone: 'UTC'});
        const source = snapshot.source === 'ecb' ? (lang === 'zh' ? '欧洲中央银行（ECB）' : 'European Central Bank (ECB)') : 'ExchangeRate-API';
        $('ratesStatus').textContent = `${text.source}: ${source} · ${text.updated}: ${date} · ${text.fetched}: ${fetched}${snapshot.stale ? ` · ${text.stale}` : ''}`;
        const selected = $('baseCurrency').value;
        for (const currency of codes) {
            const card = document.createElement('article'); card.className = 'card rate-card';
            const label = document.createElement('span'); label.textContent = currency;
            const value = document.createElement('strong'); value.textContent = (snapshot.rates[currency] / snapshot.rates[selected]).toFixed(2);
            const reference = document.createElement('small'); reference.textContent = `1.00 ${selected} → ${currency}`;
            card.append(label, value, reference); $('ratesGrid').append(card);
        }
    }
    $('baseCurrency').addEventListener('change', render);
    $('ratesLang').addEventListener('click', () => { lang = lang === 'zh' ? 'en' : 'zh'; if (preferencesAllowed()) localStorage.setItem('finance-workspace.lang', lang); render(); });
    render();
    fetch(new URL('getExchangeRates', base), {credentials: 'same-origin', cache: 'no-store', headers: {Accept: 'application/json'}})
        .then(response => { if (!response.ok) throw new Error('Unavailable'); return response.json(); })
        .then(data => {
            if (data.status !== 'success' || !data.rates || !codes.every(code => Number.isFinite(data.rates[code]) && data.rates[code] > 0) || !['ecb', 'exchangerate-api'].includes(data.source) || !/^\d{4}-\d{2}-\d{2}$/.test(data.asOf) || !Number.isFinite(Date.parse(data.asOf+'T00:00:00Z')) || !Number.isFinite(Date.parse(data.fetchedAt))) throw new Error('Invalid snapshot');
            snapshot = data; render();
        }).catch(() => { failed = true; render(); });
})();
