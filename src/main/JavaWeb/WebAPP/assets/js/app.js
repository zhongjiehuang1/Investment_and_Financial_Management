(() => {
    'use strict';

    const API_BASE = new URL('../../', document.currentScript.src).pathname.replace(/\/$/, '');
    const PAGE_SIZE = 100;
    const pendingRequests = new Map();
    let renderVersion = 0;
    let searchTimer;
    const STORE = {
        lang: 'finance-workspace.lang',
        theme: 'finance-workspace.theme',
        privacy: 'finance-workspace.privacy',
        consent: 'finance-workspace.cookie-choice',
        goals: 'finance-workspace.goals',
        budgets: 'finance-workspace.budgets',
        settings: 'finance-workspace.settings'
    };
    const DEFAULTS = {
        assetTypes: ['DEMAND_DEPOSIT', 'FIXED_DEPOSIT', 'MONEY_MARKET_FUND', 'STOCK'],
        riskTypes: ['VERY_LOW_AND_LOW_RISK_TYPE', 'MEDIUM_LOW_AND_MEDIUM_RISK_TYPE', 'MEDIUM_HIGH_AND_HIGH_RISK_TYPE'],
        currencies: ['USD', 'GBP', 'CNY', 'EUR', 'JPY', 'HKD', 'AUD', 'CAD', 'CHF', 'SGD']
    };
    function readCookieChoice() {
        try {
            const choice = JSON.parse(localStorage.getItem(STORE.consent));
            if (choice && typeof choice.preferences === 'boolean' && Number.isFinite(choice.at)
                && Date.now() >= choice.at && Date.now() - choice.at < 180 * 24 * 60 * 60 * 1000) return choice;
        } catch (_) { /* Ask again when the saved choice is invalid. */ }
        return null;
    }
    let cookieChoice = readCookieChoice();
    let cookiePanelOpen = !cookieChoice;
    const remembered = key => cookieChoice?.preferences ? localStorage.getItem(key) : null;
    const state = {
        lang: remembered(STORE.lang) === 'zh' ? 'zh' : 'en',
        theme: remembered(STORE.theme) || 'dark',
        privacy: remembered(STORE.privacy) === 'true',
        user: {
            name: sessionStorage.getItem('userName') || '',
            account: sessionStorage.getItem('userAccount') || '',
            generation: sessionStorage.getItem('userGeneration') || ''
        },
        authTab: 'login', page: 'overview', sidebarOpen: false, loading: new Set(),
        assets: null, transactions: null, recentTransactions: null, report: null, cashflowSummary: null,
        inflation: null, platforms: null,
        assetPage: 1, txPage: 1, inflationPage: 1, sessionVersion: 0, dataVersion: 0,
        assetSearch: '', assetTab: 'investment', riskFilter: 'all', txSearch: '', txType: 'all',
        txPlatform: 'all', txSection: 'cashflow', txSort: {by: 'date', direction: 'desc'},
        inflationCurrency: 'all', inflationSort: {by: 'date', direction: 'desc'},
        settingsTab: 'privacy', dropdown: null
    };

    const I18N = {
        en: {
            appName: 'Finance Workspace',
            tagline: 'Investment & Financial Management',
            privateWorkspace: 'Private wealth workspace',
            authEyebrow: 'Clarity for every financial decision',
            authTitle: 'See your money. Shape your <span>future.</span>',
            authDesc: 'A private, bilingual command centre for investments, cash flow, risk, goals and financial health.',
            localFirst: 'No third-party trackers',
            encryptedSession: 'Session-protected access',
            bilingual: 'English + 中文',
            exchangeRate: 'Daily exchange rates',
            viewExchangeRates: 'View major currencies',
            sessionSecurity: 'Session security',
            dataControl: 'Data control',
            live: 'Live from your server',
            login: 'Sign in',
            register: 'Create account',
            welcomeBack: 'Welcome back',
            loginIntro: 'Access your private financial workspace.',
            createAccount: 'Create your account',
            registerIntro: 'Start a secure personal finance workspace.',
            email: 'Email address',
            password: 'Password',
            firstName: 'First name',
            lastName: 'Last name',
            emailPlaceholder: 'you@example.com',
            passwordPlaceholder: 'Enter your password',
            firstNamePlaceholder: 'Your first name',
            lastNamePlaceholder: 'Your last name',
            signIn: 'Sign in securely',
            createAccountButton: 'Create account',
            privacyNote: 'Credentials are sent only to this server. Finance Workspace does not load ads, analytics or third-party scripts.',
            weak: 'Weak',
            fair: 'Fair',
            strong: 'Strong',
            showPassword: 'Show password',
            hidePassword: 'Hide password',
            overview: 'Overview',
            assets: 'Assets',
            transactions: 'Cash flow',
            reports: 'Reports',
            inflation: 'Inflation',
            goals: 'Goals & budgets',
            settings: 'Settings',
            workspace: 'Workspace',
            planning: 'Planning',
            account: 'Account',
            protected: 'Privacy protected',
            protectedDesc: 'Sensitive values can be hidden instantly. Local planning data stays on this device.',
            goodMorning: 'Good morning',
            goodAfternoon: 'Good afternoon',
            goodEvening: 'Good evening',
            financialOverview: 'Here is your financial position at a glance.',
            refreshed: 'Updated just now',
            totalNetWorth: 'Total net worth',
            monthlyIncome: 'Monthly income',
            monthlyExpenses: 'Monthly expenses',
            savingsRate: 'Savings rate',
            acrossAssets: 'Across all tracked assets',
            currentMonth: 'Current calendar month',
            incomeMinusExpense: 'Income minus expenses',
            portfolioPulse: 'Portfolio pulse',
            portfolioDesc: 'Asset values and account balances from your records',
            riskAllocation: 'Risk allocation',
            riskDesc: 'Current liquid assets by risk level',
            lowRisk: 'Low risk',
            mediumRisk: 'Medium risk',
            highRisk: 'High risk',
            financialGoals: 'Financial goals',
            insights: 'Smart insights',
            quickActions: 'Quick actions',
            addAsset: 'Add asset',
            addTransaction: 'Add transaction',
            generateReport: 'Generate report',
            viewAll: 'View all',
            noData: 'No data yet',
            noDataDesc: 'Add your first record to unlock personalised analytics.',
            riskBalanced: 'Risk profile is balanced',
            riskBalancedDesc: 'Your allocation is spread across multiple risk bands.',
            highRiskAlert: 'High-risk concentration',
            highRiskAlertDesc: 'High-risk assets represent a large share of your portfolio.',
            savingsHealthy: 'Positive savings rate',
            savingsHealthyDesc: 'Income is currently ahead of expenditure.',
            savingsNegative: 'Cash flow needs attention',
            savingsNegativeDesc: 'Expenses are currently higher than income.',
            recentActivity: 'Recent activity',
            name: 'Name',
            type: 'Type',
            platform: 'Platform',
            amount: 'Amount',
            date: 'Date',
            actions: 'Actions',
            investmentAssets: 'Investments',
            nonInvestmentAssets: 'Cash & deposits',
            searchAssets: 'Search assets…',
            allRisk: 'All risk levels',
            currentValue: 'Current value',
            earnings: 'Earnings',
            currency: 'Currency',
            risk: 'Risk',
            assetType: 'Asset type',
            investmentPlatform: 'Investment platform',
            investmentType: 'Investment type',
            initialAmount: 'Initial investment',
            cashWithdrawal: 'Cash withdrawn',
            buyInAmount: 'Additional buys',
            amountSold: 'Amount sold',
            managementFee: 'Management fee',
            handlingFee: 'Handling fee',
            realTimeAmount: 'Current account value (including cash)',
            initialEarnings: 'Initial earnings',
            assetName: 'Asset name',
            edit: 'Edit',
            delete: 'Delete',
            save: 'Save',
            cancel: 'Cancel',
            confirmDelete: 'Delete this record?',
            destructiveDesc: 'This action updates your server records and cannot be undone.',
            deleteAccountDesc: 'This deletes the account and its linked income, expenses, and paired transfers. Balances of surviving transfer accounts will be adjusted. This cannot be undone.',
            allTransactions: 'All transactions',
            income: 'Income',
            expense: 'Expense',
            allTypes: 'All types',
            transferIn: 'Internal transfer in',
            transferOut: 'Internal transfer out',
            internalTransfer: 'Internal transfer',
            cashflowEntries: 'Income & expenses',
            internalTransfers: 'Internal transfers',
            allCurrencies: 'All currencies',
            sortAscending: 'Sort ascending',
            sortDescending: 'Sort descending',
            inflationMonthHint: 'Monthly readings are recorded on the 15th.',
            observedThrough: 'Through',
            inflationSpanEdit: 'This rate covers the displayed date range. Editing changes the whole range; its currency and start month stay fixed.',
            inflationSpanDelete: 'Delete every monthly reading in this date range?',
            counterpartAccount: 'Counterpart account',
            pairedTransfer: 'Paired transfer',
            unpairedTransfer: 'Historical transfer · pairing pending',
            transferDeleteHint: 'This reverses the entire transfer: both account entries will be removed together.',
            transferReadOnly: 'Read only',
            transferLegs: 'Account entries',
            transferExcluded: 'Internal transfers are excluded from income and expenses.',
            transferBetweenAccounts: 'Transfer between accounts',
            fromAccount: 'From account',
            toAccount: 'To account',
            receivedAmount: 'Amount received (destination currency)',
            receivedAmountHint: 'Leave blank for the same currency. Required when the currencies differ.',
            transferEditHint: 'This entry is one side of a transfer. Check the matching entry before editing or deleting it.',
            differentAccounts: 'Choose two different accounts.',
            previousPage: 'Previous',
            nextPage: 'Next',
            page: 'Page',
            perPage: 'per page',
            totalsAllRecords: 'Totals include all matching records.',
            monthlyActivity: 'Monthly income & expenses',
            monthlyActivityDesc: 'Recorded income and expenses; internal transfers excluded.',
            investmentEarnings: 'Investment earnings',
            otherPassiveIncome: 'Other passive income',
            nativeCurrency: 'Original currency',
            reportingValue: 'Reporting value',
            currentPageAverage: 'Average on this page',
            allPlatforms: 'All platforms',
            searchTransactions: 'Search transactions…',
            records: 'Records',
            month: 'Month',
            day: 'Day',
            year: 'Year',
            add: 'Add',
            update: 'Update',
            filter: 'Filter',
            clearFilters: 'Clear filters',
            financialReport: 'Financial report',
            reportDesc: 'A clear view of asset values, earnings, currency exposure and risk.',
            baseCurrency: 'Reporting currency',
            export: 'Export',
            print: 'Print',
            idealEarnings: 'Ideal earnings',
            degreeCompletion: 'Goal completion',
            actualEarnings: 'Actual earnings',
            priorEarnings: 'Previous earnings',
            cycleEarnings: 'Cycle earnings',
            accumulatedAmount: 'Accumulated amount',
            currencyExposure: 'Currency exposure',
            riskGuardrails: 'Risk guardrails',
            earningsSources: 'Earnings sources',
            current: 'Current',
            targetRange: 'Target range',
            inflationMonitor: 'Inflation monitor',
            inflationDesc: 'Reference inflation readings provided by your application server.',
            rate: 'Rate',
            latestReading: 'Latest reading',
            averageRate: 'Average rate',
            realValueHint: 'Real-value perspective',
            realValueHintDesc: 'Compare nominal returns with inflation to understand purchasing-power growth.',
            goalsBudgets: 'Goals & budgets',
            goalsDesc: 'Device-local planning tools. These records are never sent to the server.',
            newGoal: 'New goal',
            newBudget: 'New budget',
            localOnly: 'Stored only on this device',
            target: 'Target',
            saved: 'Saved',
            deadline: 'Deadline',
            category: 'Category',
            spent: 'Spent',
            budget: 'Budget',
            noGoals: 'No goals or budgets yet',
            noGoalsDesc: 'Create a private plan to track milestones alongside your portfolio.',
            appearance: 'Appearance',
            language: 'Language',
            privacySecurity: 'Privacy & security',
            dataPreferences: 'Data & preferences',
            dark: 'Dark',
            light: 'Light',
            privacyMode: 'Hide sensitive balances',
            privacyModeDesc: 'Blurs balances and account details across the interface.',
            autoLock: 'Automatic sign-out',
            autoLockDesc: 'Ends the local session after inactivity to reduce unattended access.',
            nineMinutes: '9 minutes',
            fiveMinutes: '5 minutes',
            disabled: 'Disabled',
            reducedMotion: 'Respect reduced motion',
            reducedMotionDesc: 'Animations already follow your operating system preference.',
            localData: 'Local planning data',
            localDataDesc: 'Goals, budgets and interface preferences stay in browser storage on this device.',
            exportLocal: 'Export local data',
            clearLocal: 'Clear local data',
            noTrackers: 'No ads or analytics',
            noTrackersDesc: 'The WebAPP contains no third-party advertising, telemetry, analytics SDKs or external fonts.',
            sessionOnly: 'Minimal session identity',
            sessionOnlyDesc: 'Display name and account stay in sessionStorage. A pending sign-in ID and email are retained until its result is resolved. Passwords are never saved.',
            logout: 'Sign out',
            logoutConfirm: 'Sign out now?',
            sessionExpired: 'Your session has expired. Please sign in again.',
            success: 'Success',
            error: 'Something went wrong',
            savedMessage: 'Changes saved successfully.',
            deletedMessage: 'Record deleted successfully.',
            addedMessage: 'Record added successfully.',
            invalidForm: 'Please complete all required fields correctly.',
            networkError: 'Unable to reach the application server.',
            retry: 'Retry',
            profile: 'Profile',
            languageEnglish: 'English',
            languageChinese: '中文',
            theme: 'Theme',
            privacy: 'Privacy',
            notifications: 'Notifications',
            noNotifications: 'No urgent notifications',
            budgetName: 'Budget name',
            goalName: 'Goal name',
            currentSaved: 'Current amount',
            targetAmount: 'Target amount',
            monthlyLimit: 'Monthly limit',
            spentAmount: 'Spent amount',
            close: 'Close',
            menu: 'Menu',
            hideBalances: 'Hide balances',
            showBalances: 'Show balances',
            secureAccess: 'Secure access',
            downloadCsv: 'Download CSV',
            downloadJson: 'Download JSON',
            reportUnavailable: 'Report data is unavailable. Add assets and try again.',
            exchangeUnavailable: 'Rate unavailable',
            offline: 'You are offline. Cached interface is available, but server data cannot be updated.',
            demandDeposit: 'Demand deposit',
            fixedDeposit: 'Fixed deposit',
            moneyMarketFund: 'Money market fund',
            stock: 'Stock',
            veryLowLow: 'Very low & low',
            mediumLowMedium: 'Medium-low & medium',
            mediumHighHigh: 'Medium-high & high',
            privacyCentre: 'Privacy centre',
            privacyCentreDesc: 'Finance Workspace is designed to minimise data exposure in the browser.',
            dataFlow: 'Data flow',
            dataFlowDesc: 'Financial records are loaded directly from your existing Java application. The WebAPP does not forward them elsewhere.',
            browserStorage: 'Browser storage',
            browserStorageDesc: 'Language, theme, privacy preferences, goals and budgets are stored locally. Clear them anytime.',
            serverSession: 'Server session',
            serverSessionDesc: 'Authentication uses the existing server session and expires automatically.',
            understand: 'Got it',
            noMatching: 'No matching records',
            noMatchingDesc: 'Adjust your search or filters to see more results.',
            reportScore: 'Financial health score',
            healthy: 'Healthy',
            needsAttention: 'Needs attention',
            totalAssets: 'Total assets',
            investmentCount: 'Investments',
            cashCount: 'Cash assets',
            fees: 'Fees',
            returnRate: 'Return',
            refresh: 'Refresh',
            editAsset: 'Edit asset',
            addInvestment: 'Add investment',
            addCashAsset: 'Add cash asset',
            editTransaction: 'Edit transaction',
            addIncomeExpense: 'Add income or expense',
            addInflationRate: 'Add inflation rate',
            editInflationRate: 'Edit inflation rate',
            selectedCurrency: 'Selected currency',
            registrationClosed: 'Public registration is unavailable',
            registrationClosedDesc: 'Finance Workspace does not currently support self-service registration. Access is provided manually for this personal, non-commercial learning project. To request access, contact the developer:',
            privacyPolicy: 'Privacy Policy',
            privacyPolicyDesc: 'Read how Finance Workspace handles personal data under UK and European data protection law.',
            january: 'January',
            february: 'February',
            march: 'March',
            april: 'April',
            may: 'May',
            june: 'June',
            july: 'July',
            august: 'August',
            september: 'September',
            october: 'October',
            november: 'November',
            december: 'December'
        },
        zh: {
            appName: '财务管理工作台',
            tagline: '投资与财务管理',
            privateWorkspace: '私人财富工作台',
            authEyebrow: '让每一个财务决策更清晰',
            authTitle: '看清资金，规划<span>未来。</span>',
            authDesc: '集投资、现金流、风险、目标与财务健康于一体的私密双语管理中心。',
            localFirst: '无第三方追踪',
            encryptedSession: '会话保护访问',
            bilingual: 'English + 中文',
            exchangeRate: '每日汇率',
            viewExchangeRates: '查看主流货币汇率',
            sessionSecurity: '会话安全',
            dataControl: '数据自主',
            live: '来自您的服务器',
            login: '登录',
            register: '创建账户',
            welcomeBack: '欢迎回来',
            loginIntro: '进入您的私人财务工作台。',
            createAccount: '创建账户',
            registerIntro: '开启安全的个人财务管理空间。',
            email: '电子邮箱',
            password: '密码',
            firstName: '名字',
            lastName: '姓氏',
            emailPlaceholder: 'you@example.com',
            passwordPlaceholder: '输入密码',
            firstNamePlaceholder: '请输入名字',
            lastNamePlaceholder: '请输入姓氏',
            signIn: '安全登录',
            createAccountButton: '创建账户',
            privacyNote: '登录信息只发送至本服务器。Finance Workspace 不加载广告、分析或第三方脚本。',
            weak: '较弱',
            fair: '一般',
            strong: '强',
            showPassword: '显示密码',
            hidePassword: '隐藏密码',
            overview: '总览',
            assets: '资产',
            transactions: '现金流',
            reports: '财务报告',
            inflation: '通胀',
            goals: '目标与预算',
            settings: '设置',
            workspace: '工作台',
            planning: '规划',
            account: '账户',
            protected: '隐私保护已启用',
            protectedDesc: '可一键隐藏敏感数值，本地规划数据仅保存在此设备。',
            goodMorning: '早上好',
            goodAfternoon: '下午好',
            goodEvening: '晚上好',
            financialOverview: '这是您的最新财务概览。',
            refreshed: '刚刚更新',
            totalNetWorth: '总净资产',
            monthlyIncome: '本月收入',
            monthlyExpenses: '本月支出',
            savingsRate: '储蓄率',
            acrossAssets: '全部已记录资产',
            currentMonth: '当前自然月',
            incomeMinusExpense: '收入减支出',
            portfolioPulse: '资产趋势',
            portfolioDesc: '基于已记录数据的资产价值与账户余额',
            riskAllocation: '风险分布',
            riskDesc: '按风险等级划分的流动资产',
            lowRisk: '低风险',
            mediumRisk: '中风险',
            highRisk: '高风险',
            financialGoals: '财务目标',
            insights: '智能洞察',
            quickActions: '快捷操作',
            addAsset: '添加资产',
            addTransaction: '添加收支',
            generateReport: '生成报告',
            viewAll: '查看全部',
            noData: '暂无数据',
            noDataDesc: '添加第一条记录即可解锁个性化分析。',
            riskBalanced: '风险结构较均衡',
            riskBalancedDesc: '您的资产分布在多个风险区间。',
            highRiskAlert: '高风险占比较高',
            highRiskAlertDesc: '高风险资产在当前组合中占比较大。',
            savingsHealthy: '储蓄率为正',
            savingsHealthyDesc: '当前收入高于支出。',
            savingsNegative: '现金流需要关注',
            savingsNegativeDesc: '当前支出高于收入。',
            recentActivity: '最近记录',
            name: '名称',
            type: '类型',
            platform: '平台',
            amount: '金额',
            date: '日期',
            actions: '操作',
            investmentAssets: '投资资产',
            nonInvestmentAssets: '现金与存款',
            searchAssets: '搜索资产…',
            allRisk: '全部风险等级',
            currentValue: '当前价值',
            earnings: '收益',
            currency: '货币',
            risk: '风险',
            assetType: '资产类型',
            investmentPlatform: '投资平台',
            investmentType: '投资类型',
            initialAmount: '初始投资',
            cashWithdrawal: '已提现',
            buyInAmount: '追加买入',
            amountSold: '已卖出金额',
            managementFee: '管理费',
            handlingFee: '手续费',
            realTimeAmount: '当前账户总值（含现金）',
            initialEarnings: '初始收益',
            assetName: '资产名称',
            edit: '编辑',
            delete: '删除',
            save: '保存',
            cancel: '取消',
            confirmDelete: '确定删除该记录？',
            destructiveDesc: '此操作会更新服务器记录，且无法撤销。',
            deleteAccountDesc: '这将删除该账户及其关联收支和成对转账，并调整保留的转账对方账户余额。此操作无法撤销。',
            allTransactions: '全部收支',
            income: '收入',
            expense: '支出',
            allTypes: '全部类型',
            transferIn: '内部转入',
            transferOut: '内部转出',
            internalTransfer: '内部转账',
            cashflowEntries: '收入与支出',
            internalTransfers: '内部转账',
            allCurrencies: '所有货币',
            sortAscending: '升序排列',
            sortDescending: '降序排列',
            inflationMonthHint: '每月通胀记录的日期统一为 15 日。',
            observedThrough: '截至',
            inflationSpanEdit: '此利率覆盖所显示的整个日期区间。修改会应用到整段；货币与起始月份固定。',
            inflationSpanDelete: '删除此日期区间内的全部月度记录？',
            counterpartAccount: '对应账户',
            pairedTransfer: '已配对转账',
            unpairedTransfer: '历史转账 · 待配对',
            transferDeleteHint: '此操作将撤销整笔转账，同时删除两个账户的对应分录。',
            transferReadOnly: '仅查看',
            transferLegs: '账户分录',
            transferExcluded: '自己账户之间的内部转账不计入收入和支出。',
            transferBetweenAccounts: '账户间转账',
            fromAccount: '转出账户',
            toAccount: '转入账户',
            receivedAmount: '到账金额（转入账户币种）',
            receivedAmountHint: '同币种留空；跨币种转账必须填写实际到账金额。',
            transferEditHint: '这条记录是转账的一侧。修改或删除前，请核对对应另一侧记录。',
            differentAccounts: '请选择两个不同的账户。',
            previousPage: '上一页',
            nextPage: '下一页',
            page: '页码',
            perPage: '条 / 页',
            totalsAllRecords: '汇总包含所有符合筛选条件的记录。',
            monthlyActivity: '每月收入与支出',
            monthlyActivityDesc: '按实际记录汇总，已排除内部转账。',
            investmentEarnings: '投资收益',
            otherPassiveIncome: '其他被动收入',
            nativeCurrency: '原币金额',
            reportingValue: '报表币种金额',
            currentPageAverage: '本页平均值',
            allPlatforms: '全部平台',
            searchTransactions: '搜索收支记录…',
            records: '记录数',
            month: '月份',
            day: '日期',
            year: '年份',
            add: '添加',
            update: '更新',
            filter: '筛选',
            clearFilters: '清除筛选',
            financialReport: '财务报告',
            reportDesc: '清楚查看资产价值、收益、货币敞口与风险分布。',
            baseCurrency: '报告货币',
            export: '导出',
            print: '打印',
            idealEarnings: '理想收益',
            degreeCompletion: '目标完成度',
            actualEarnings: '实际收益',
            priorEarnings: '此前收益',
            cycleEarnings: '周期收益',
            accumulatedAmount: '累计金额',
            currencyExposure: '货币敞口',
            riskGuardrails: '风险边界',
            earningsSources: '收益来源',
            current: '当前',
            targetRange: '目标区间',
            inflationMonitor: '通胀监测',
            inflationDesc: '由应用服务器提供的参考通胀数据。',
            rate: '通胀率',
            latestReading: '最新读数',
            averageRate: '平均通胀率',
            realValueHint: '实际价值视角',
            realValueHintDesc: '将名义收益与通胀对比，了解购买力是否真正增长。',
            goalsBudgets: '目标与预算',
            goalsDesc: '设备本地规划工具，数据不会发送到服务器。',
            newGoal: '新建目标',
            newBudget: '新建预算',
            localOnly: '仅保存在此设备',
            target: '目标',
            saved: '已存',
            deadline: '截止日期',
            category: '分类',
            spent: '已花费',
            budget: '预算',
            noGoals: '暂无目标或预算',
            noGoalsDesc: '创建私人计划，与投资组合同步跟踪里程碑。',
            appearance: '外观',
            language: '语言',
            privacySecurity: '隐私与安全',
            dataPreferences: '数据与偏好',
            dark: '深色',
            light: '浅色',
            privacyMode: '隐藏敏感余额',
            privacyModeDesc: '模糊显示界面中的余额与账户信息。',
            autoLock: '自动退出',
            autoLockDesc: '长时间无操作后结束本地会话，减少无人值守风险。',
            nineMinutes: '9 分钟',
            fiveMinutes: '5 分钟',
            disabled: '关闭',
            reducedMotion: '遵循减少动画设置',
            reducedMotionDesc: '动画会自动遵循操作系统的辅助功能偏好。',
            localData: '本地规划数据',
            localDataDesc: '目标、预算和界面偏好仅保存在此设备浏览器中。',
            exportLocal: '导出本地数据',
            clearLocal: '清除本地数据',
            noTrackers: '无广告与分析追踪',
            noTrackersDesc: 'WebAPP 不包含第三方广告、遥测、分析 SDK 或外部字体。',
            sessionOnly: '最小化会话身份信息',
            sessionOnlyDesc: '显示名称与账号保存在 sessionStorage；待核对登录仅保留操作标识和邮箱，结果确认后移除，绝不保存密码。',
            logout: '退出登录',
            logoutConfirm: '现在退出登录？',
            sessionExpired: '会话已过期，请重新登录。',
            success: '操作成功',
            error: '操作失败',
            savedMessage: '修改已成功保存。',
            deletedMessage: '记录已成功删除。',
            addedMessage: '记录已成功添加。',
            invalidForm: '请正确填写所有必填项。',
            networkError: '无法连接应用服务器。',
            retry: '重试',
            profile: '个人资料',
            languageEnglish: 'English',
            languageChinese: '中文',
            theme: '主题',
            privacy: '隐私',
            notifications: '通知',
            noNotifications: '暂无紧急提醒',
            budgetName: '预算名称',
            goalName: '目标名称',
            currentSaved: '当前金额',
            targetAmount: '目标金额',
            monthlyLimit: '每月限额',
            spentAmount: '已花费金额',
            close: '关闭',
            menu: '菜单',
            hideBalances: '隐藏余额',
            showBalances: '显示余额',
            secureAccess: '安全访问',
            downloadCsv: '下载 CSV',
            downloadJson: '下载 JSON',
            reportUnavailable: '报告数据不可用，请先添加资产后重试。',
            exchangeUnavailable: '汇率不可用',
            offline: '当前离线。可查看缓存界面，但无法更新服务器数据。',
            demandDeposit: '活期存款',
            fixedDeposit: '定期存款',
            moneyMarketFund: '货币市场基金',
            stock: '股票',
            veryLowLow: '极低与低风险',
            mediumLowMedium: '中低与中风险',
            mediumHighHigh: '中高与高风险',
            privacyCentre: '隐私中心',
            privacyCentreDesc: 'Finance Workspace 以减少浏览器端数据暴露为设计原则。',
            dataFlow: '数据流向',
            dataFlowDesc: '财务记录直接从现有 Java 应用加载，WebAPP 不会将其转发至其他服务。',
            browserStorage: '浏览器存储',
            browserStorageDesc: '语言、主题、隐私偏好、目标和预算存储于本地，可随时清除。',
            serverSession: '服务器会话',
            serverSessionDesc: '认证沿用现有服务器会话，并自动过期。',
            understand: '知道了',
            noMatching: '没有匹配记录',
            noMatchingDesc: '调整搜索词或筛选条件以查看更多结果。',
            reportScore: '财务健康评分',
            healthy: '健康',
            needsAttention: '需要关注',
            totalAssets: '资产总数',
            investmentCount: '投资资产',
            cashCount: '现金资产',
            fees: '费用',
            returnRate: '收益率',
            refresh: '刷新',
            editAsset: '编辑资产',
            addInvestment: '添加投资资产',
            addCashAsset: '添加现金资产',
            editTransaction: '编辑收支记录',
            addIncomeExpense: '添加收入或支出',
            addInflationRate: '添加通胀率',
            editInflationRate: '编辑通胀率',
            selectedCurrency: '当前货币',
            registrationClosed: '公众注册暂未开放',
            registrationClosedDesc: 'Finance Workspace 目前不支持主动、自助注册。本系统为个人、非商业学习项目，访问权限由开发者人工提供。如有兴趣，请通过以下任一邮箱联系开发者：',
            privacyPolicy: '隐私政策',
            privacyPolicyDesc: '查看 Finance Workspace 如何依据英国及欧洲数据保护法规处理个人数据。',
            january: '一月',
            february: '二月',
            march: '三月',
            april: '四月',
            may: '五月',
            june: '六月',
            july: '七月',
            august: '八月',
            september: '九月',
            october: '十月',
            november: '十一月',
            december: '十二月'
        }
    };

    const $ = (sel, root = document) => root.querySelector(sel);
    const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
    const t = key => I18N[state.lang][key] ?? I18N.en[key] ?? key;
    const languageSwitchLabel = () => state.lang === 'en' ? '中文' : 'English';
    const languageSwitchAria = () => state.lang === 'en' ? 'Switch to Chinese' : '切换到英语';
    const esc = value => String(value ?? '').replace(/[&<>'"]/g, c => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        "'": '&#39;',
        '"': '&quot;'
    }[c]));
    const attr = value => esc(value).replace(/`/g, '&#96;');
    const num = value => {
        const n = Number(String(value ?? '').replace(/[^0-9.-]/g, ''));
        return Number.isFinite(n) ? n : 0;
    };
    const clamp = (n, min, max) => Math.min(max, Math.max(min, n));
    const pct = value => value == null ? '—' : `${num(value).toFixed(1).replace('.0', '')}%`;
    // Monetary values stay decimal strings; do not pass them through Number/parseFloat.
    const decimalText = value => {
        const match = /^([+-]?)([0-9]+)(?:\.([0-9]{1,2}))?$/.exec(String(value ?? '').trim());
        if (!match) return null;
        const whole = match[2].replace(/^0+(?=[0-9])/, '');
        const fraction = (match[3] || '').padEnd(2, '0');
        const sign = match[1] === '-' && (whole !== '0' || fraction !== '00') ? '-' : '';
        return `${sign}${whole}.${fraction}`;
    };
    const isNegativeMoney = value => decimalText(value)?.startsWith('-') === true;
    const money = (value, currency = 'GBP') => {
        const exact = decimalText(value);
        if (exact === null) return '—';
        const sign = exact.startsWith('-') ? '−' : '';
        const [whole, fraction] = exact.replace(/^-/, '').split('.');
        const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
        const code = String(currency || '');
        const symbol = code === 'GBP' ? '£' : code === 'CNY' ? 'CN¥' : `${code} `;
        return esc(`${sign}${symbol}${grouped}.${fraction}`);
    };
    function transactionCurrency(item) {
        return item.currency || '';
    }
    const isTransfer = item => item.internalTransfer === true || ['transfer_in', 'transfer_out'].includes(item.type);
    const transactionClass = item => isTransfer(item) ? '' : item.type === 'income' ? 'amount-positive' : 'amount-negative';
    const transactionSign = item => ['income', 'transfer_in'].includes(item.type) ? '+' : '−';
    const dateText = value => {
        if (!value) return '—';
        const d = value instanceof Date ? value : new Date(value);
        return Number.isNaN(d.getTime()) ? String(value) : new Intl.DateTimeFormat(state.lang === 'zh' ? 'zh-CN' : 'en-GB', {
            year: 'numeric',
            month: 'short',
            day: 'numeric'
        }).format(d);
    };
    const initials = name => (name || 'FS').split(/\s+/).filter(Boolean).slice(0, 2).map(s => s[0]).join('').toUpperCase();
    const icon = (name, cls = '') => {
        const paths = {
            overview: '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
            assets: '<path d="M4 8.5 12 4l8 4.5v7L12 20l-8-4.5z"/><path d="m4.5 8.5 7.5 4 7.5-4M12 12.5V20"/>',
            transactions: '<path d="M7 7h11l-3-3m3 3-3 3M17 17H6l3 3m-3-3 3-3"/>',
            reports: '<path d="M4 20V10m5 10V4m6 16v-7m5 7V7"/>',
            inflation: '<path d="M4 17 10 11l4 4 6-8"/><path d="M15 7h5v5"/>',
            goals: '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="4"/><path d="M12 2v4m0 12v4m10-10h-4M6 12H2"/>',
            settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06a1.7 1.7 0 0 0-1.88-.34 1.7 1.7 0 0 0-1.03 1.56V21H10v-.08A1.7 1.7 0 0 0 9 19.37a1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.63 15 1.7 1.7 0 0 0 3.08 14H3v-4h.08A1.7 1.7 0 0 0 4.63 9a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 9 4.63 1.7 1.7 0 0 0 10 3.08V3h4v.08A1.7 1.7 0 0 0 15 4.63a1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.37 9 1.7 1.7 0 0 0 20.92 10H21v4h-.08A1.7 1.7 0 0 0 19.4 15Z"/>',
            search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
            plus: '<path d="M12 5v14M5 12h14"/>',
            eye: '<path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12Z"/><circle cx="12" cy="12" r="2.5"/>',
            eyeOff: '<path d="m3 3 18 18M10.6 6.2A10.7 10.7 0 0 1 12 6c6.5 0 10 6 10 6a16 16 0 0 1-2.3 3M6.6 6.6C3.6 8.5 2 12 2 12s3.5 6 10 6a10 10 0 0 0 4-.8M10.6 10.6a2 2 0 0 0 2.8 2.8"/>',
            moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8Z"/>',
            sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
            globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a15 15 0 0 1 0 18M12 3a15 15 0 0 0 0 18"/>',
            shield: '<path d="M12 3 20 6v5c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10V6z"/><path d="m9 12 2 2 4-4"/>',
            bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/>',
            user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
            logout: '<path d="M10 17l5-5-5-5M15 12H3M15 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4"/>',
            menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
            close: '<path d="m6 6 12 12M18 6 6 18"/>',
            chevron: '<path d="m9 18 6-6-6-6"/>',
            more: '<circle cx="5" cy="12" r="1" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1" fill="currentColor" stroke="none"/><circle cx="19" cy="12" r="1" fill="currentColor" stroke="none"/>',
            edit: '<path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4z"/>',
            trash: '<path d="M4 7h16M9 7V4h6v3m3 0-1 14H7L6 7m4 4v6m4-6v6"/>',
            download: '<path d="M12 3v12m0 0 4-4m-4 4-4-4M5 21h14"/>',
            print: '<path d="M7 9V3h10v6M7 17H5a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-2M7 14h10v7H7z"/>',
            refresh: '<path d="M20 6v5h-5M4 18v-5h5M6.1 9A7 7 0 0 1 18.5 6.5L20 11M4 13l1.5 4.5A7 7 0 0 0 17.9 15"/>',
            lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>',
            mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/>',
            key: '<circle cx="8" cy="15" r="4"/><path d="m11 12 8-8m-3 3 3 3m-6 0 3 3"/>',
            check: '<path d="m5 12 4 4L19 6"/>',
            info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10h.01"/>',
            alert: '<path d="M12 3 2 20h20z"/><path d="M12 9v4m0 3h.01"/>',
            trend: '<path d="m3 17 6-6 4 4 8-9"/><path d="M16 6h5v5"/>',
            wallet: '<path d="M4 6h14a2 2 0 0 1 2 2v11H4a2 2 0 0 1-2-2V6a3 3 0 0 1 3-3h12"/><path d="M16 12h4"/>',
            percent: '<path d="m19 5-14 14M7 5a2 2 0 1 1-4 0 2 2 0 0 1 4 0Zm14 14a2 2 0 1 1-4 0 2 2 0 0 1 4 0Z"/>',
            calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18"/>',
            copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
            database: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v7c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 12v7c0 1.7 3.6 3 8 3s8-1.3 8-3v-7"/>'
        };
        return `<svg class="icon ${cls}" viewBox="0 0 24 24" aria-hidden="true">${paths[name] || paths.info}</svg>`;
    };

    function translateType(value) {
        const map = {
            DEMAND_DEPOSIT: 'demandDeposit',
            FIXED_DEPOSIT: 'fixedDeposit',
            MONEY_MARKET_FUND: 'moneyMarketFund',
            STOCK: 'stock',
            VERY_LOW_AND_LOW_RISK_TYPE: 'veryLowLow',
            MEDIUM_LOW_AND_MEDIUM_RISK_TYPE: 'mediumLowMedium',
            MEDIUM_HIGH_AND_HIGH_RISK_TYPE: 'mediumHighHigh',
            income: 'income',
            expense: 'expense',
            transfer_in: 'transferIn',
            transfer_out: 'transferOut'
        };
        return map[value] ? t(map[value]) : String(value || '').replaceAll('_', ' ');
    }

    function riskClass(value) {
        return String(value).includes('VERY_LOW') ? 'good' : String(value).includes('MEDIUM_HIGH') ? 'bad' : 'warn';
    }

    function monthName(month) {
        const keys = ['january', 'february', 'march', 'april', 'may', 'june', 'july', 'august', 'september', 'october', 'november', 'december'];
        const idx = Math.max(1, Math.min(12, num(month))) - 1;
        return t(keys[idx]);
    }

    function greeting() {
        const h = new Date().getHours();
        return h < 12 ? t('goodMorning') : h < 18 ? t('goodAfternoon') : t('goodEvening');
    }

    function setDocumentPrefs() {
        document.documentElement.lang = state.lang === 'zh' ? 'zh-CN' : 'en';
        window.FinanceConnectionStatus?.setLanguage(state.lang);
        document.documentElement.dataset.theme = state.theme;
        document.body.classList.toggle('privacy-mask', state.privacy);
        document.querySelector('meta[name="theme-color"]')?.setAttribute('content', state.theme === 'dark' ? '#07111f' : '#eef4f8');
    }

    const mutationEndpoints = new Set(['/addInvestmentAsset', '/editInvestmentAsset', '/deleteInvestmentAsset',
        '/addNonInvestmentAsset', '/editNonInvestmentAsset', '/deleteNonInvestmentAsset',
        '/addIncomeOrExpense', '/editIncomeOrExpense', '/deleteIncomeOrExpense', '/transferBetweenAccounts',
        '/addInflationRate', '/editInflationRate', '/deleteInflationRate', '/currencySwitch', '/deleteUser']);
    const rowKey = row => window.FinanceOperations.recordKey(row);
    const snapshots = window.FinanceSnapshots;
    const snapshotMessage = () => state.lang === 'zh' ? '数据已变更或缺少已确认快照，请重新加载当前数据后再提交。' : 'The displayed data changed. Reload the current values before submitting a new request.';
    async function handleSnapshotConflict(error,generation=state.user.generation) {
        if(!error?.snapshotConflict||error.snapshotHandled)return;
        error.snapshotHandled=true;error.message=snapshotMessage();
        if(error.operationId&&generation===state.user.generation)try {
            const status=await apiRequest('/operationStatus',{json:{operationId:error.operationId}});
            await window.FinanceOperations.clearConfirmedAbsent({storage:localStorage,locks:navigator.locks,generation,operationId:error.operationId,status});
        }catch{ /* Keep the original pending identity if absence cannot be confirmed. */ }
        closeModal();invalidateFinancialData();await renderPage();
    }
    function bindSnapshotForm(id,source) {
        try { snapshots.bind(document.getElementById(id),source);return true; }
        catch(error){handleSnapshotConflict(error);toast(snapshotMessage(),'bad');return false;}
    }


    async function api(endpoint, options = {}) {
        if (endpoint === '/login') {
            const form = options.form instanceof URLSearchParams ? options.form : new URLSearchParams(options.form);
            return window.FinanceLoginOperations.run({storage: localStorage, locks: navigator.locks, crypto,
                email: form.get('emailAddress'),
                send: operationId => apiRequest('/login', {...options, operationId, authOutcome: true}),
                resolve: pending => apiRequest('/loginStatus', {json: {operationId: pending.operationId, emailAddress: pending.email}, authOutcome: true}),
                message: state.lang === 'zh' ? '上次登录结果尚待核对，请等待服务恢复后重试。' : 'The previous sign-in result needs checking. Wait for recovery, then retry.',
                failureMessage: code => code === 429
                    ? (state.lang === 'zh' ? '登录尝试过多，请五分钟后重试。' : 'Too many sign-in attempts. Try again in five minutes.')
                    : (state.lang === 'zh' ? '邮箱或密码无效。' : 'Invalid email or password.')});
        }
        if (!mutationEndpoints.has(endpoint)) return apiRequest(endpoint, endpoint === '/logOff'
            ? {...options, operationId: crypto.randomUUID()} : options);
        const expectedGeneration = state.user.generation;
        const expectedSession = state.sessionVersion;
        const requireSameSession = () => {
            if (state.sessionVersion !== expectedSession || state.user.generation !== expectedGeneration)
                throw Object.assign(new Error(t('sessionExpired')), {staleRequest: true});
        };
        const frozen = options.json !== undefined
            ? {json: JSON.parse(JSON.stringify(options.json))}
            : {form: new URLSearchParams(options.form instanceof URLSearchParams ? options.form.toString() : options.form)};
        const payload = frozen.json ?? Object.fromEntries(frozen.form.entries());
        try { return await window.FinanceOperations.run({storage: localStorage, locks: navigator.locks, crypto,
            generation: expectedGeneration, endpoint, payload,
            send: operationId => { requireSameSession(); return apiRequest(endpoint, {...frozen, operationId}); },
            resolve: operationId => { requireSameSession(); return apiRequest('/operationStatus', {json: {operationId}}); },
            changed: () => { invalidateFinancialData(); closeModal(); renderPage(); },
            message: state.lang === 'zh' ? '操作结果尚待核对，请等待服务器恢复并刷新后重试。' : 'The previous result needs checking. Wait for recovery, refresh, then retry.'});
        } catch(error) { await handleSnapshotConflict(error,expectedGeneration);throw error; }
    }

    async function apiRequest(endpoint, options = {}) {
        const sessionVersion = state.sessionVersion;
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), options.timeout || 15000);
        const init = {
            method: 'POST',
            credentials: 'same-origin',
            redirect: options.authOutcome ? 'error' : 'follow',
            cache: 'no-store',
            signal: controller.signal,
            headers: {'Accept': 'application/json'}
        };
        if (state.user.generation) init.headers['X-IAFM-User-Generation'] = state.user.generation;
        if (options.operationId) init.headers['X-IAFM-Operation-Id'] = options.operationId;
        if (options.json !== undefined) {
            init.headers['Content-Type'] = 'application/json; charset=UTF-8';
            init.body = JSON.stringify(options.json);
        } else if (options.form !== undefined) {
            init.headers['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
            init.body = options.form instanceof URLSearchParams ? options.form : new URLSearchParams(options.form);
        }
        try {
            const response = await fetch(API_BASE + endpoint, init);
            if (response.status === 503) window.FinanceConnectionStatus?.reportUnavailable();
            const contentType = response.headers.get('content-type') || '';
            if (response.redirected && (response.url.includes('?message=') || response.url.endsWith(API_BASE + '/') || response.url.endsWith(API_BASE))) throw Object.assign(new Error(t('sessionExpired')), {sessionExpired: true});
            const data = contentType.includes('json') ? await response.json() : null;
            if (sessionVersion !== state.sessionVersion) throw Object.assign(new Error(t('sessionExpired')), {staleRequest: true});
            if (options.authOutcome && [200, 401, 409, 429].includes(response.status)) {
                if (!data) throw new Error(t('networkError'));
                return {httpStatus: response.status, body: data};
            }
            if(data?.reloadRequired===true&&['SNAPSHOT_REQUIRED','SNAPSHOT_INVALID','SNAPSHOT_STALE'].includes(data.code))
                throw Object.assign(new Error(snapshotMessage()),{snapshotConflict:true,operationId:data.operationId});
            if (response.status === 401) throw Object.assign(new Error(data?.message || t('sessionExpired')), {sessionExpired: true});
            if (!response.ok) throw new Error(data?.message || `${t('networkError')} (${response.status})`);
            if (!data) throw new Error(t('networkError'));
            if (endpoint !== '/login' && state.user.generation && data.userGeneration && state.user.generation !== data.userGeneration)
                throw Object.assign(new Error(t('sessionExpired')), {sessionExpired: true});
            if (data.status === 'failure' || data.status === 'error') throw new Error(data.message || t('error'));
            return data;
        } catch (error) {
            if (error.name === 'AbortError' || error instanceof TypeError) window.FinanceConnectionStatus?.reportUnavailable();
            if (error.name === 'AbortError') throw new Error(t('networkError'));
            if (error.sessionExpired) {
                clearSession();
                state.user = {name: '', account: ''};
                state.authTab = 'login';
                render();
                toast(t('sessionExpired'), 'bad');
            }
            throw error;
        } finally {
            clearTimeout(timer);
        }
    }

    function clearSession() {
        sessionStorage.removeItem('userName');
        sessionStorage.removeItem('userAccount');
        sessionStorage.removeItem('userGeneration');
        state.sessionVersion++;
        renderVersion++;
        pendingRequests.clear();
        state.assets = state.transactions = state.recentTransactions = state.report = state.cashflowSummary = state.inflation = state.platforms = null;
        state.assetPage = state.txPage = state.inflationPage = 1;
        state.assetSearch = state.txSearch = '';
        state.riskFilter = state.txType = state.txPlatform = 'all';
        state.txSection = 'cashflow';
        state.inflationCurrency = 'all';
    }

    function toast(message, type = 'good', title = type === 'good' ? t('success') : t('error')) {
        const region = $('#toastRegion');
        if (!region) return;
        const el = document.createElement('div');
        el.className = `toast ${type}`;
        el.innerHTML = `${icon(type === 'good' ? 'check' : 'alert')}<div><strong>${esc(title)}</strong><p>${esc(message)}</p></div>`;
        region.appendChild(el);
        setTimeout(() => {
            el.style.opacity = '0';
            el.style.transform = 'translateX(15px)';
            setTimeout(() => el.remove(), 250);
        }, 4200);
    }

    function setLoading(key, on) {
        on ? state.loading.add(key) : state.loading.delete(key);
    }

    function isAuthed() {
        return Boolean(state.user.generation && (state.user.name || state.user.account));
    }

    function currentPage() {
        const p = (location.hash.replace(/^#\/?/, '') || 'overview').split('?')[0];
        return ['overview', 'assets', 'transactions', 'reports', 'inflation', 'goals', 'settings'].includes(p) ? p : 'overview';
    }

    function pageMeta(page) {
        const meta = {
            overview: ['overview', 'financialOverview'],
            assets: ['assets', 'portfolioDesc'],
            transactions: ['transactions', 'incomeMinusExpense'],
            reports: ['reports', 'reportDesc'],
            inflation: ['inflation', 'inflationDesc'],
            goals: ['goals', 'goalsDesc'],
            settings: ['settings', 'privacyCentreDesc']
        };
        return meta[page] || meta.overview;
    }

    function navigate(page) {
        state.sidebarOpen = false;
        if (location.hash !== `#/${page}`) location.hash = `#/${page}`; else {
            state.page = page;
            render();
        }
    }

    function safeFilename(name) {
        return String(name).replace(/[^a-z0-9_-]/gi, '_').toLowerCase();
    }

    function download(name, content, type = 'application/json') {
        const blob = new Blob([content], {type});
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = name;
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(a.href), 500);
    }

    function render() {
        setDocumentPrefs();
        state.page = currentPage();
        const app = $('#app');
        if (!isAuthed()) app.innerHTML = renderAuth(); else app.innerHTML = renderShell();
        bindGlobal();
        if (isAuthed()) {
            bindShell();
            renderPage();
        } else bindAuth();
        renderCookieControls();
    }

    function renderAuth() {
        return `<main class="auth-shell" id="mainContent">
      <section class="auth-showcase">
        <div class="auth-brand"><span class="brand-mark">F</span><span>${t('appName')}<small style="display:block;color:var(--muted);font-size:.67rem;font-weight:700;letter-spacing:.06em;text-transform:uppercase">${t('tagline')}</small></span></div>
        <div class="auth-copy">
          <div class="eyebrow">${t('authEyebrow')}</div>
          <h1>${t('authTitle')}</h1>
          <p>${t('authDesc')}</p>
          <div class="trust-row">
            <span class="trust-pill">${icon('shield')}${t('localFirst')}</span>
            <span class="trust-pill">${icon('lock')}${t('encryptedSession')}</span>
            <span class="trust-pill">${icon('globe')}${t('bilingual')}</span>
          </div>
        </div>
        <div class="market-strip">
          <div class="market-chip"><small>${t('exchangeRate')}</small><strong><a href="${API_BASE}/exchange-rates.html">${t('viewExchangeRates')} →</a></strong></div>
          <div class="market-chip"><small>${t('sessionSecurity')}</small><strong>${icon('shield')} 10 min server expiry</strong></div>
          <div class="market-chip"><small>${t('dataControl')}</small><strong>${icon('database')} ${t('localFirst')}</strong></div>
        </div>
      </section>
      <section class="auth-panel">
        <div class="auth-tools">
          <button class="icon-btn language-switch" id="authLang" aria-label="${languageSwitchAria()}">${icon('globe')}<span>${languageSwitchLabel()}</span></button>
          <button class="icon-btn" id="authTheme" aria-label="${t('theme')}">${icon(state.theme === 'dark' ? 'sun' : 'moon')}</button>
        </div>
        <div class="auth-form-wrap">
          <h2>${t('welcomeBack')}</h2>
          <p>${t('loginIntro')}</p>
          ${renderLoginForm()}
          <div class="auth-registration-notice">
            <strong>${icon('info')}${t('registrationClosed')}</strong>
            <p>${t('registrationClosedDesc')}</p>
            <div class="registration-contacts">
              <a href="mailto:zhjpersonal83@gmail.com">zhjpersonal83@gmail.com</a>
              <a href="mailto:zhongjiehuang197@gmail.com">zhongjiehuang197@gmail.com</a>
              <a href="mailto:zhjpersonal@outlook.com">zhjpersonal@outlook.com</a>
              <a href="mailto:2669631586@qq.com">2669631586@qq.com</a>
            </div>
          </div>
          <div class="auth-note">${icon('shield')}<span>${t('privacyNote')} <a href="${API_BASE}/privacy-policy.html">${t('privacyPolicy')}</a>.</span></div>
        </div>
      </section>
    </main>`;
    }

    function renderLoginForm() {
        return `<form id="loginForm" novalidate>
      <div class="form-grid">
        <div class="form-group full"><label class="form-label" for="emailAddress">${t('email')}</label><div class="field">${icon('mail', 'field-icon')}<input class="input has-icon" id="emailAddress" name="emailAddress" type="email" autocomplete="username" placeholder="${attr(t('emailPlaceholder'))}" required></div></div>
        <div class="form-group full"><label class="form-label" for="password">${t('password')}</label><div class="field">${icon('key', 'field-icon')}<input class="input has-icon" id="password" name="password" type="password" autocomplete="current-password" placeholder="${attr(t('passwordPlaceholder'))}" required><button type="button" class="icon-btn btn-sm field-action" data-toggle-password="password" aria-label="${t('showPassword')}">${icon('eye')}</button></div></div>
        <div class="form-group full"><button class="btn btn-primary btn-lg" type="submit" ${state.loading.has('auth') ? 'disabled' : ''}>${state.loading.has('auth') ? '<span class="loader" style="width:20px;height:20px;margin:0"></span>' : icon('lock')}${t('signIn')}</button></div>
      </div>
    </form>`;
    }

    function renderShell() {
        const [titleKey, subKey] = pageMeta(state.page);
        return `<div class="app-shell">
      ${!navigator.onLine ? `<div class="offline-banner">${t('offline')}</div>` : ''}
      <aside class="sidebar ${state.sidebarOpen ? 'open' : ''}" id="sidebar">
        <button class="sidebar-brand" data-nav="overview" style="border:0;background:transparent;text-align:left;cursor:pointer"><span class="brand-mark">F</span><span>${t('appName')}<small>${t('tagline')}</small></span></button>
        <div class="nav-label">${t('workspace')}</div>
        <nav class="nav-list" aria-label="Main navigation">
          ${navLink('overview', 'overview')}${navLink('assets', 'assets')}${navLink('transactions', 'transactions')}${navLink('reports', 'reports')}${navLink('inflation', 'inflation')}
        </nav>
        <div class="nav-label">${t('planning')}</div>
        <nav class="nav-list">${navLink('goals', 'goals')}${navLink('settings', 'settings')}</nav>
        <a class="nav-link" href="${API_BASE}/exchange-rates.html">${icon('globe')}<span>${t('exchangeRate')}</span></a>
        <div class="sidebar-spacer"></div>
        <div class="security-card"><strong>${icon('shield')}${t('protected')}</strong><p>${t('protectedDesc')}</p></div>
      </aside>
      <div class="main-wrap">
        <header class="topbar">
          <div class="topbar-left">
            <button class="icon-btn mobile-menu" id="mobileMenu" aria-label="${t('menu')}">${icon('menu')}</button>
            <div class="page-heading"><h1>${t(titleKey)}</h1><p>${t(subKey)}</p></div>
          </div>
          <div class="topbar-actions">
            <button class="icon-btn desktop-only ${state.privacy ? 'privacy-active' : ''}" id="privacyToggle" aria-label="${state.privacy ? t('showBalances') : t('hideBalances')}">${icon(state.privacy ? 'eyeOff' : 'eye')}</button>
            <button class="icon-btn desktop-only" id="themeToggle" aria-label="${t('theme')}">${icon(state.theme === 'dark' ? 'sun' : 'moon')}</button>
            <button class="icon-btn language-switch" id="languageToggle" aria-label="${languageSwitchAria()}">${icon('globe')}<span>${languageSwitchLabel()}</span></button>
            <button class="icon-btn" id="notificationToggle" aria-label="${t('notifications')}">${icon('bell')}<span class="badge-count">${notificationCount()}</span></button>
            <button class="user-chip" id="userMenu"><span class="user-avatar">${esc(initials(state.user.name))}</span><span class="user-meta"><strong>${esc(state.user.name || t('account'))}</strong><span data-sensitive="true">${esc(state.user.account)}</span></span>${icon('chevron')}</button>
          </div>
        </header>
        <main class="content" id="mainContent"></main>
      </div>
    </div>`;
    }

    function navLink(page, iconName) {
        return `<button class="nav-link ${state.page === page ? 'active' : ''}" data-nav="${page}">${icon(iconName)}<span>${t(page)}</span></button>`;
    }

    function notificationCount() {
        let count = 0;
        const s = overviewStats();
        if (s.highPct > 45) count++;
        if (s.savingsRate < 0) count++;
        if (state.report && !s.assetCount) count++;
        return count;
    }

    function bindGlobal() {
        document.onclick = event => {
            if (state.dropdown && !event.target.closest('.dropdown') && !event.target.closest('#userMenu,#notificationToggle')) closeDropdown();
        };
        window.ononline = () => render();
        window.onoffline = () => render();
    }

    function bindAuth() {
        $('#authLang')?.addEventListener('click', toggleLanguage);
        $('#authTheme')?.addEventListener('click', toggleTheme);
        $$('[data-toggle-password]').forEach(b => b.addEventListener('click', () => togglePassword(b)));
        $('#loginForm')?.addEventListener('submit', handleLogin);
    }

    function togglePassword(button) {
        const input = $(`#${button.dataset.togglePassword}`);
        if (!input) return;
        const show = input.type === 'password';
        input.type = show ? 'text' : 'password';
        button.innerHTML = icon(show ? 'eyeOff' : 'eye');
        button.setAttribute('aria-label', show ? t('hidePassword') : t('showPassword'));
    }

    async function handleLogin(e) {
        e.preventDefault();
        if (state.loading.has('auth')) return;
        const form = e.currentTarget;
        if (!form.reportValidity()) return;
        setLoading('auth', true);
        render();
        try {
            const data = await api('/login', {form: FormDataToParams(form)});
            if (data.status === 'success' || data.status === 'already_logged_in') {
                const name = data.userName || sessionStorage.getItem('userName') || data.userAccount || 'User';
                const account = data.userAccount || form.emailAddress.value;
                clearSession();
                sessionStorage.setItem('userName', name);
                sessionStorage.setItem('userAccount', account);
                sessionStorage.setItem('userGeneration', data.userGeneration);
                state.user = {name, account, generation: data.userGeneration};
                location.hash = '#/overview';
                render();
            } else throw new Error(data.message || t('error'));
        } catch (err) {
            toast(err.message, 'bad');
        } finally {
            setLoading('auth', false);
            if (!isAuthed()) render();
        }
    }

    function FormDataToParams(form) {
        return new URLSearchParams(new FormData(form));
    }

    function bindShell() {
        $$('[data-nav]').forEach(b => b.addEventListener('click', () => navigate(b.dataset.nav)));
        $('#mobileMenu')?.addEventListener('click', () => {
            state.sidebarOpen = !state.sidebarOpen;
            $('#sidebar')?.classList.toggle('open', state.sidebarOpen);
        });
        $('#privacyToggle')?.addEventListener('click', togglePrivacy);
        $('#themeToggle')?.addEventListener('click', toggleTheme);
        $('#languageToggle')?.addEventListener('click', toggleLanguage);
        $('#userMenu')?.addEventListener('click', e => {
            e.stopPropagation();
            showUserDropdown(e.currentTarget);
        });
        $('#notificationToggle')?.addEventListener('click', e => {
            e.stopPropagation();
            showNotifications(e.currentTarget);
        });
    }


    function savePreference(key, value) {
        if (cookieChoice?.preferences) localStorage.setItem(key, value);
    }

    function setCookieChoice(preferences) {
        cookieChoice = {preferences, at: Date.now()};
        localStorage.setItem(STORE.consent, JSON.stringify(cookieChoice));
        if (preferences) {
            savePreference(STORE.lang, state.lang);
            savePreference(STORE.theme, state.theme);
            savePreference(STORE.privacy, String(state.privacy));
        } else {
            [STORE.lang, STORE.theme, STORE.privacy].forEach(key => localStorage.removeItem(key));
        }
        cookiePanelOpen = false;
        syncOfflineCache();
        renderCookieControls();
    }

    function renderCookieControls() {
        const root = document.getElementById('cookieControls');
        const zh = state.lang === 'zh';
        const text = zh ? {
            settings: 'Cookie 设置', title: 'Cookie 与本地存储',
            description: '必要的会话 Cookie 用于安全登录。您可以选择是否在此设备记住语言、主题和隐私遮罩偏好，并缓存离线界面文件。我们不使用广告或分析 Cookie。',
            necessary: '必要：登录与安全（始终启用）', optional: '可选：记住界面偏好和离线界面缓存',
            planning: '您主动创建的目标和预算仍保存在本机。选择会在本机保存 180 天；您可随时在 Cookie 设置中更改。',
            all: '允许偏好', only: '仅使用必要项', policy: '隐私政策'
        } : {
            settings: 'Cookie settings', title: 'Cookies and local storage',
            description: 'A necessary session cookie enables secure sign-in. Choose whether this device remembers your language, theme and privacy mask and caches the interface for offline use. We do not use advertising or analytics cookies.',
            necessary: 'Necessary: sign-in and security (always on)', optional: 'Optional: remember interface preferences and cache the offline interface',
            planning: 'Goals and budgets you create remain on this device. Your choice is saved on this device for 180 days and can be changed in Cookie settings.',
            all: 'Allow preferences', only: 'Necessary only', policy: 'Privacy policy'
        };
        root.innerHTML = `<button type="button" class="cookie-settings-link" id="openCookieSettings">${text.settings}</button>${cookiePanelOpen ? `<section class="cookie-panel" role="region" aria-labelledby="cookieTitle"><h2 id="cookieTitle">${text.title}</h2><p>${text.description}</p><p><strong>${text.necessary}</strong><br>${text.optional}</p><p>${text.planning} <a href="${API_BASE}/privacy-policy.html">${text.policy}</a></p><div class="cookie-actions"><button type="button" class="btn btn-secondary" id="cookieNecessary">${text.only}</button><button type="button" class="btn btn-primary" id="cookieAllow">${text.all}</button></div></section>` : ''}`;
        document.getElementById('openCookieSettings').onclick = () => { cookiePanelOpen = !cookiePanelOpen; renderCookieControls(); };
        document.getElementById('cookieNecessary')?.addEventListener('click', () => setCookieChoice(false));
        document.getElementById('cookieAllow')?.addEventListener('click', () => setCookieChoice(true));
    }

    function toggleLanguage() {
        state.lang = state.lang === 'en' ? 'zh' : 'en';
        savePreference(STORE.lang, state.lang);
        render();
    }

    function toggleTheme() {
        state.theme = state.theme === 'dark' ? 'light' : 'dark';
        savePreference(STORE.theme, state.theme);
        render();
    }

    function togglePrivacy() {
        state.privacy = !state.privacy;
        savePreference(STORE.privacy, String(state.privacy));
        render();
    }

    function showUserDropdown(anchor) {
        closeDropdown();
        const r = anchor.getBoundingClientRect();
        const el = document.createElement('div');
        el.className = 'dropdown';
        el.innerHTML = `<div class="dropdown-head"><strong>${esc(state.user.name || t('account'))}</strong><p data-sensitive="true">${esc(state.user.account)}</p></div><button data-menu-settings>${icon('settings')}${t('settings')}</button><button data-menu-privacy>${icon(state.privacy ? 'eye' : 'eyeOff')}${state.privacy ? t('showBalances') : t('hideBalances')}</button><button class="danger" data-menu-logout>${icon('logout')}${t('logout')}</button>`;
        document.body.appendChild(el);
        positionDropdown(el, r);
        state.dropdown = el;
        $('[data-menu-settings]', el).onclick = () => {
            closeDropdown();
            navigate('settings');
        };
        $('[data-menu-privacy]', el).onclick = () => {
            closeDropdown();
            togglePrivacy();
        };
        $('[data-menu-logout]', el).onclick = () => {
            closeDropdown();
            confirmLogout();
        };
    }

    function showNotifications(anchor) {
        closeDropdown();
        const r = anchor.getBoundingClientRect();
        const notes = buildInsights();
        const el = document.createElement('div');
        el.className = 'dropdown';
        el.innerHTML = `<div class="dropdown-head"><strong>${t('notifications')}</strong><p>${notes.length ? `${notes.length} ${t('insights').toLowerCase()}` : t('noNotifications')}</p></div>${notes.length ? notes.map(n => `<button>${icon(n.icon)}<span style="text-align:left"><strong style="display:block;color:var(--text)">${esc(n.title)}</strong><small style="color:var(--muted)">${esc(n.desc)}</small></span></button>`).join('') : `<button>${icon('check')}${t('noNotifications')}</button>`}`;
        document.body.appendChild(el);
        positionDropdown(el, r);
        state.dropdown = el;
    }

    function positionDropdown(el, r) {
        const w = 270;
        el.style.top = `${Math.min(innerHeight - el.offsetHeight - 12, r.bottom + 8)}px`;
        el.style.left = `${Math.max(10, Math.min(innerWidth - w - 10, r.right - w))}px`;
    }

    function closeDropdown() {
        state.dropdown?.remove();
        state.dropdown = null;
    }

    function confirmLogout() {
        showConfirm(t('logoutConfirm'), t('sessionOnlyDesc'), async () => {
            try {
                await api('/logOff');
            } catch {
            }
            clearSession();
            state.user = {name: '', account: ''};
            location.hash = '';
            render();
        }, t('logout'));
    }

    async function readData(endpoint, options = {}) {
        const dataVersion = state.dataVersion;
        const key = `${state.sessionVersion}:${dataVersion}:${endpoint}:${JSON.stringify(options)}`;
        if (!pendingRequests.has(key)) {
            const request = api(endpoint, options).then(data => {
                if (dataVersion !== state.dataVersion) throw Object.assign(new Error(t('refreshed')), {staleRequest: true});
                return snapshots.stamp(data);
            }).finally(() => pendingRequests.delete(key));
            pendingRequests.set(key, request);
        }
        return pendingRequests.get(key);
    }

    async function bootstrapData(force = false) {
        await Promise.all([loadReport(force), loadRecentTransactions(force)]);
    }

    async function renderPage() {
        const target = $('#mainContent');
        if (!target) return;
        const version = ++renderVersion;
        const page = state.page;
        const routes = {
            overview: [() => bootstrapData(), renderOverview, bindOverview],
            assets: [() => Promise.all([loadAssets(), loadReport()]), renderAssets, bindAssets],
            transactions: [() => Promise.all([loadTransactions(), loadCashflowSummary(), loadPlatforms()]), renderTransactions, bindTransactions],
            reports: [() => loadReport(), renderReports, bindReports],
            inflation: [() => loadInflation(), renderInflation, bindInflation],
            goals: [() => Promise.resolve(), renderGoals, bindGoals],
            settings: [() => Promise.resolve(), renderSettings, bindSettings]
        };
        target.innerHTML = `<div class="loading-shell"><div><div class="loader"></div><p style="color:var(--muted)">${t('refreshed')}</p></div></div>`;
        try {
            const [load, view, bind] = routes[page];
            await load();
            if (version !== renderVersion || !target.isConnected) return;
            target.innerHTML = view();
            bind();
            bindPagination();
            bindSorting();
            target.firstElementChild?.classList.add('page-enter');
        } catch (error) {
            if (version !== renderVersion || !target.isConnected || error.staleRequest) return;
            target.innerHTML = renderError(error);
            $('#retryPage')?.addEventListener('click', () => renderPage());
        }
    }

    async function loadAssets(force = false) {
        const form = {kind: state.assetTab, page: state.assetPage, pageSize: PAGE_SIZE, q: state.assetSearch, risk: state.riskFilter};
        const cacheKey = JSON.stringify(form);
        if (state.assets?.cacheKey === cacheKey && !force) return state.assets;
        const data = await readData('/viewAllCurrentAssets', {form});
        if (!Array.isArray(data.items)) throw new Error(t('networkError'));
        state.assets = {
            ...data, cacheKey,
            investments: form.kind === 'investment' ? data.items : [],
            nonInvestments: form.kind === 'nonInvestment' ? data.items : [],
            assetTypes: data.assetTypes || DEFAULTS.assetTypes,
            riskTypes: data.riskTypes || DEFAULTS.riskTypes,
            currencies: data.currencies || DEFAULTS.currencies
        };
        return state.assets;
    }

    function transactionFilters() {
        return {
            filterType: state.txType === 'all' ? [] : [state.txType],
            filterPlatform: state.txPlatform === 'all' ? [] : [state.txPlatform],
            filterMonth: [], filterDateOfMonth: [], filterYear: [], q: state.txSearch,
            section: state.txSection
        };
    }

    async function loadTransactions(force = false) {
        const filter = {...transactionFilters(), page: state.txPage, pageSize: PAGE_SIZE, sortBy: state.txSort.by, sortDirection: state.txSort.direction};
        const cacheKey = JSON.stringify(filter);
        if (state.transactions?.cacheKey === cacheKey && !force) return state.transactions;
        const data = await readData('/getIncomeAndExpenditureData', {json: filter});
        if (!Array.isArray(data.items)) throw new Error(t('networkError'));
        state.transactions = {...data, cacheKey};
        return state.transactions;
    }

    async function loadRecentTransactions(force = false) {
        if (state.recentTransactions && !force) return state.recentTransactions;
        const data = await readData('/getIncomeAndExpenditureData', {json: {page: 1, pageSize: 5}});
        if (!Array.isArray(data.items)) throw new Error(t('networkError'));
        state.recentTransactions = data.items;
        return state.recentTransactions;
    }

    async function loadCashflowSummary(force = false) {
        const filter = transactionFilters();
        const cacheKey = JSON.stringify(filter);
        if (state.cashflowSummary?.cacheKey === cacheKey && !force) return state.cashflowSummary;
        const data = await readData('/getCashflowSummary', {json: filter});
        state.cashflowSummary = {...data, cacheKey};
        return state.cashflowSummary;
    }

    async function loadReport(force = false) {
        if (state.report && !force) return state.report;
        state.report = await readData('/generatingFinancialReport', {json: {}});
        return state.report;
    }

    async function loadPlatforms(force = false) {
        if (state.platforms && !force) return state.platforms;
        const data = await readData('/getPlatforms', {json: {page: 1, pageSize: PAGE_SIZE}});
        state.platforms = data;
        return data;
    }

    async function loadInflation(force = false) {
        const filter = {page: state.inflationPage, pageSize: PAGE_SIZE, currency: state.inflationCurrency, sortBy: state.inflationSort.by, sortDirection: state.inflationSort.direction};
        const cacheKey = JSON.stringify(filter);
        if (state.inflation?.cacheKey === cacheKey && !force) return state.inflation;
        const data = await readData('/getInflationRatesData', {json: filter});
        if (!Array.isArray(data.items)) throw new Error(t('networkError'));
        state.inflation = {
            ...data, cacheKey,
            items: data.items.map(row => snapshots.derive(row,{rate: row.annualInflationRate, month: row.monthOfYear}))
        };
        return state.inflation;
    }

    function overviewStats() {
        const report = state.report || {};
        const risk = report.riskDistribution || [];
        const percentage = type => num(risk.find(row => row.riskType === type)?.percentage);
        return {
            currency: report.currencyCurrentChosen || state.cashflowSummary?.currencyCurrentChosen || 'GBP',
            netWorth: report.netWorth,
            income: report.monthlyIncome,
            expenses: report.monthlyExpense,
            savingsRate: num(report.monthlySavingsRate ?? report.savingsRate),
            lowPct: percentage('VERY_LOW_AND_LOW_RISK_TYPE'),
            mediumPct: percentage('MEDIUM_LOW_AND_MEDIUM_RISK_TYPE'),
            highPct: percentage('MEDIUM_HIGH_AND_HIGH_RISK_TYPE'),
            assetCount: num(report.assetCount),
            investmentCount: num(report.investmentCount),
            cashCount: num(report.cashCount),
            actualEarnings: report.earnings
        };
    }

    function pagination(data, kind) {
        if (!data) return '';
        const page = num(data.page) || 1;
        const pageSize = num(data.pageSize) || PAGE_SIZE;
        const count = data.items?.length || 0;
        const start = count ? (page - 1) * pageSize + 1 : 0;
        const end = count ? start + count - 1 : 0;
        return `<nav class="pagination" aria-label="${t('page')}"><span>${t('records')}: ${start}–${end} / ${num(data.total)} · ${pageSize} ${t('perPage')}</span><div class="row-actions"><button class="btn btn-secondary btn-sm" data-page-kind="${kind}" data-page="${page - 1}" ${page <= 1 ? 'disabled' : ''}>${t('previousPage')}</button><span class="page-number">${t('page')} ${page}</span><button class="btn btn-secondary btn-sm" data-page-kind="${kind}" data-page="${page + 1}" ${data.hasNext ? '' : 'disabled'}>${t('nextPage')}</button></div></nav>`;
    }

    function bindPagination() {
        $$('[data-page-kind]').forEach(button => button.addEventListener('click', () => {
            const key = {assets: 'assetPage', transactions: 'txPage', inflation: 'inflationPage'}[button.dataset.pageKind];
            if (!key) return;
            state[key] = Math.max(1, num(button.dataset.page));
            renderPage();
        }));
    }

    function sortHeading(label, kind, key) {
        const sort = kind === 'transactions' ? state.txSort : state.inflationSort;
        const active = sort.by === key;
        const next = active && sort.direction === 'asc' ? 'desc' : 'asc';
        return `<th aria-sort="${active ? (sort.direction === 'asc' ? 'ascending' : 'descending') : 'none'}"><button type="button" class="table-sort" data-sort-kind="${kind}" data-sort-key="${key}" title="${t(next === 'asc' ? 'sortAscending' : 'sortDescending')}">${esc(label)}<span aria-hidden="true">${active ? (sort.direction === 'asc' ? '↑' : '↓') : '↕'}</span></button></th>`;
    }

    function bindSorting() {
        $$('[data-sort-kind]').forEach(button => button.addEventListener('click', () => {
            const transactions = button.dataset.sortKind === 'transactions';
            const sort = transactions ? state.txSort : state.inflationSort;
            const by = button.dataset.sortKey;
            sort.direction = sort.by === by && sort.direction === 'asc' ? 'desc' : 'asc';
            sort.by = by;
            state[transactions ? 'txPage' : 'inflationPage'] = 1;
            renderPage();
        }));
    }

    function scheduleSearch(input, stateKey, pageKey) {
        clearTimeout(searchTimer);
        const value = input.value;
        searchTimer = setTimeout(async () => {
            state[stateKey] = value;
            state[pageKey] = 1;
            await renderPage();
            const replacement = document.getElementById(input.id);
            replacement?.focus();
            replacement?.setSelectionRange(value.length, value.length);
        }, 300);
    }

    function invalidateFinancialData() {
        state.dataVersion++;
        state.report = state.cashflowSummary = state.assets = state.transactions = state.recentTransactions = state.platforms = state.inflation = null;
    }

    function buildInsights() {
        const s = overviewStats(), notes = [];
        if (s.highPct > 45) notes.push({
            icon: 'alert',
            title: t('highRiskAlert'),
            desc: t('highRiskAlertDesc')
        }); else if (s.assetCount) notes.push({icon: 'shield', title: t('riskBalanced'), desc: t('riskBalancedDesc')});
        if (s.savingsRate < 0) notes.push({
            icon: 'alert',
            title: t('savingsNegative'),
            desc: t('savingsNegativeDesc')
        }); else if ([s.income, s.expenses].some(value => decimalText(value) && decimalText(value) !== '0.00')) notes.push({
            icon: 'trend',
            title: t('savingsHealthy'),
            desc: t('savingsHealthyDesc')
        });
        if (!s.assetCount) notes.push({icon: 'plus', title: t('noData'), desc: t('noDataDesc')});
        return notes;
    }

    function renderOverview() {
        const s = overviewStats();
        const goals = getGoals();
        const tx = state.recentTransactions || [];
        const notes = buildInsights();
        return `<div>
      <section class="hero-card"><div class="eyebrow">${greeting()}, ${esc((state.user.name || '').split(' ')[0] || t('account'))}</div><h2>${t('financialOverview')}</h2><p>${t('reportDesc')}</p><div class="hero-meta"><div class="hero-stat"><small>${t('totalAssets')}</small><strong>${s.assetCount}</strong></div><div class="hero-stat"><small>${t('selectedCurrency')}</small><strong>${esc(s.currency)}</strong></div><div class="hero-stat"><small>${t('refreshed')}</small><strong>${new Intl.DateTimeFormat(state.lang === 'zh' ? 'zh-CN' : 'en-GB', {
            hour: '2-digit',
            minute: '2-digit'
        }).format(new Date())}</strong></div></div></section>
      <section class="kpi-grid">
        ${kpi(t('totalNetWorth'), money(s.netWorth, s.currency), 'wallet', t('acrossAssets'), '', isNegativeMoney(s.actualEarnings) ? 'down' : 'up')}
        ${kpi(t('monthlyIncome'), money(s.income, s.currency), 'trend', t('currentMonth'), '', 'up')}
        ${kpi(t('monthlyExpenses'), money(s.expenses, s.currency), 'transactions', t('currentMonth'), '', 'down')}
        ${kpi(t('savingsRate'), pct(s.savingsRate), 'percent', t('incomeMinusExpense'), s.savingsRate >= 0 ? '+' : '', s.savingsRate >= 0 ? 'up' : 'down')}
      </section>
      <section class="dashboard-grid">
        <div class="stack">
          <article class="card card-pad"><div class="card-head"><div><h3 class="card-title">${t('monthlyActivity')}</h3><div class="card-subtitle">${t('monthlyActivityDesc')}</div></div><div class="card-tools"><button class="btn btn-ghost btn-sm" id="refreshOverview">${icon('refresh')}${t('refresh')}</button></div></div>${renderTrendChart()}</article>
          <article class="card table-card"><div class="card-head" style="padding:20px 20px 0"><div><h3 class="card-title">${t('recentActivity')}</h3><div class="card-subtitle">${t('transactions')}</div></div><button class="btn btn-ghost btn-sm" data-nav="transactions">${t('viewAll')}${icon('chevron')}</button></div>${renderRecentTransactions(tx, s.currency)}</article>
        </div>
        <div class="stack">
          <article class="card card-pad"><div class="card-head"><div><h3 class="card-title">${t('riskAllocation')}</h3><div class="card-subtitle">${t('riskDesc')}</div></div></div>${renderDonut(s)}</article>
          <article class="card card-pad"><div class="card-head"><div><h3 class="card-title">${t('financialGoals')}</h3><div class="card-subtitle">${t('localOnly')}</div></div><button class="btn btn-ghost btn-sm" data-nav="goals">${t('viewAll')}</button></div>${goals.length ? `<div class="progress-list">${goals.slice(0, 3).map(renderGoalProgress).join('')}</div>` : renderMiniEmpty()}</article>
          <article class="card card-pad"><div class="card-head"><div><h3 class="card-title">${t('insights')}</h3><div class="card-subtitle">${t('portfolioDesc')}</div></div></div><div class="insight-list">${notes.map(n => `<div class="insight ${n.icon === 'alert' ? 'warn' : 'good'}"><span class="insight-icon">${icon(n.icon)}</span><div><strong>${esc(n.title)}</strong><p>${esc(n.desc)}</p></div></div>`).join('')}</div></article>
        </div>
      </section>
      <section class="card card-pad" style="margin-top:18px"><div class="card-head"><div><h3 class="card-title">${t('quickActions')}</h3></div></div><div class="page-actions-left"><button class="btn btn-primary" id="quickAddAsset">${icon('plus')}${t('addAsset')}</button><button class="btn btn-secondary" id="quickAddTx">${icon('transactions')}${t('addTransaction')}</button><button class="btn btn-secondary" data-nav="reports">${icon('reports')}${t('generateReport')}</button></div></section>
    </div>`;
    }

    function kpi(label, value, iconName, foot, delta, deltaClass) {
        return `<article class="kpi-card"><div class="kpi-top"><span class="kpi-label">${label}</span><span class="kpi-icon">${icon(iconName)}</span></div><div class="kpi-value money" data-sensitive="true">${value}</div><div class="kpi-foot">${delta ? `<span class="delta ${deltaClass}">${delta}</span>` : ''}<span>${foot}</span></div></article>`;
    }

    function renderTrendChart() {
        const rows = state.report?.monthly || [];
        if (!rows.length) return renderEmpty(t('noData'), t('monthlyActivityDesc'));
        // Number is used only for SVG coordinates. Labels and monetary totals remain server strings.
        const max = Math.max(...rows.flatMap(row => [num(row.income), num(row.expense)]), 1);
        const point = (value, index) => [36 + index * (520 / Math.max(rows.length - 1, 1)), 210 - num(value) / max * 165];
        const series = [['income', 'var(--brand)'], ['expense', 'var(--bad)']];
        return `<div class="chart-legend"><span><i style="background:var(--brand)"></i>${t('income')}</span><span><i style="background:var(--bad)"></i>${t('expense')}</span></div><div class="chart-wrap"><svg class="chart-svg" viewBox="0 0 590 240" role="img" aria-label="${t('monthlyActivity')}"><g class="chart-grid"><line x1="36" x2="556" y1="45" y2="45"/><line x1="36" x2="556" y1="100" y2="100"/><line x1="36" x2="556" y1="155" y2="155"/><line x1="36" x2="556" y1="210" y2="210"/></g>${series.map(([key, color]) => `<polyline class="chart-line" style="stroke:${color}" points="${rows.map((row, i) => point(row[key], i).join(',')).join(' ')}"/>${rows.map((row, i) => { const [x, y] = point(row[key], i); return `<circle class="chart-dot" style="stroke:${color}" cx="${x}" cy="${y}" r="4"><title>${esc(row.month)} · ${t(key)}: ${money(row[key], overviewStats().currency)}</title></circle>`; }).join('')}`).join('')}${rows.map((row, i) => `<text class="chart-label" x="${point('0.00', i)[0]}" y="232" text-anchor="middle">${esc(String(row.month).slice(5))}</text>`).join('')}</svg></div>`;
    }

    function renderDonut(s) {
        const low = clamp(s.lowPct, 0, 100), medium = clamp(s.mediumPct, 0, 100), high = clamp(s.highPct, 0, 100);
        const total = low + medium + high;
        if (!total) return renderEmpty(t('noData'), t('riskDesc'));
        const l = low / total * 100, m = medium / total * 100, h = high / total * 100;
        return `<div class="allocation-layout"><div class="donut" style="background:conic-gradient(var(--brand) 0 ${l}%,var(--brand-2) ${l}% ${l + m}%,var(--accent) ${l + m}% 100%)"><div class="donut-center"><strong>${Math.round(l + m + h)}%</strong><small>${t('totalAssets')}</small></div></div><div class="legend"><div class="legend-row"><span class="legend-dot" style="background:var(--brand)"></span><span>${t('lowRisk')}</span><strong>${pct(l)}</strong></div><div class="legend-row"><span class="legend-dot" style="background:var(--brand-2)"></span><span>${t('mediumRisk')}</span><strong>${pct(m)}</strong></div><div class="legend-row"><span class="legend-dot" style="background:var(--accent)"></span><span>${t('highRisk')}</span><strong>${pct(h)}</strong></div></div></div>`;
    }

    function renderRecentTransactions(items) {
        if (!items.length) return renderEmpty(t('noData'), t('noDataDesc'));
        return `<div class="table-scroll"><table><thead><tr><th>${t('name')}</th><th>${t('type')}</th><th>${t('platform')}</th><th>${t('amount')}</th><th>${t('date')}</th></tr></thead><tbody>${items.map(x => `<tr><td><span class="cell-title">${esc(x.name)}</span></td><td>${transactionBadge(x)}</td><td>${esc(x.platform)}</td><td class="${transactionClass(x)} money" data-sensitive="true">${transactionSign(x)}${money(x.amount, transactionCurrency(x))}</td><td>${esc(transactionDate(x))}</td></tr>`).join('')}</tbody></table></div>`;
    }

    function transactionBadge(item) {
        const color = isTransfer(item) ? '' : item.type === 'income' ? 'good' : 'bad';
        return `<span class="chip ${color}">${esc(translateType(item.type))}</span>`;
    }

    function transactionDate(item) {
        return `${item.year}-${String(item.monthOfYear).padStart(2, '0')}-${String(item.dateOfMonth).padStart(2, '0')}`;
    }

    function renderMiniEmpty() {
        return `<div style="text-align:center;padding:18px;color:var(--muted)">${icon('goals', 'icon-lg')}<p>${t('noGoals')}</p></div>`;
    }

    function progressPercent(current, target) {
        const currentText = decimalText(current), targetText = decimalText(target);
        if (!currentText || !targetText) return 0;
        const currentUnits = BigInt(currentText.replace('.', ''));
        const targetUnits = BigInt(targetText.replace('.', ''));
        if (targetUnits <= 0n) return 0;
        const basisPoints = currentUnits * 10000n / targetUnits;
        return Number(basisPoints < 0n ? 0n : basisPoints > 10000n ? 10000n : basisPoints) / 100;
    }

    function renderGoalProgress(g) {
        const p = progressPercent(g.current, g.target);
        return `<div><div class="progress-item-head"><strong>${esc(g.name)}</strong><span>${pct(p)}</span></div><div class="progress-bar"><span style="--value:${p}%"></span></div></div>`;
    }

    function bindOverview() {
        $$('[data-nav]').forEach(b => b.addEventListener('click', () => navigate(b.dataset.nav)));
        $('#refreshOverview')?.addEventListener('click', async () => {
            await bootstrapData(true);
            renderPage();
            toast(t('refreshed'));
        });
        $('#quickAddAsset')?.addEventListener('click', () => {
            navigate('assets');
            setTimeout(() => showAssetModal('investment'), 80);
        });
        $('#quickAddTx')?.addEventListener('click', () => {
            navigate('transactions');
            setTimeout(() => showTransactionModal(), 80);
        });
    }

    function renderAssets() {
        const data = state.assets;
        const rows = data?.items || [];
        const summary = state.report || {};
        const currency = summary.currencyCurrentChosen;
        const filtered = Boolean(state.assetSearch || state.riskFilter !== 'all');
        return `<div>
          <div class="page-actions"><div class="page-actions-left"><div class="segmented"><button class="${state.assetTab === 'investment' ? 'active' : ''}" data-asset-tab="investment">${t('investmentAssets')} <span class="chip">${num(summary.investmentCount)}</span></button><button class="${state.assetTab === 'nonInvestment' ? 'active' : ''}" data-asset-tab="nonInvestment">${t('nonInvestmentAssets')} <span class="chip">${num(summary.cashCount)}</span></button></div></div><div class="page-actions-right"><button class="btn btn-secondary" id="refreshAssets">${icon('refresh')}${t('refresh')}</button><button class="btn btn-primary" id="addAssetButton">${icon('plus')}${t(state.assetTab === 'investment' ? 'addInvestment' : 'addCashAsset')}</button></div></div>
          <div class="summary-strip"><div class="summary-item"><small>${t('totalNetWorth')}</small><strong class="money" data-sensitive="true">${money(summary.netWorth, currency)}</strong></div><div class="summary-item"><small>${t('investmentEarnings')}</small><strong class="${isNegativeMoney(summary.investmentEarnings) ? 'amount-negative' : 'amount-positive'} money" data-sensitive="true">${money(summary.investmentEarnings, currency)}</strong></div><div class="summary-item"><small>${t('fees')}</small><strong class="money" data-sensitive="true">${money(summary.fees, currency)}</strong></div></div>
          <div class="page-actions"><div class="page-actions-left"><div class="search-box">${icon('search')}<input class="input" id="assetSearch" value="${attr(state.assetSearch)}" placeholder="${attr(t('searchAssets'))}"></div><select class="select filter-select" id="assetRiskFilter"><option value="all">${t('allRisk')}</option>${(data?.riskTypes || DEFAULTS.riskTypes).map(risk => `<option value="${attr(risk)}" ${state.riskFilter === risk ? 'selected' : ''}>${esc(translateType(risk))}</option>`).join('')}</select></div></div>
          <section class="card table-card">${rows.length ? renderAssetTable(rows, state.assetTab) : renderEmpty(t(filtered ? 'noMatching' : 'noData'), t(filtered ? 'noMatchingDesc' : 'noDataDesc'))}${pagination(data, 'assets')}</section>
        </div>`;
    }

    function renderAssetTable(list, tab) {
        if (tab === 'investment') return `<div class="table-scroll"><table><thead><tr><th>${t('investmentPlatform')}</th><th>${t('investmentType')}</th><th>${t('risk')}</th><th>${t('currentValue')}</th><th>${t('earnings')}</th><th>${t('fees')}</th><th>${t('currency')}</th><th>${t('actions')}</th></tr></thead><tbody>${list.map(x => `<tr><td><span class="cell-title">${esc(x.investmentPlatform)}</span><span class="cell-sub">#${esc(x.id)}</span></td><td>${esc(translateType(x.investmentType))}</td><td><span class="chip ${riskClass(x.riskType)}">${esc(translateType(x.riskType))}</span></td><td class="money" data-sensitive="true">${money(x.realTimeAmount, x.currency)}</td><td class="${isNegativeMoney(x.earnings) ? 'amount-negative' : 'amount-positive'} money" data-sensitive="true">${money(x.earnings, x.currency)}</td><td class="money" data-sensitive="true">${money(x.fees, x.currency)}</td><td>${esc(x.currency)}</td><td><div class="row-actions"><button class="btn btn-ghost btn-sm" data-edit-investment="${attr(rowKey(x))}">${icon('edit')}${t('edit')}</button><button class="btn btn-danger btn-sm" data-delete-investment="${attr(rowKey(x))}">${icon('trash')}${t('delete')}</button></div></td></tr>`).join('')}</tbody></table></div>`;
        return `<div class="table-scroll"><table><thead><tr><th>${t('assetName')}</th><th>${t('assetType')}</th><th>${t('risk')}</th><th>${t('amount')}</th><th>${t('currency')}</th><th>${t('actions')}</th></tr></thead><tbody>${list.map(x => `<tr><td><span class="cell-title">${esc(x.assetName)}</span><span class="cell-sub">#${esc(x.id)}</span></td><td>${esc(translateType(x.assetType))}</td><td><span class="chip ${riskClass(x.riskType)}">${esc(translateType(x.riskType))}</span></td><td class="money" data-sensitive="true">${money(x.amount, x.currency)}</td><td>${esc(x.currency)}</td><td><div class="row-actions"><button class="btn btn-ghost btn-sm" data-edit-noninvestment="${attr(rowKey(x))}">${icon('edit')}${t('edit')}</button><button class="btn btn-danger btn-sm" data-delete-noninvestment="${attr(rowKey(x))}">${icon('trash')}${t('delete')}</button></div></td></tr>`).join('')}</tbody></table></div>`;
    }

    function bindAssets() {
        $$('[data-asset-tab]').forEach(b => b.addEventListener('click', () => {
            state.assetTab = b.dataset.assetTab;
            state.assetPage = 1;
            renderPage();
        }));
        $('#assetSearch')?.addEventListener('input', e => scheduleSearch(e.target, 'assetSearch', 'assetPage'));
        $('#assetRiskFilter')?.addEventListener('change', e => {
            state.riskFilter = e.target.value;
            state.assetPage = 1;
            renderPage();
        });
        $('#addAssetButton')?.addEventListener('click', () => showAssetModal(state.assetTab));
        $('#refreshAssets')?.addEventListener('click', async () => {
            invalidateFinancialData();
            await renderPage();
            toast(t('refreshed'));
        });
        $$('[data-edit-investment]').forEach(b => b.addEventListener('click', () => showAssetModal('investment', state.assets.investments.find(x => rowKey(x) === b.dataset.editInvestment))));
        $$('[data-edit-noninvestment]').forEach(b => b.addEventListener('click', () => showAssetModal('nonInvestment', state.assets.nonInvestments.find(x => rowKey(x) === b.dataset.editNoninvestment))));
        $$('[data-delete-investment]').forEach(b => b.addEventListener('click', () => deleteAsset('investment', b.dataset.deleteInvestment)));
        $$('[data-delete-noninvestment]').forEach(b => b.addEventListener('click', () => deleteAsset('nonInvestment', b.dataset.deleteNoninvestment)));
    }

    function showAssetModal(kind, item = null) {
        const a = state.assets || {
            assetTypes: DEFAULTS.assetTypes,
            riskTypes: DEFAULTS.riskTypes,
            currencies: DEFAULTS.currencies
        };
        const isInv = kind === 'investment';
        const title = item ? t('editAsset') : (isInv ? t('addInvestment') : t('addCashAsset'));
        const body = isInv ? `<form id="assetForm" class="form-grid" novalidate>
      ${item ? `<input type="hidden" name="id" value="${attr(item.id)}">` : ''}
      ${field('investmentPlatform', t('investmentPlatform'), item?.investmentPlatform, 'text', true)}
      ${selectField('investmentType', t('investmentType'), a.assetTypes, item?.investmentType, translateType)}
      ${selectField('riskType', t('risk'), a.riskTypes, item?.riskType, translateType)}
      ${selectField('currency', t('currency'), a.currencies, item?.currency)}
      ${field('investmentAmount', t('initialAmount'), item?.investmentAmount, 'number', true)}
      ${field('realTimeAmount', t('realTimeAmount'), item?.realTimeAmount, 'number', true)}
      ${field('buyInAmount', t('buyInAmount'), item?.buyInAmount ?? 0, 'number', true)}
      ${field('amountSold', t('amountSold'), item?.amountSold ?? 0, 'number', true)}
      ${field('cashWithdrawal', t('cashWithdrawal'), item?.cashWithdrawal ?? 0, 'number', true)}
      ${field('initialEarnings', t('initialEarnings'), item?.initialEarnings ?? 0, 'number', true)}
      ${field('managementFee', t('managementFee'), item?.managementFee ?? 0, 'number', true)}
      ${field('handlingFee', t('handlingFee'), item?.handlingFee ?? 0, 'number', true)}
    </form>` : `<form id="assetForm" class="form-grid" novalidate>${item ? `<input type="hidden" name="id" value="${attr(item.id)}">` : ''}${field('assetName', t('assetName'), item?.assetName, 'text', true)}${selectField('assetType', t('assetType'), a.assetTypes, item?.assetType, translateType)}${selectField('riskType', t('risk'), a.riskTypes, item?.riskType, translateType)}${selectField('currency', t('currency'), a.currencies, item?.currency)}${field('amount', t('amount'), item?.amount, 'number', true)}</form>`;
        showModal(title, isInv ? t('investmentAssets') : t('nonInvestmentAssets'), body, `<button class="btn btn-ghost" data-close-modal>${t('cancel')}</button><button class="btn btn-primary" id="saveAsset">${icon('check')}${item ? t('update') : t('add')}</button>`);
        if (!bindSnapshotForm('assetForm', item || state.assets || state.report)) return;
        $('#saveAsset')?.addEventListener('click', () => saveAsset(kind, item));
    }

    function field(name, label, value = '', type = 'text', required = false) {
        const monetary = type === 'number' && !['year', 'rate'].includes(name);
        const inputType = monetary ? 'text' : type;
        return `<div class="form-group">
        <label class="form-label" for="${name}">${label}</label>
        <input class="input" id="${name}" name="${name}" type="${inputType}"
        ${monetary ? `inputmode="decimal" pattern="${name === 'initialEarnings' ? '-?' : ''}[0-9]+([.][0-9]{1,2})?"` : type === 'number' ? `step="${name === 'year' ? '1' : '0.01'}"` : ''}
        value="${attr(value ?? '')}" ${required ? 'required' : ''}>
    </div>`;
    }

    function selectField(name, label, options, value = '', formatter = x => x) {
        return `<div class="form-group"><label class="form-label" for="${name}">${label}</label><select class="select" id="${name}" name="${name}" required>${options.map(o => `<option value="${attr(o)}" ${String(o) === String(value) ? 'selected' : ''}>${esc(formatter(o))}</option>`).join('')}</select></div>`;
    }

    async function saveAsset(kind, item) {
        const form = $('#assetForm');
        if (!form?.reportValidity()) {
            toast(t('invalidForm'), 'bad');
            return;
        }
        const params = new URLSearchParams(new FormData(form));
        if (item) { params.set('generation', item.generation); params.set('databaseId', item.databaseId || 1); }
        const endpoint = kind === 'investment' ? (item ? '/editInvestmentAsset' : '/addInvestmentAsset') : (item ? '/editNonInvestmentAsset' : '/addNonInvestmentAsset');
        const btn = $('#saveAsset');
        if (btn) btn.disabled = true;
        try {
            snapshots.attach(params,form);
            await api(endpoint, {form: params});
            closeModal();
            invalidateFinancialData();
            await renderPage();
            toast(item ? t('savedMessage') : t('addedMessage'));
        } catch (e) {
            await handleSnapshotConflict(e);
            toast(e.message, 'bad');
            if (btn) btn.disabled = false;
        }
    }

    function deleteAsset(kind, key) {
        const items = kind === 'investment' ? state.assets?.investments : state.assets?.nonInvestments;
        const item = items?.find(row => rowKey(row) === key);
        if (!item) return;
        showConfirm(t('confirmDelete'), t('deleteAccountDesc'), async () => {
            try {
                await api(kind === 'investment' ? '/deleteInvestmentAsset' : '/deleteNonInvestmentAsset', {form: {id: item.id, databaseId: item.databaseId, generation: item.generation, expectedDataRevision:snapshots.get(item)}});
                invalidateFinancialData();
                await renderPage();
                toast(t('deletedMessage'));
            } catch (e) {
            await handleSnapshotConflict(e);
                toast(e.message, 'bad');
            }
        });
    }

    function renderTransactions() {
        const data = state.transactions;
        const items = data?.items || [];
        const summary = state.cashflowSummary || {};
        const currency = summary.currencyCurrentChosen || overviewStats().currency;
        const filtered = Boolean(state.txSearch || state.txType !== 'all' || state.txPlatform !== 'all');
        const transfers = state.txSection === 'transfers';
        const types = transfers ? ['transfer_in', 'transfer_out'] : ['income', 'expense'];
        const headings = [['type', 'type'], ['platform', 'platform'], ['name', 'name'], ['date', 'date'], ['amount', 'amount']];
        return `<div>
          <div class="segmented ledger-tabs" aria-label="${t('transactions')}"><button class="${transfers ? '' : 'active'}" data-tx-section="cashflow" aria-pressed="${!transfers}">${t('cashflowEntries')}</button><button class="${transfers ? 'active' : ''}" data-tx-section="transfers" aria-pressed="${transfers}">${t('internalTransfers')}</button></div>
          <div class="page-actions"><div class="page-actions-left"><div class="search-box">${icon('search')}<input class="input" id="txSearch" value="${attr(state.txSearch)}" placeholder="${attr(t('searchTransactions'))}"></div><select class="select filter-select" id="txType"><option value="all">${t('allTypes')}</option>${types.map(type => `<option value="${type}" ${state.txType === type ? 'selected' : ''}>${esc(translateType(type))}</option>`).join('')}</select><div class="platform-filter">${platformInput('txPlatform', state.txPlatform === 'all' ? '' : state.txPlatform, false, t('allPlatforms'))}</div></div><div class="page-actions-right"><button class="btn btn-secondary" id="refreshTx">${icon('refresh')}${t('refresh')}</button><button class="btn btn-secondary" id="transferButton">${icon('transactions')}${t('transferBetweenAccounts')}</button><button class="btn btn-primary" id="addTxButton">${icon('plus')}${t('addTransaction')}</button></div></div>
          ${transfers ? `<div class="summary-strip"><div class="summary-item"><small>${t('transferLegs')}</small><strong>${num(data?.total)}</strong></div></div>` : `<div class="summary-strip"><div class="summary-item"><small>${t('income')} · ${esc(currency)}</small><strong class="amount-positive money" data-sensitive="true">${money(summary.income, currency)}</strong></div><div class="summary-item"><small>${t('expense')} · ${esc(currency)}</small><strong class="amount-negative money" data-sensitive="true">${money(summary.expense, currency)}</strong></div><div class="summary-item"><small>${t('records')}</small><strong>${num(summary.recordCount)}</strong></div></div>`}<p class="data-note">${t('totalsAllRecords')} ${t('transferExcluded')}</p>
          <section class="card table-card">${items.length ? `<div class="table-scroll"><table><thead><tr>${headings.map(([label, key]) => sortHeading(t(label), 'transactions', key)).join('')}${transfers ? `<th>${t('counterpartAccount')}</th>` : ''}<th>${t('actions')}</th></tr></thead><tbody>${items.map(x => renderTransactionRow(x, transfers)).join('')}</tbody></table></div>` : renderEmpty(t(filtered ? 'noMatching' : 'noData'), t(filtered ? 'noMatchingDesc' : 'noDataDesc'))}${pagination(data, 'transactions')}</section>
        </div>`;
    }

    function renderTransactionRow(item, transfers) {
        const editable = !isTransfer(item) && item.canEdit !== false;
        const deletable = item.canDelete !== false && (!isTransfer(item) || item.pairedTransfer);
        const pair = transfers ? `<span class="cell-sub">${t(item.pairedTransfer ? 'pairedTransfer' : 'unpairedTransfer')}${item.journalId ? ` · #${esc(item.journalId)}` : ''}</span>` : '';
        const counterpart = transfers ? `<td>${item.counterpartPlatform ? `${esc(item.counterpartPlatform)}<span class="cell-sub money" data-sensitive="true">${money(item.counterpartAmount, item.counterpartCurrency)}</span>` : '—'}</td>` : '';
        return `<tr><td>${transactionBadge(item)}${pair}</td><td>${esc(item.platform)}</td><td><span class="cell-title">${esc(item.name)}</span><span class="cell-sub">#${esc(item.id)}</span></td><td>${esc(transactionDate(item))}</td><td class="${transactionClass(item)} money" data-sensitive="true">${transactionSign(item)}${money(item.amount, transactionCurrency(item))}</td>${counterpart}<td><div class="row-actions">${editable ? `<button class="btn btn-ghost btn-sm" data-edit-tx="${attr(rowKey(item))}">${icon('edit')}${t('edit')}</button>` : ''}${deletable ? `<button class="btn btn-danger btn-sm" data-delete-tx="${attr(rowKey(item))}">${icon('trash')}${t('delete')}</button>` : ''}${!editable && !deletable ? `<span class="cell-sub">${t('transferReadOnly')}</span>` : ''}</div></td></tr>`;
    }

    function bindTransactions() {
        $$('[data-tx-section]').forEach(button => button.addEventListener('click', () => {
            state.txSection = button.dataset.txSection;
            state.txType = 'all';
            state.txPage = 1;
            renderPage();
        }));
        $('#txSearch')?.addEventListener('input', e => scheduleSearch(e.target, 'txSearch', 'txPage'));
        $('#txType')?.addEventListener('change', e => {
            state.txType = e.target.value;
            state.txPage = 1;
            renderPage();
        });
        $('#txPlatform')?.addEventListener('change', e => {
            state.txPlatform = e.target.value.trim() || 'all';
            state.txPage = 1;
            renderPage();
        });
        bindPlatformInputs();
        $('#addTxButton')?.addEventListener('click', () => showTransactionModal());
        $('#transferButton')?.addEventListener('click', showTransferModal);
        $('#refreshTx')?.addEventListener('click', async () => {
            invalidateFinancialData();
            await renderPage();
            toast(t('refreshed'));
        });
        $$('[data-edit-tx]').forEach(b => b.addEventListener('click', () => showTransactionModal(state.transactions.items.find(x => rowKey(x) === b.dataset.editTx))));
        $$('[data-delete-tx]').forEach(b => b.addEventListener('click', () => deleteTransaction(state.transactions.items.find(x => rowKey(x) === b.dataset.deleteTx))));
    }

    function platformInput(name, value = '', required = true, placeholder = '', generation = '', revision = '') {
        const origin=state.platforms?.items?.find(item=>item.name===value);
        generation ||= origin?.generation || '';
        revision ||= origin ? snapshots.get(origin) : '';
        return `<input class="input" id="${name}" name="${name}" value="${attr(value)}" list="${name}Options" data-generation="${attr(generation)}" data-generation-name="${attr(value)}" data-snapshot-revision="${attr(revision)}" data-platform-search autocomplete="off" placeholder="${attr(placeholder)}" aria-label="${attr(placeholder || t('platform'))}" ${required ? 'required' : ''}><datalist id="${name}Options">${platformOptions(state.platforms?.items || [])}</datalist>`;
    }

    function platformOptions(items) {
        return items.map(item => `<option value="${attr(item.name)}" data-generation="${attr(item.generation)}" data-snapshot-revision="${attr(snapshots.get(item))}">${esc(item.currency)}</option>`).join('');
    }

    function capturePlatformIdentity(input) {
        if (input.dataset.generationName !== input.value) {input.dataset.generation = '';input.dataset.snapshotRevision='';}
        input.dataset.generationName = input.value;
        if (!input.dataset.generation) {
            const option = Array.from(document.getElementById(`${input.id}Options`)?.options || []).find(row => row.value === input.value);
            input.dataset.generation = option?.dataset.generation || '';
            input.dataset.snapshotRevision = option?.dataset.snapshotRevision || '';
        }
    }

    function addPlatformIdentity(values, name) {
        const input = document.getElementById(name);
        capturePlatformIdentity(input);
        snapshots.same(input.form,input.dataset.snapshotRevision);
        values.set(`${name}Generation`, input.dataset.generation || '');
    }

    function bindPlatformInputs() {
        $$('[data-platform-search]').forEach(input => {
            if (input.dataset.searchBound) return;
            input.dataset.searchBound = 'true';
            let timer;
            input.addEventListener('input', () => {
                capturePlatformIdentity(input);
                clearTimeout(timer);
                const query = input.value;
                timer = setTimeout(async () => {
                    try {
                        const data = await readData('/getPlatforms', {json: {page: 1, pageSize: PAGE_SIZE, q: query}});
                        if (input.isConnected && input.value === query) {
                            const list = document.getElementById(`${input.id}Options`);
                            if (list) list.innerHTML = platformOptions(data.items || []);
                            capturePlatformIdentity(input);
                        }
                    } catch (error) {
                        if (!error.staleRequest && input.isConnected) toast(error.message, 'bad');
                    }
                }, 250);
            });
        });
    }

    function showTransactionModal(item = null) {
        const now = new Date();
        const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
        if (item && (isTransfer(item) || item.canEdit === false)) return;
        const types = ['income', 'expense'];
        const dateFields = item
            ? `${selectField('monthOfYear', t('month'), Array.from({length: 12}, (_, i) => String(i + 1).padStart(2, '0')), String(item.monthOfYear).padStart(2, '0'), m => monthName(m))}${selectField('dateOfMonth', t('day'), Array.from({length: 31}, (_, i) => String(i + 1).padStart(2, '0')), String(item.dateOfMonth).padStart(2, '0'))}${field('year', t('year'), item.year, 'number', true)}`
            : `<div class="form-group"><label class="form-label" for="date">${t('date')}</label><input class="input" id="date" name="date" type="date" value="${today}" required></div>`;
        const body = `<form id="transactionForm" class="form-grid" novalidate>${item ? `<input type="hidden" name="id" value="${attr(item.id)}">` : ''}${selectField('type', t('type'), types, item?.type || 'income', translateType)}<div class="form-group"><label class="form-label" for="platform">${t('platform')}</label>${platformInput('platform', item?.platform || '', true, '', item?.platformGeneration || '', item ? snapshots.get(item) : '')}</div>${field('name', t('name'), item?.name, 'text', true)}${field('amount', t('amount'), item?.amount, 'number', true)}${dateFields}<p class="form-group full data-note">${item && isTransfer(item) ? t('transferEditHint') : t('transferExcluded')}</p></form>`;
        showModal(item ? t('editTransaction') : t('addIncomeExpense'), t('transactions'), body, `<button class="btn btn-ghost" data-close-modal>${t('cancel')}</button><button class="btn btn-primary" id="saveTransaction">${icon('check')}${item ? t('update') : t('add')}</button>`);
        if (!bindSnapshotForm('transactionForm', item || state.platforms || state.transactions || state.report)) return;
        bindPlatformInputs();
        $('#saveTransaction')?.addEventListener('click', () => saveTransaction(item));
    }

    function showTransferModal() {
        const now = new Date();
        const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
        const body = `<form id="transferForm" class="form-grid" novalidate><div class="form-group"><label class="form-label" for="fromPlatform">${t('fromAccount')}</label>${platformInput('fromPlatform')}</div><div class="form-group"><label class="form-label" for="toPlatform">${t('toAccount')}</label>${platformInput('toPlatform')}</div>${field('amount', t('amount'), '', 'number', true)}${field('receivedAmount', t('receivedAmount'), '', 'number')}<p class="form-group full data-note">${t('receivedAmountHint')}</p><div class="form-group"><label class="form-label" for="date">${t('date')}</label><input class="input" id="date" name="date" type="date" value="${today}" required></div>${field('name', t('name'), t('internalTransfer'))}</form>`;
        showModal(t('transferBetweenAccounts'), t('transferExcluded'), body, `<button class="btn btn-ghost" data-close-modal>${t('cancel')}</button><button class="btn btn-primary" id="saveTransfer">${icon('check')}${t('save')}</button>`);
        if (!bindSnapshotForm('transferForm', state.platforms || state.report)) return;
        bindPlatformInputs();
        $('#saveTransfer')?.addEventListener('click', saveTransfer);
    }

    async function saveTransfer() {
        const form = $('#transferForm');
        if (!form?.reportValidity()) return;
        const values = new URLSearchParams(new FormData(form));
        if (values.get('fromPlatform')?.trim() === values.get('toPlatform')?.trim()) {
            toast(t('differentAccounts'), 'bad');
            return;
        }
        if (!values.get('receivedAmount')) values.delete('receivedAmount');
        const button = $('#saveTransfer');
        button.disabled = true;
        try {
            addPlatformIdentity(values, 'fromPlatform');
            addPlatformIdentity(values, 'toPlatform');
            snapshots.attach(values,form);
            await api('/transferBetweenAccounts', {form: values});
            closeModal();
            invalidateFinancialData();
            await renderPage();
            toast(t('savedMessage'));
        } catch (error) {
            await handleSnapshotConflict(error);
            toast(error.message, 'bad');
            if (button.isConnected) button.disabled = false;
        }
    }

    async function saveTransaction(item) {
        const form = $('#transactionForm');
        if (!form?.reportValidity()) {
            toast(t('invalidForm'), 'bad');
            return;
        }
        const btn = $('#saveTransaction');
        if (btn) btn.disabled = true;
        try {
            const values = new URLSearchParams(new FormData(form));
            if (item) { values.set('generation', item.generation); values.set('databaseId', item.databaseId || 1); }
            addPlatformIdentity(values, 'platform');
            snapshots.attach(values,form);
            await api(item ? '/editIncomeOrExpense' : '/addIncomeOrExpense', {form: values});
            closeModal();
            invalidateFinancialData();
            await renderPage();
            toast(item ? t('savedMessage') : t('addedMessage'));
        } catch (e) {
            await handleSnapshotConflict(e);
            toast(e.message, 'bad');
            if (btn) btn.disabled = false;
        }
    }

    function deleteTransaction(item) {
        if (!item || item.canDelete === false) return;
        showConfirm(t('confirmDelete'), t(item.pairedTransfer ? 'transferDeleteHint' : 'destructiveDesc'), async () => {
            try {
                await api('/deleteIncomeOrExpense', {
                    form: {
                        id: item.id,
                        databaseId: item.databaseId || 1,
                        generation: item.generation,
                        expectedDataRevision:snapshots.get(item),
                        type: item.type,
                        platform: item.platform,
                        amount: item.amount
                    }
                });
                invalidateFinancialData();
                await renderPage();
                toast(t('deletedMessage'));
            } catch (e) {
            await handleSnapshotConflict(e);
                toast(e.message, 'bad');
            }
        });
    }

    function renderReports() {
        const report = state.report || {};
        const stats = overviewStats();
        const currency = stats.currency;
        const exposure = report.currencyExposure || [];
        const currencies = Array.from(new Set([...(report.currencies || DEFAULTS.currencies), ...exposure.map(row => row.currency)]));
        return `<div>
          <div class="page-actions"><div class="page-actions-left"><label class="sr-only" for="reportCurrency">${t('baseCurrency')}</label><select class="select filter-select" id="reportCurrency">${currencies.map(code => `<option value="${attr(code)}" ${code === currency ? 'selected' : ''}>${esc(code)}</option>`).join('')}</select></div><div class="page-actions-right"><button class="btn btn-secondary" id="refreshReport">${icon('refresh')}${t('refresh')}</button><button class="btn btn-secondary" id="exportReport">${icon('download')}${t('export')}</button><button class="btn btn-primary" id="printReport">${icon('print')}${t('print')}</button></div></div>
          <section class="hero-card"><div class="eyebrow">${t('financialReport')}</div><h2>${t('totalNetWorth')}: <span class="money" data-sensitive="true">${money(report.netWorth, currency)}</span></h2><p>${t('reportDesc')}</p><div class="hero-meta"><div class="hero-stat"><small>${t('baseCurrency')}</small><strong>${esc(currency)}</strong></div><div class="hero-stat"><small>${t('totalAssets')}</small><strong>${stats.assetCount}</strong></div><div class="hero-stat"><small>${t('records')}</small><strong>${num(report.recordCount)}</strong></div></div></section>
          <div class="report-grid" style="margin-top:18px">
            <article class="card card-pad"><div class="card-head"><div><h3 class="card-title">${t('financialOverview')}</h3><div class="card-subtitle">${t('selectedCurrency')}: ${esc(currency)}</div></div></div><div class="metric-list">${metric(t('actualEarnings'), money(report.earnings, currency), isNegativeMoney(report.earnings) ? 'amount-negative' : 'amount-positive')}${metric(t('fees'), money(report.fees, currency))}${metric(t('priorEarnings'), money(report.priorEarnings, currency))}${metric(t('cycleEarnings'), money(report.cycleEarnings, currency))}</div></article>
            <article class="card card-pad"><div class="card-head"><div><h3 class="card-title">${t('riskAllocation')}</h3><div class="card-subtitle">${t('riskDesc')}</div></div></div>${renderDonut(stats)}</article>
            <article class="card table-card wide"><div class="card-head" style="padding:20px 20px 0"><div><h3 class="card-title">${t('currencyExposure')}</h3><div class="card-subtitle">${t('baseCurrency')}: ${esc(currency)}</div></div></div>${exposure.length ? `<div class="table-scroll"><table><thead><tr><th>${t('currency')}</th><th>${t('nativeCurrency')}</th><th>${t('reportingValue')}</th></tr></thead><tbody>${exposure.map(row => `<tr><td>${esc(row.currency)}</td><td class="money" data-sensitive="true">${money(row.amount, row.currency)}</td><td class="money" data-sensitive="true">${money(row.value, currency)}</td></tr>`).join('')}</tbody></table></div>` : renderEmpty(t('noData'), t('noDataDesc'))}</article>
            <article class="card card-pad"><div class="card-head"><div><h3 class="card-title">${t('earningsSources')}</h3><div class="card-subtitle">${t('selectedCurrency')}: ${esc(currency)}</div></div></div><div class="metric-list">${metric(t('investmentEarnings'), money(report.investmentEarnings, currency))}${metric(t('otherPassiveIncome'), money(report.otherPassiveIncome, currency))}</div></article>
            <article class="card card-pad"><div class="card-head"><div><h3 class="card-title">${t('transactions')}</h3><div class="card-subtitle">${t('transferExcluded')}</div></div></div><div class="metric-list">${metric(t('income'), money(report.income, currency), 'amount-positive')}${metric(t('expense'), money(report.expense, currency), 'amount-negative')}</div></article>
          </div>
        </div>`;
    }

    function metric(label, value, cls = '') {
        return `<div class="metric-tile"><small>${label}</small><strong class="${cls} money" data-sensitive="true">${value}</strong></div>`;
    }

    function bindReports() {
        $('#reportCurrency')?.addEventListener('change', async e => {
            const select = e.target;
            const previousCurrency = overviewStats().currency;
            select.disabled = true;
            try {
                await api('/currencySwitch', {json: {currency: select.value}});
                invalidateFinancialData();
                await loadReport(true);
                renderPage();
                toast(t('savedMessage'));
            } catch (err) {
                select.value = previousCurrency;
                toast(err.message, 'bad');
            } finally {
                if (select.isConnected) select.disabled = false;
            }
        });
        $('#refreshReport')?.addEventListener('click', async () => {
            try {
                await loadReport(true);
                renderPage();
                toast(t('refreshed'));
            } catch (e) {
                toast(e.message, 'bad');
            }
        });
        $('#printReport')?.addEventListener('click', () => window.print());
        $('#exportReport')?.addEventListener('click', () => showExportMenu());
    }

    function showExportMenu() {
        const report = state.report || {};
        showModal(t('export'), t('financialReport'), `<div class="page-actions-left"><button class="btn btn-secondary" id="downloadReportJson">${icon('download')}${t('downloadJson')}</button><button class="btn btn-secondary" id="downloadReportCsv">${icon('download')}${t('downloadCsv')}</button></div>`, `<button class="btn btn-ghost" data-close-modal>${t('close')}</button>`, 'modal-sm');
        $('#downloadReportJson')?.addEventListener('click', () => download('finance-workspace-financial-report.json', JSON.stringify(report, null, 2)));
        $('#downloadReportCsv')?.addEventListener('click', () => {
            const rows = [];
            const flatten = (value, prefix = '') => {
                if (value && typeof value === 'object') {
                    Object.entries(value).forEach(([key, child]) => flatten(child, prefix ? `${prefix}.${key}` : key));
                } else if (value != null) rows.push([prefix, String(value)]);
            };
            flatten(report);
            const csvCell = value => {
                let text = String(value);
                if (/^[\s]*[=+@-]/.test(text) && !/^-?\d+(\.\d+)?$/.test(text)) text = `'${text}`;
                return `"${text.replaceAll('"', '""')}"`;
            };
            download('finance-workspace-financial-report.csv', 'Metric,Value\n' + rows.map(row => row.map(csvCell).join(',')).join('\n'), 'text/csv');
        });
    }

    function renderInflation() {
        const data = state.inflation;
        const rows = data?.items || [];
        const canEdit = data?.canEdit === true;
        const currencies = data?.currencies || DEFAULTS.currencies;
        return `<div>
          <section class="hero-card"><div class="eyebrow">${t('inflationMonitor')}</div><h2>${t('inflationMonitor')}</h2><p>${t('inflationDesc')}</p><div class="hero-meta"><div class="hero-stat"><small>${t('records')}</small><strong>${num(data?.total)}</strong></div><div class="hero-stat"><small>${t('page')}</small><strong>${num(data?.page) || 1}</strong></div></div></section>
          <div class="page-actions" style="margin-top:18px"><div class="page-actions-left"><label for="inflationCurrency">${t('currency')}</label><select class="select filter-select" id="inflationCurrency"><option value="all">${t('allCurrencies')}</option>${currencies.map(code => `<option value="${attr(code)}" ${state.inflationCurrency === code ? 'selected' : ''}>${esc(code)}</option>`).join('')}</select></div><div class="page-actions-right"><button class="btn btn-secondary" id="refreshInflation">${icon('refresh')}${t('refresh')}</button>${canEdit ? `<button class="btn btn-primary" id="addInflationButton">${icon('plus')}${t('addInflationRate')}</button>` : ''}</div></div>
          <div class="report-grid"><article class="card table-card"><div class="card-head" style="padding:20px 20px 0"><div><h3 class="card-title">${t('inflation')}</h3><div class="card-subtitle">${t('inflationMonthHint')}</div></div></div>${rows.length ? `<div class="table-scroll"><table><thead><tr>${sortHeading(t('date'), 'inflation', 'date')}${sortHeading(t('rate'), 'inflation', 'rate')}${sortHeading(t('currency'), 'inflation', 'currency')}${canEdit ? `<th>${t('actions')}</th>` : ''}</tr></thead><tbody>${rows.map(x => `<tr><td>${inflationDateCell(x)}</td><td><span class="chip ${num(x.rate) >= 3 ? 'warn' : 'good'}">${pct(x.rate)}</span></td><td>${esc(x.currency)}</td>${canEdit ? `<td><div class="row-actions"><button class="btn btn-ghost btn-sm" data-edit-inflation="${attr(rowKey(x))}">${icon('edit')}${t('edit')}</button><button class="btn btn-danger btn-sm" data-delete-inflation="${attr(rowKey(x))}">${icon('trash')}${t('delete')}</button></div></td>` : ''}</tr>`).join('')}</tbody></table></div>` : renderEmpty(t('noData'), t('noDataDesc'))}${pagination(data, 'inflation')}</article><article class="card card-pad"><div class="card-head"><div><h3 class="card-title">${t('realValueHint')}</h3></div></div><div class="insight good"><span class="insight-icon">${icon('info')}</span><div><strong>${t('realValueHint')}</strong><p>${t('realValueHintDesc')}</p></div></div>${renderInflationBars(rows.slice(0, 12))}</article></div>
        </div>`;
    }

    function hasInflationSpan(item) {
        return Boolean(item?.date && item?.observedThrough && item.observedThrough > item.date);
    }

    function inflationDateCell(item) {
        const date = item.date || `${item.year}-${String(monthNumber(item.month)).padStart(2, '0')}-15`;
        return `${esc(date)}${hasInflationSpan(item) ? `<span class="cell-sub">${t('observedThrough')} ${esc(item.observedThrough)}</span>` : ''}`;
    }

    function monthNumber(name) {
        const names = ['january', 'february', 'march', 'april', 'may', 'june', 'july', 'august', 'september', 'october', 'november', 'december'];
        const idx = names.indexOf(String(name).toLowerCase());
        return idx >= 0 ? idx + 1 : num(name) || 1;
    }

    function renderInflationBars(rows) {
        if (!rows.length) return '';
        const max = Math.max(...rows.map(x => Math.abs(num(x.rate))), 1);
        return `<div class="progress-list" style="margin-top:18px">${rows.map(x => `<div><div class="progress-item-head"><strong>${esc(monthName(monthNumber(x.month)))} ${esc(x.year)} · ${esc(x.currency)}</strong><span>${pct(x.rate)}</span></div><div class="progress-bar"><span style="--value:${Math.abs(num(x.rate)) / max * 100}%"></span></div></div>`).join('')}</div>`;
    }

    function bindInflation() {
        $('#inflationCurrency')?.addEventListener('change', e => {
            state.inflationCurrency = e.target.value;
            state.inflationPage = 1;
            renderPage();
        });
        $('#refreshInflation')?.addEventListener('click', async () => {
            try {
                await loadInflation(true);
                renderPage();
                toast(t('refreshed'));
            } catch (e) {
                toast(e.message, 'bad');
            }
        });
        $('#addInflationButton')?.addEventListener('click', () => showInflationModal());
        $$('[data-edit-inflation]').forEach(b => b.addEventListener('click', () => showInflationModal((state.inflation?.items || []).find(x => rowKey(x) === b.dataset.editInflation))));
        $$('[data-delete-inflation]').forEach(b => b.addEventListener('click', () => deleteInflationRate((state.inflation?.items || []).find(x => rowKey(x) === b.dataset.deleteInflation))));
    }

    function showInflationModal(item = null) {
        if (state.inflation?.canEdit !== true) return;
        const rows = state.inflation?.items || [];
        const currencies = [...new Set([...(state.inflation?.currencies || DEFAULTS.currencies), ...rows.map(x => x.currency).filter(Boolean)])];
        const selectedCurrency = item?.currency || (state.inflationCurrency !== 'all' ? state.inflationCurrency : overviewStats().currency) || currencies[0];
        const span = hasInflationSpan(item);
        const currencyField = selectField('currency', t('currency'), currencies, selectedCurrency);
        const body = `<form id="inflationForm" class="form-grid" novalidate>${item ? `<input type="hidden" name="id" value="${attr(item.id)}">` : ''}<div class="form-group"><label class="form-label" for="rate">${t('rate')}</label><input class="input" id="rate" name="rate" type="number" step="0.01" value="${attr(item?.rate ?? '')}" required></div>${span ? currencyField.replace('<select ', '<select disabled ') : currencyField}<div class="form-group full"><label class="form-label" for="date">${t('date')}</label><input class="input" id="date" name="date" type="month" ${span ? 'disabled' : ''} value="${attr(item?.date?.slice(0, 7) || new Date().toISOString().slice(0, 7))}" required></div><p class="form-group full data-note">${t(span ? 'inflationSpanEdit' : 'inflationMonthHint')}${span ? ` ${esc(item.date)} – ${esc(item.observedThrough)}` : ''}</p></form>`;
        showModal(item ? t('editInflationRate') : t('addInflationRate'), t('inflationDesc'), body, `<button class="btn btn-ghost" data-close-modal>${t('cancel')}</button><button class="btn btn-primary" id="saveInflationRate">${icon('check')}${item ? t('update') : t('add')}</button>`);
        if (!bindSnapshotForm('inflationForm', item || state.inflation)) return;
        $('#saveInflationRate')?.addEventListener('click', () => saveInflationRate(item));
    }

    async function saveInflationRate(item) {
        if (state.inflation?.canEdit !== true) return;
        const form = $('#inflationForm');
        if (!form?.reportValidity()) {
            toast(t('invalidForm'), 'bad');
            return;
        }
        const btn = $('#saveInflationRate');
        if (btn) btn.disabled = true;
        const values = Object.fromEntries(new FormData(form));
        if (item) values.generation = item.generation;
        if (hasInflationSpan(item)) {
            values.date = item.date;
            values.currency = item.currency;
        } else values.date += '-15';
        try {
            snapshots.attach(values,form);
            await api(item ? '/editInflationRate' : '/addInflationRate', {form: values});
            closeModal();
            invalidateFinancialData();
            await loadInflation(true);
            renderPage();
            toast(item ? t('savedMessage') : t('addedMessage'));
        } catch (e) {
            await handleSnapshotConflict(e);
            toast(e.message, 'bad');
            if (btn) btn.disabled = false;
        }
    }

    function deleteInflationRate(item) {
        if (!item || state.inflation?.canEdit !== true) return;
        showConfirm(t('confirmDelete'), hasInflationSpan(item) ? `${t('inflationSpanDelete')} ${item.date} – ${item.observedThrough}` : t('destructiveDesc'), async () => {
            try {
                await api('/deleteInflationRate', {form: {id: item.id, generation: item.generation, expectedDataRevision:snapshots.get(item)}});
                invalidateFinancialData();
                await loadInflation(true);
                renderPage();
                toast(t('deletedMessage'));
            } catch (e) {
            await handleSnapshotConflict(e);
                toast(e.message, 'bad');
            }
        });
    }

    function getGoals() {
        try {
            const rows = JSON.parse(localStorage.getItem(planningKey(STORE.goals)) || '[]');
            return Array.isArray(rows) ? rows : [];
        } catch {
            return [];
        }
    }

    function getBudgets() {
        try {
            const rows = JSON.parse(localStorage.getItem(planningKey(STORE.budgets)) || '[]');
            return Array.isArray(rows) ? rows : [];
        } catch {
            return [];
        }
    }

    function planningKey(key) {
        // Keep device-local plans separate when another user signs in on this browser.
        return `${key}:${encodeURIComponent(state.user.account || '')}`;
    }

    function renderGoals() {
        const goals = getGoals(), budgets = getBudgets();
        return `<div>
    <div class="page-actions"><div class="page-actions-left"><span class="local-only">${icon('lock')}${t('localOnly')}</span></div><div class="page-actions-right"><button class="btn btn-secondary" id="newBudget">${icon('plus')}${t('newBudget')}</button><button class="btn btn-primary" id="newGoal">${icon('goals')}${t('newGoal')}</button></div></div>
    <section class="hero-card"><div class="eyebrow">${t('goalsBudgets')}</div><h2>${t('goalsDesc')}</h2><p>${t('localDataDesc')}</p></section>
    ${goals.length ? `<h3 style="margin:26px 0 14px">${t('financialGoals')}</h3><div class="goal-grid">${goals.map(g => renderGoalCard(g)).join('')}</div>` : ''}
    ${budgets.length ? `<h3 style="margin:26px 0 14px">${t('budget')}</h3><div class="goal-grid">${budgets.map(b => renderBudgetCard(b)).join('')}</div>` : ''}
    ${!goals.length && !budgets.length ? `<section class="card" style="margin-top:18px">${renderEmpty(t('noGoals'), t('noGoalsDesc'))}</section>` : ''}
  </div>`;
    }

    function renderGoalCard(g) {
        const p = progressPercent(g.current, g.target);
        return `<article class="goal-card"><div class="goal-card-head"><div><h3>${esc(g.name)}</h3><p>${t('deadline')}: ${esc(dateText(g.deadline))}</p></div><button class="icon-btn" data-goal-menu="${attr(g.id)}">${icon('more')}</button></div><div class="goal-amount money" data-sensitive="true">${money(g.current, g.currency || 'GBP')} <span style="color:var(--muted);font-size:.78rem">/ ${money(g.target, g.currency || 'GBP')}</span></div><div class="progress-bar"><span style="--value:${p}%"></span></div><div class="goal-meta"><span>${t('saved')}</span><strong>${pct(p)}</strong></div></article>`;
    }

    function renderBudgetCard(b) {
        const p = progressPercent(b.spent, b.limit);
        return `<article class="goal-card"><div class="goal-card-head"><div><h3>${esc(b.name)}</h3><p>${esc(b.category || t('budget'))}</p></div><button class="icon-btn" data-budget-menu="${attr(b.id)}">${icon('more')}</button></div><div class="goal-amount money" data-sensitive="true">${money(b.spent, b.currency || 'GBP')} <span style="color:var(--muted);font-size:.78rem">/ ${money(b.limit, b.currency || 'GBP')}</span></div><div class="progress-bar"><span style="--value:${p}%;background:${p > 90 ? 'var(--bad)' : 'linear-gradient(90deg,var(--brand),var(--brand-2))'}"></span></div><div class="goal-meta"><span>${t('spent')}</span><strong>${pct(p)}</strong></div></article>`;
    }

    function bindGoals() {
        $('#newGoal')?.addEventListener('click', () => showGoalModal());
        $('#newBudget')?.addEventListener('click', () => showBudgetModal());
        $$('[data-goal-menu]').forEach(b => b.addEventListener('click', () => showLocalItemMenu('goal', b.dataset.goalMenu)));
        $$('[data-budget-menu]').forEach(b => b.addEventListener('click', () => showLocalItemMenu('budget', b.dataset.budgetMenu)));
    }

    function showGoalModal(item = null) {
        const body = `<form id="goalForm" class="form-grid" novalidate>${field('goalName', t('goalName'), item?.name, 'text', true)}${field('targetAmount', t('targetAmount'), item?.target, 'number', true)}${field('currentSaved', t('currentSaved'), item?.current ?? 0, 'number', true)}${selectField('goalCurrency', t('currency'), DEFAULTS.currencies, item?.currency || overviewStats().currency)}<div class="form-group full"><label class="form-label" for="deadline">${t('deadline')}</label><input class="input" type="date" id="deadline" value="${attr(item?.deadline || '')}"></div></form>`;
        showModal(item ? t('edit') : t('newGoal'), t('localOnly'), body, `<button class="btn btn-ghost" data-close-modal>${t('cancel')}</button><button class="btn btn-primary" id="saveGoal">${icon('check')}${t('save')}</button>`);
        $('#saveGoal')?.addEventListener('click', () => saveGoal(item));
    }

    function saveGoal(item) {
        const form = $('#goalForm');
        if (!form?.reportValidity()) {
            toast(t('invalidForm'), 'bad');
            return;
        }
        const goals = getGoals();
        const data = {
            id: item?.id || cryptoRandomId(),
            name: $('#goalName').value.trim(),
            target: decimalText($('#targetAmount').value),
            current: decimalText($('#currentSaved').value),
            currency: $('#goalCurrency').value,
            deadline: $('#deadline').value
        };
        const next = item ? goals.map(g => g.id === item.id ? data : g) : [data, ...goals];
        localStorage.setItem(planningKey(STORE.goals), JSON.stringify(next));
        closeModal();
        renderPage();
        toast(t('savedMessage'));
    }

    function showBudgetModal(item = null) {
        const body = `<form id="budgetForm" class="form-grid" novalidate>${field('budgetName', t('budgetName'), item?.name, 'text', true)}${field('budgetCategory', t('category'), item?.category, 'text', true)}${field('monthlyLimit', t('monthlyLimit'), item?.limit, 'number', true)}${field('spentAmount', t('spentAmount'), item?.spent ?? 0, 'number', true)}${selectField('budgetCurrency', t('currency'), DEFAULTS.currencies, item?.currency || overviewStats().currency)}</form>`;
        showModal(item ? t('edit') : t('newBudget'), t('localOnly'), body, `<button class="btn btn-ghost" data-close-modal>${t('cancel')}</button><button class="btn btn-primary" id="saveBudget">${icon('check')}${t('save')}</button>`);
        $('#saveBudget')?.addEventListener('click', () => saveBudget(item));
    }

    function saveBudget(item) {
        const form = $('#budgetForm');
        if (!form?.reportValidity()) {
            toast(t('invalidForm'), 'bad');
            return;
        }
        const budgets = getBudgets();
        const data = {
            id: item?.id || cryptoRandomId(),
            name: $('#budgetName').value.trim(),
            category: $('#budgetCategory').value.trim(),
            limit: decimalText($('#monthlyLimit').value),
            spent: decimalText($('#spentAmount').value),
            currency: $('#budgetCurrency').value
        };
        const next = item ? budgets.map(b => b.id === item.id ? data : b) : [data, ...budgets];
        localStorage.setItem(planningKey(STORE.budgets), JSON.stringify(next));
        closeModal();
        renderPage();
        toast(t('savedMessage'));
    }

    function cryptoRandomId() {
        return globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`;
    }

    function showLocalItemMenu(kind, id) {
        const item = (kind === 'goal' ? getGoals() : getBudgets()).find(x => x.id === id);
        if (!item) return;
        showModal(item.name, t('localOnly'), `<div class="page-actions-left"><button class="btn btn-secondary" id="editLocalItem">${icon('edit')}${t('edit')}</button><button class="btn btn-danger" id="deleteLocalItem">${icon('trash')}${t('delete')}</button></div>`, `<button class="btn btn-ghost" data-close-modal>${t('close')}</button>`, 'modal-sm');
        $('#editLocalItem')?.addEventListener('click', () => {
            closeModal();
            kind === 'goal' ? showGoalModal(item) : showBudgetModal(item);
        });
        $('#deleteLocalItem')?.addEventListener('click', () => {
            const key = kind === 'goal' ? STORE.goals : STORE.budgets;
            const list = kind === 'goal' ? getGoals() : getBudgets();
            localStorage.setItem(planningKey(key), JSON.stringify(list.filter(x => x.id !== id)));
            closeModal();
            renderPage();
            toast(t('deletedMessage'));
        });
    }

    function renderSettings() {
        return `<div class="settings-grid">
    <aside class="card settings-nav"><button class="${state.settingsTab === 'privacy' ? 'active' : ''}" data-settings-tab="privacy">${t('privacySecurity')}</button><button class="${state.settingsTab === 'appearance' ? 'active' : ''}" data-settings-tab="appearance">${t('appearance')}</button><button class="${state.settingsTab === 'data' ? 'active' : ''}" data-settings-tab="data">${t('dataPreferences')}</button></aside>
    <section class="card setting-section">${state.settingsTab === 'privacy' ? renderPrivacySettings() : state.settingsTab === 'appearance' ? renderAppearanceSettings() : renderDataSettings()}</section>
  </div>`;
    }

    function renderPrivacySettings() {
        return `<div class="privacy-panel"><strong>${icon('shield')}${t('privacyCentre')}</strong><p>${t('privacyCentreDesc')}</p></div>
    <div class="setting-row"><div class="setting-copy"><strong>${t('privacyMode')}</strong><p>${t('privacyModeDesc')}</p></div><button class="switch ${state.privacy ? 'on' : ''}" id="settingsPrivacy" role="switch" aria-checked="${state.privacy}"></button></div>
    <div class="setting-row"><div class="setting-copy"><strong>${t('noTrackers')}</strong><p>${t('noTrackersDesc')}</p></div><span class="chip good">${icon('check')}${t('protected')}</span></div>
    <div class="setting-row"><div class="setting-copy"><strong>${t('sessionOnly')}</strong><p>${t('sessionOnlyDesc')}</p></div><button class="btn btn-secondary" id="privacyDetails">${icon('info')}${t('privacyCentre')}</button></div>
    <div class="setting-row"><div class="setting-copy"><strong>${t('privacyPolicy')}</strong><p>${t('privacyPolicyDesc')}</p></div><a class="btn btn-secondary" href="${API_BASE}/privacy-policy.html">${icon('shield')}${t('privacyPolicy')}</a></div>`;
    }

    function renderAppearanceSettings() {
        return `<div class="setting-row"><div class="setting-copy"><strong>${t('theme')}</strong><p>${t('appearance')}</p></div><div class="segmented"><button class="${state.theme === 'dark' ? 'active' : ''}" data-theme-choice="dark">${icon('moon')}${t('dark')}</button><button class="${state.theme === 'light' ? 'active' : ''}" data-theme-choice="light">${icon('sun')}${t('light')}</button></div></div><div class="setting-row"><div class="setting-copy"><strong>${t('language')}</strong><p>${t('bilingual')}</p></div><div class="segmented"><button class="${state.lang === 'en' ? 'active' : ''}" data-lang-choice="en">English</button><button class="${state.lang === 'zh' ? 'active' : ''}" data-lang-choice="zh">中文</button></div></div><div class="setting-row"><div class="setting-copy"><strong>${t('reducedMotion')}</strong><p>${t('reducedMotionDesc')}</p></div><span class="chip good">${icon('check')} OS</span></div>`;
    }

    function renderDataSettings() {
        return `<div class="setting-row"><div class="setting-copy"><strong>${t('localData')}</strong><p>${t('localDataDesc')}</p></div><button class="btn btn-secondary" id="exportLocalData">${icon('download')}${t('exportLocal')}</button></div><div class="setting-row"><div class="setting-copy"><strong>${t('clearLocal')}</strong><p>${t('browserStorageDesc')}</p></div><button class="btn btn-danger" id="clearLocalData">${icon('trash')}${t('clearLocal')}</button></div><div class="setting-row"><div class="setting-copy"><strong>${t('logout')}</strong><p>${t('serverSessionDesc')}</p></div><button class="btn btn-secondary" id="settingsLogout">${icon('logout')}${t('logout')}</button></div>`;
    }

    function bindSettings() {
        $$('[data-settings-tab]').forEach(b => b.addEventListener('click', () => {
            state.settingsTab = b.dataset.settingsTab;
            renderPage();
        }));
        $('#settingsPrivacy')?.addEventListener('click', togglePrivacy);
        $('#privacyDetails')?.addEventListener('click', showPrivacyCentre);
        $$('[data-theme-choice]').forEach(b => b.addEventListener('click', () => {
            state.theme = b.dataset.themeChoice;
            savePreference(STORE.theme, state.theme);
            render();
        }));
        $$('[data-lang-choice]').forEach(b => b.addEventListener('click', () => {
            state.lang = b.dataset.langChoice;
            savePreference(STORE.lang, state.lang);
            render();
        }));
        $('#exportLocalData')?.addEventListener('click', exportLocalData);
        $('#clearLocalData')?.addEventListener('click', clearLocalData);
        $('#settingsLogout')?.addEventListener('click', confirmLogout);
    }

    function showPrivacyCentre() {
        showModal(t('privacyCentre'), t('privacyCentreDesc'), `<div class="insight-list"><div class="insight good"><span class="insight-icon">${icon('database')}</span><div><strong>${t('dataFlow')}</strong><p>${t('dataFlowDesc')}</p></div></div><div class="insight good"><span class="insight-icon">${icon('lock')}</span><div><strong>${t('browserStorage')}</strong><p>${t('browserStorageDesc')}</p></div></div><div class="insight good"><span class="insight-icon">${icon('shield')}</span><div><strong>${t('serverSession')}</strong><p>${t('serverSessionDesc')}</p></div></div></div>`, `<button class="btn btn-primary" data-close-modal>${t('understand')}</button>`, 'modal-sm');
    }

    function exportLocalData() {
        const payload = {
            exportedAt: new Date().toISOString(),
            goals: getGoals(),
            budgets: getBudgets(),
            preferences: {
                language: state.lang,
                theme: state.theme,
                privacyMode: state.privacy
            }
        };
        download('finance-workspace-local-data.json', JSON.stringify(payload, null, 2));
        toast(t('success'));
    }

    function clearLocalData() {
        showConfirm(t('clearLocal'), t('browserStorageDesc'), () => {
            [STORE.goals, STORE.budgets, STORE.settings].forEach(k => localStorage.removeItem(planningKey(k)));
            renderPage();
            toast(t('deletedMessage'));
        });
    }

    function renderEmpty(title, desc, withRetry = false) {
        return `<div class="empty-state"><div><div class="empty-icon">${icon('assets', 'icon-lg')}</div><h3>${esc(title)}</h3><p>${esc(desc)}</p>${withRetry ? `<button class="btn btn-primary" id="retryPage">${icon('refresh')}${t('retry')}</button>` : ''}</div></div>`;
    }

    function renderError(error) {
        return `<div class="card">${renderEmpty(t('error'), error?.message || t('networkError'), true)}</div>`;
    }

    function showModal(title, subtitle, body, footer = '', size = '') {
        closeModal();
        const root = $('#modalRoot');
        root.innerHTML = `<div class="modal-backdrop" role="presentation"><section class="modal ${size}" role="dialog" aria-modal="true" aria-labelledby="modalTitle"><header class="modal-head"><div><h2 id="modalTitle">${esc(title)}</h2><p>${esc(subtitle || '')}</p></div><button class="icon-btn" data-close-modal aria-label="${t('close')}">${icon('close')}</button></header><div class="modal-body">${body}</div><footer class="modal-foot">${footer}</footer></section></div>`;
        // Keep form values out of navigation URLs; the existing save handlers use POST.
        $$('form', root).forEach(form => form.addEventListener('submit', event => {
            event.preventDefault();
            $('.modal-foot .btn-primary:not(:disabled)', root)?.click();
        }));
        $$('[data-close-modal]', root).forEach(b => b.addEventListener('click', closeModal));
        $('.modal-backdrop', root)?.addEventListener('click', e => {
            if (e.target.classList.contains('modal-backdrop')) closeModal();
        });
        document.addEventListener('keydown', modalKeyHandler);
        setTimeout(() => $('.modal input,.modal select,.modal button', root)?.focus(), 20);
    }

    function closeModal() {
        const root = $('#modalRoot');
        if (root) root.innerHTML = '';
        document.removeEventListener('keydown', modalKeyHandler);
    }

    function modalKeyHandler(e) {
        if (e.key === 'Escape') closeModal();
    }

    function showConfirm(title, desc, onConfirm, actionLabel = t('delete')) {
        showModal(title, desc, `<div class="insight warn"><span class="insight-icon">${icon('alert')}</span><div><strong>${esc(title)}</strong><p>${esc(desc)}</p></div></div>`, `<button class="btn btn-ghost" data-close-modal>${t('cancel')}</button><button class="btn btn-danger" id="confirmAction">${icon('check')}${esc(actionLabel)}</button>`, 'modal-sm');
        $('#confirmAction')?.addEventListener('click', async () => {
            const btn = $('#confirmAction');
            if (btn) btn.disabled = true;
            closeModal();
            await onConfirm();
        });
    }

    window.addEventListener('hashchange', () => {
        state.page = currentPage();
        if (isAuthed()) render();
    });
    window.addEventListener('keydown', e => {
        if (e.key === 'Escape') {
            closeDropdown();
            if (state.sidebarOpen) {
                state.sidebarOpen = false;
                $('#sidebar')?.classList.remove('open');
            }
        }
    });
    let offlineCacheUpdate = Promise.resolve();
    function syncOfflineCache() {
        if (!('serviceWorker' in navigator) || location.protocol === 'file:') return;
        offlineCacheUpdate = offlineCacheUpdate.catch(() => {}).then(async () => {
            if (cookieChoice?.preferences) {
                await navigator.serviceWorker.register(`${API_BASE}/sw.js`, {updateViaCache: 'none'});
            } else {
                const controller = navigator.serviceWorker.controller;
                if (controller) await new Promise(resolve => {
                    const channel = new MessageChannel();
                    const timeout = setTimeout(resolve, 300);
                    channel.port1.onmessage = () => { clearTimeout(timeout); resolve(); };
                    controller.postMessage('disable-offline-cache', [channel.port2]);
                });
                const registration = await navigator.serviceWorker.getRegistration(`${API_BASE}/`);
                const script = new URL(`${API_BASE}/sw.js`, location.origin).href;
                if (registration && [registration.active, registration.waiting, registration.installing].some(worker => worker?.scriptURL === script)) await registration.unregister();
                if ('caches' in window) {
                    const keys = await caches.keys();
                    await Promise.all(keys.filter(key => key.startsWith('finance-workspace-shell-')).map(key => caches.delete(key)));
                }
            }
        });
        offlineCacheUpdate.catch(() => {});
    }
    syncOfflineCache();

    const initialParams = new URLSearchParams(location.search);
    const urlMessage = initialParams.get('message');
    if (urlMessage) setTimeout(() => toast(urlMessage, 'bad'), 300);
    setDocumentPrefs();
    render();
})();
