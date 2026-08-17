package Services.payment;

import config.Env;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manual bKash flow.
 *
 * The student sends money to the CP-STATION bKash number from their own bKash
 * app, then submits the transaction id here. An admin verifies the transaction
 * in the console, and only that verification confirms the booking. No gateway
 * credentials, no callback endpoint, no sandbox required.
 *
 * Configure in .env:
 *   BKASH_MERCHANT_NUMBER=01XXXXXXXXX
 *   BKASH_ACCOUNT_TYPE=Personal        (or Merchant)
 */
public class ManualBkashGateway implements PaymentGateway {

    @Override
    public String name() {
        return "manual";
    }

    @Override
    public PaymentIntent initiate(int bookingId, int amountBdt, String idempotencyKey) {
        String merchantNumber = Env.get("BKASH_MERCHANT_NUMBER", "01XXXXXXXXX");
        String accountType = Env.get("BKASH_ACCOUNT_TYPE", "Personal");
        String reference = "CPS-" + bookingId;

        Map<String, String> steps = new LinkedHashMap<>();
        steps.put("method", "bKash Send Money");
        steps.put("account_type", accountType);
        steps.put("merchant_number", merchantNumber);
        steps.put("amount", String.valueOf(amountBdt));
        steps.put("reference", reference);
        steps.put("note", "Send exactly BDT " + amountBdt + " to " + merchantNumber
                + " using reference " + reference + ", then submit your bKash Transaction ID below. "
                + "Your slot stays reserved until the hold expires.");

        return new PaymentIntent(name(), amountBdt, null, steps);
    }

    @Override
    public String validateSubmission(Map<String, String> submission) {
        String trxId = submission.get("trx_id");
        if (trxId == null || trxId.trim().isEmpty()) {
            return "bKash Transaction ID is required.";
        }
        trxId = trxId.trim();
        // bKash transaction ids are 10 alphanumeric characters, upper case.
        if (!trxId.matches("[A-Za-z0-9]{8,20}")) {
            return "That does not look like a bKash Transaction ID. It should be around 10 letters and digits.";
        }

        String msisdn = submission.get("payer_msisdn");
        if (msisdn != null && !msisdn.trim().isEmpty() && !msisdn.trim().matches("01[3-9][0-9]{8}")) {
            return "Sender number must be a valid 11-digit Bangladeshi mobile number.";
        }
        return null;
    }

    @Override
    public boolean requiresManualVerification() {
        return true;
    }
}
