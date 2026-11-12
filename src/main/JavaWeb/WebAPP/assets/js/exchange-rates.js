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
        en: {title: 'Daily exchange rates', intro: 'The latest available daily snapshot from ExchangeRate-API. Updated at most once every 24 hours; these are reference rates, not live trading quotes.', back: '← Finance Workspace', eyebrow: 'IAFM · CURRENCY REFERENCE', base: 'Base currency', loading: 'Loading daily rates…', unavailable: 'Daily rates are temporarily unavailable. Please try again later.', updated: 'Provider updated', stale: 'The latest refresh was unavailable; the previous snapshot is shown.', footer: 'Values are displayed to two decimal places. Currency calculations retain the full provider precision. Your bank or payment provider may apply a different rate and fees.'},
        zh: {title: '每日汇率', intro: '查看 ExchangeRate-API 提供的最新每日快照。每 24 小时最多更新一次，仅供参考，并非实时交易报价。', back: '← 财务工作台', eyebrow: 'IAFM · 货币汇率参考', base: '基准货币', loading: '正在读取每日汇率…', unavailable: '每日汇率暂时不可用，请稍后再试。', updated: '数据更新时间', stale: '最近未能更新，当前显示上一份可用快照。', footer: '前端数值保留两位小数，货币计算使用数据源的完整精度。银行或支付机构可能采用不同汇率并收取费用。'}
    };
    function render() {
        const text = copy[lang];
        document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
        window.FinanceConnectionStatus?.setLanguage(lang);
        document.documentElement.dataset.theme = (preferencesAllowed() && localStorage.getItem('finance-workspace.theme')) || 'dark';
        document.title = `${text.title} — IAFM`;
        for (const [id, key] of Object.entries({ratesTitle: 'title', ratesIntro: 'intro', backLink: 'back', eyebrow: 'eyebrow', baseLabel: 'base', ratesFooter: 'footer'})) $(id).textContent = text[key];
        $('ratesLang').textContent = lang === 'zh' ? 'English' : '中文';
        $('baseCurrency').setAttribute('aria-label', text.base);
        $('ratesGrid').replaceChildren();
        if (!snapshot) { $('ratesStatus').textContent = failed ? text.unavailable : text.loading; return; }
        const date = new Date(snapshot.lastUpdatedAt).toLocaleString(lang === 'zh' ? 'zh-CN' : 'en-GB', {timeZoneName: 'short'});
        $('ratesStatus').textContent = `${text.updated}: ${date}${snapshot.stale ? ` · ${text.stale}` : ''}`;
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
            if (data.status !== 'success' || !data.rates || !codes.every(code => Number.isFinite(data.rates[code]) && data.rates[code] > 0) || !Number.isFinite(Date.parse(data.lastUpdatedAt))) throw new Error('Invalid snapshot');
            snapshot = data; render();
        }).catch(() => { failed = true; render(); });
})();
