package Model.FixedTypes;

public enum Currencies {

    USD, GBP, CNY, EUR, JPY, HKD, AUD, CAD, CHF, SGD;

    public static org.json.JSONArray supported() {
        org.json.JSONArray result = new org.json.JSONArray();
        for (Currencies currency : values()) result.put(currency.name());
        return result;
    }

}
