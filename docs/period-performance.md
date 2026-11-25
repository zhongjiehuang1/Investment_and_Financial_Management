# Period performance

Sign in and open **Financial Report** to see the purchasing-power estimate. The report reads your existing accounts and transactions together with the inflation observations already stored in Finance Workspace. It calculates the estimate when the page loads or refreshes.

The report covers 12 completed months. If inflation observations for the currencies held in the latest completed month end at different times, the period ends at the earliest available cutoff among those currencies. Each account contributes from its recorded opening date through its recorded closing date or the end of the period.

Cash exposure is estimated by reversing recorded inflows and outflows from the recorded balance. Investment exposure uses cumulative contributions less withdrawals, with a floor of zero. Ledger transfers change the exposure of each participating account from the day after posting. A balance edit without a corresponding transaction can affect the estimated cash balance for earlier dates.

Each month's stored year-over-year inflation rate is prorated over the days that the estimated balance was held. The application adds the inflation allowance in each native currency and converts the result using the financial report's reference rates. This is an estimate of the inflation allowance on recorded capital. The period result is unavailable if an inflation observation required for the calculation is missing.

## Saved period reports

Previously saved reports remain available for viewing, export and deletion.

- Nominal profit is closing value less opening value and net external flows.
- The preservation target restates opening value and signed cash flows at the closing month's purchasing power using CPI index ratios.
- Money-weighted return is a dated IRR for the report period. Cash-flow series with multiple sign changes are shown as unavailable.
- Time-weighted return uses end-of-day external flows and geometrically links returns between recorded valuations. The closing value includes closing-day flows.
- Both rates are nominal and non-annualized. The ordinary cash-flow overview uses report-time reference rates and labels that basis separately.

Reference material for saved reports. [ONS UK CPI index D7BT](https://www.ons.gov.uk/economy/inflationandpriceindices/timeseries/d7bt/mm23), [BLS purchasing power methodology](https://www.bls.gov/cpi/factsheets/purchasing-power-constant-dollars.htm), [CFA Institute GIPS handbook](https://www.gipsstandards.org/standards/gips-standards-for-firms/gips-standards-handbook-for-firms/).

## Deployment

The ledger estimate uses existing asset, transaction, journal and inflation tables. For an installation upgrading from before saved period reports, provision `SAVED_PERIOD_REPORTS` in both finance schemas through the standard [migration procedure](../README.md#maintenance-and-migration). The live archive reads the primary table and removes saved reports during user deletion.
