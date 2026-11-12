package Model.Controller.InflationRatesData;

/** A safe, actionable validation/conflict message for an inflation mutation. */
public final class InflationRateException extends IllegalArgumentException {
    private final int status;
    public InflationRateException(int status, String message) {
        super(message);
        this.status = status;
    }
    public int getStatus() { return status; }
}
