package Services.payment;

import java.util.Map;

/**
 * Strategy Pattern Implementation: PaymentGateway
 *
 * Every payment provider (manual bKash send-money, bKash Checkout API,
 * SSLCommerz, ...) implements this contract. Handlers never talk to a provider
 * directly, they resolve one through PaymentGatewayFactory, so swapping the
 * live provider is a single configuration change.
 */
public interface PaymentGateway {

    /** Provider key persisted in payments.provider. */
    String name();

    /**
     * Starts a payment for a booking.
     *
     * @param bookingId      the booking being paid for
     * @param amountBdt      amount in whole BDT
     * @param idempotencyKey stable key so a retried initiate never double-charges
     * @return instructions for the student (manual flow) or a redirect URL (hosted flow)
     */
    PaymentIntent initiate(int bookingId, int amountBdt, String idempotencyKey);

    /**
     * Validates the reference a student submitted (or a gateway callback payload).
     *
     * @return a human-readable rejection reason, or null when the submission is acceptable
     */
    String validateSubmission(Map<String, String> submission);

    /**
     * True when a human (admin) has to confirm the money actually arrived before
     * the booking may be confirmed. The manual bKash flow requires this; a real
     * gateway callback does not.
     */
    boolean requiresManualVerification();
}
