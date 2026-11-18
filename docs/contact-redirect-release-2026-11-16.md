# Contact-page redirect release — 16 November 2026

Both application hosts were updated in maintenance run `0a532cea-162e-4677-bd98-120a80177c6a`. Finance Workspace's sign-in notice links to the contact page on the personal website. Star Hub's sign-in dialog does the same. Both links select the English or Chinese contact page with the interface language.

The releases were assembled from the previously deployed WARs. Finance changed only `index.html`, `assets/js/app.js`, `sw.js` and the legacy registration page. Star Hub changed only `index.html`, `i18n.js` and its matching browser call-source copy. The other WAR entries, including server classes, were unchanged.

| Application | Previous WAR SHA-256 | New WAR SHA-256 |
| --- | --- | --- |
| Finance Workspace | `e900830af0050a533ef161e0abfa332dd6777a4b00831f275f8118ebbbbdafa8` | `27a33bc0aff1d08db40638c735c5dfc40f318e721f2734a40b7fd921a70aedac` |
| Star Hub | `626aaf38c6ba806d7ca2b1afef449140fd705eaaa13442ebd42bff06275973df` | `f21caef8b0f888358d2da00d90602945205d484ff9a5c6253d11dab00c506132` |

The Portfolio, UCost and ATLAS WARs were retained. Each host retains the previous Finance and Star Hub WARs and release receipts under `/var/lib/codex-validation/contact-release-0a532cea-162e-4677-bd98-120a80177c6a/before`; the previous expanded trees are retained under `/var/lib/codex-validation/preexpanded-war-preimages/0a532cea-162e-4677-bd98-120a80177c6a`. Server 2 also retains the previous monitor and capacity-proof files in that release's `before` directory. The monitor and proof were rebound to the new Star Hub WAR; no capacity thresholds or other proof fields changed, and no new capacity test is claimed.

Before installation, backup `3aa2642c-8ef1-4cf3-975d-371b8c40a831` was created with both application stacks stopped. Its manifest SHA-256 is `ed1c6bb64dfb511256fb6257f95bed52f1d23c8e99402100efd77b1888e3c570`. All four encrypted components were checked against the manifest and authenticated with both recovery keys. Isolated restore `da126bac-2550-44b7-81b9-2eba873fb002` passed against that exact manifest and content digest; its MySQL process exited, owned cleanup completed, and the production data-directory identity remained unchanged. This is not a blank-host rebuild or a full test of grants, routines, events and triggers.

After restart, all four local admission checks and the ATLAS route passed on both hosts. Both public HTTPS entries served exact copies of the revised Finance and Star Hub assets; both versions of the Portfolio contact page were reachable. A browser followed each revised Chinese contact link to the intended page, and the English targets were checked in the rendered interfaces and public endpoint checks. Server 2's monitor reported database, reserve and capacity-model readiness, and Server 1's MySQL remained masked. No authenticated business transaction or paid model request was performed. Existing signed-in sessions may require a new sign-in after the restart.
