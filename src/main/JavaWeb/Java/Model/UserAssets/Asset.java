package Model.UserAssets;

/** Base asset contract: every asset has a currency and an amount. */
public interface Asset {

    java.lang.String getCurrency();
    java.lang.String getAmount();

}
