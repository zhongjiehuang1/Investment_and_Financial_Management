# Inflation data sources and coverage

Collected on 17 October 2026 from Trading Economics' official country/region `Inflation Rate` pages. Currencies match IAFM's exchange-rate page: USD, GBP, CNY, EUR, JPY, HKD, AUD, CAD, CHF and SGD.

## Files

- `inflation_monthly_raw.csv`: 1,096 verified monthly observations for merging with an existing database.
- `inflation_change_points.csv`: 932 change points. Only consecutive calendar months with exactly equal values are collapsed; the earlier month is retained and `observed_through` records the segment's last observed month.
- `coverage.json`: source, observed range, counts, missing months and access restrictions per currency.
- `raw/visible-chart-evidence.json`: text evidence shown in the public browser charts. `history` records individually selected month/value points; recent `axis`/`points` or `tooltipRecords` provide independent consistency checks.
- `build_dataset.py`: rebuilds both CSVs and the coverage report from evidence, without network access, database writes or access to private financial records.

Run `python3 build_dataset.py` in this directory to reproduce validation and outputs.

## Observed coverage

| Currency | Region | First reference month | Last reference month | Raw rows | Change points |
| --- | --- | --- | --- | ---: | ---: |
| USD | United States | 2016-10 | 2026-09 | 119 | 108 |
| GBP | United Kingdom | 2016-09 | 2026-08 | 120 | 100 |
| CNY | China | 2016-10 | 2026-09 | 120 | 108 |
| EUR | Euro area | 2016-10 | 2026-09 | 120 | 106 |
| JPY | Japan | 2016-09 | 2026-08 | 120 | 102 |
| HKD | Hong Kong | 2016-09 | 2026-08 | 120 | 97 |
| AUD | Australia | 2025-04 | 2026-08 | 17 | 16 |
| CAD | Canada | 2016-09 | 2026-08 | 120 | 107 |
| CHF | Switzerland | 2016-10 | 2026-09 | 120 | 92 |
| SGD | Singapore | 2016-09 | 2026-08 | 120 | 96 |

The public USD chart lacked October 2025: this remains missing, without a zero or estimate. The AUD monthly series shown in the public `10Y` chart began in April 2025; quarterly year-on-year data were not substituted. Historical start dates mentioned elsewhere on the site were not treated as observed coverage.

## Collection method and limits

Open the official page, select the publicly available `10Y` chart and click each bar to read its displayed tooltip month and exact value. Recent-year visible labels and axis text were captured separately; AUD used visible tooltips. The rebuild script compares those recent observations with the ten-year capture month by month.

The UK page explicitly limited `MAX` to subscribers. The collection did not access subscription history, reuse protected endpoints or bypass restrictions. The [official API authentication guide](https://docs.tradingeconomics.com/get_started/authentication/) also requires a subscription and API key. Completeness beyond publicly captured coverage is not claimed.

## Measurement conventions

- Values are headline consumer-price **year-on-year** inflation rates. `rate=3.10` means 3.10%, not 0.031, a price index, month-on-month growth, forecast or core inflation.
- EUR uses euro-area headline HICP year-on-year (`measure=headline_hicp_yoy`); others use regional headline CPI year-on-year (`measure=headline_cpi_yoy`).
- Dates are normalized to the **15th of the reference month** as requested, not the release date. August 2026 is stored as `2026-08-15` even if released in September.
- Negative and zero values are valid. The frontend may display two decimal places.
- Collapse equal values only across adjacent calendar months. Missing months break a segment; do not merge across gaps or infer unchanged prices from absent data.
- A monthly observation of a year-on-year rate is not the actual price rise within that month. Annual inflation adjustment must use the application's established model, rather than multiplying successive year-on-year percentages as monthly returns.

## Merge guidance

Import the raw monthly table and match existing GBP/CNY history by currency and normalized reference month. Preserve earlier existing records. Within observed coverage, log differences and use the latest verified source values where appropriate. Collapse equal values across **continuous months** only after merging. Do not delete all historical GBP/CNY records before importing this limited dataset.

If the database already stores change points, adjacent stored rows are not necessarily consecutive calendar months. Use coverage/observation evidence; `observed_through` and the raw table preserve the original monthly observations.

## Official pages

- USD: https://tradingeconomics.com/united-states/inflation-cpi
- GBP: https://tradingeconomics.com/united-kingdom/inflation-cpi
- CNY: https://tradingeconomics.com/china/inflation-cpi
- EUR: https://tradingeconomics.com/euro-area/inflation-cpi
- JPY: https://tradingeconomics.com/japan/inflation-cpi
- HKD: https://tradingeconomics.com/hong-kong/inflation-cpi
- AUD: https://tradingeconomics.com/australia/inflation-cpi
- CAD: https://tradingeconomics.com/canada/inflation-cpi
- CHF: https://tradingeconomics.com/switzerland/inflation-cpi
- SGD: https://tradingeconomics.com/singapore/inflation-cpi

This directory contains no private account transactions, database passwords or server changes.
