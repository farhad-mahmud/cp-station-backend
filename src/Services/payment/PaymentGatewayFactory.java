package Services.payment;

import config.Env;

/**
 * Factory Pattern Implementation: PaymentGatewayFactory
 *
 * Resolves the active payment strategy from configuration. Adding bKash
 * Checkout or SSLCommerz later means writing one new PaymentGateway
 * implementation and adding one case here; no handler changes.
 *
 * Configure in .env:  PAYMENT_PROVIDER=manual
 */
public class PaymentGatewayFactory {

    private static final PaymentGateway MANUAL = new ManualBkashGateway();

    private PaymentGatewayFactory() {
    }

    public static PaymentGateway getActiveGateway() {
        String provider = Env.get("PAYMENT_PROVIDER", "manual");
        return forProvider(provider);
    }

    public static PaymentGateway forProvider(String provider) {
        if (provider == null) {
            return MANUAL;
        }
        switch (provider.trim().toLowerCase()) {
            case "manual":
            case "bkash_manual":
                return MANUAL;
            default:
                // Unknown provider configured: fall back to the flow that always works
                // rather than taking the booking endpoint down.
                System.err.println("Unknown PAYMENT_PROVIDER '" + provider + "', falling back to manual bKash.");
                return MANUAL;
        }
    }
}
