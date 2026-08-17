package Services.payment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the frontend needs in order to let the student pay.
 *
 * For a hosted gateway this carries a redirectUrl. For the manual bKash flow it
 * carries the merchant number, the exact amount, and the reference the student
 * must type into the bKash reference field.
 */
public class PaymentIntent {

    private final String provider;
    private final int amountBdt;
    private final String redirectUrl;
    private final Map<String, String> instructions;

    public PaymentIntent(String provider, int amountBdt, String redirectUrl, Map<String, String> instructions) {
        this.provider = provider;
        this.amountBdt = amountBdt;
        this.redirectUrl = redirectUrl;
        this.instructions = instructions == null ? new LinkedHashMap<>() : instructions;
    }

    public String getProvider() {
        return provider;
    }

    public int getAmountBdt() {
        return amountBdt;
    }

    public String getRedirectUrl() {
        return redirectUrl;
    }

    public Map<String, String> getInstructions() {
        return instructions;
    }

    /** Shape sent to the browser. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", provider);
        out.put("amount_bdt", amountBdt);
        out.put("redirect_url", redirectUrl);
        out.put("instructions", instructions);
        return out;
    }
}
