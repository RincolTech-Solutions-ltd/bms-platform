package com.rincoltech.bms.lending.loans.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/** Request and response bodies of a loan renewal (issue #918, ADR-128; chapter 7 section 7.11.13). */
final class RenewalApi {

    private RenewalApi() {}

    @Schema(
            name = "LoanRenewalRequest",
            description = "Renew an active loan: what is collected now is recorded at once as a normal repayment; the"
                    + " principal still owed then moves to a new loan once a second person approves")
    record RenewalRequest(
            @NotNull @Schema(description = "Today or earlier, not before the loan's latest repayment")
            LocalDate valueDate,

            @PositiveOrZero
            @Max(LoanApi.MAX_MONEY_MINOR)
            @Schema(
                    description = "Collected now as a repayment (receipt, journal, allocation as any repayment); 0 or"
                            + " left out when the interest is already paid")
            Long collectMinor,

            @Pattern(regexp = ServicingApi.METHODS)
            @Schema(description = "Required when collect_minor or the renewal fee is above 0")
            String paymentMethodKey,

            @Size(max = 100) String externalReference,

            @Min(1) @Max(3660) @Schema(description = "The new loan's term; the old loan's when left out")
            Integer termCount,

            @Min(0)
            @Max(100000)
            @Schema(description = "The new loan's rate; the old loan's when left out, never above it")
            Integer interestRateBp,

            @Size(max = 500) String reason,

            @Schema(description = "As on a repayment: false sends no receipt text message (an administrator only)")
            Boolean notifyMember) {}

    @Schema(name = "LoanRenewalQuote", description = "What a renewal would do, worked out on the value date")
    record Quote(
            UUID loanId,
            String loanNo,
            String currency,
            LocalDate valueDate,

            @Schema(description = "Principal still owed before anything is collected")
            long principalOutstandingMinor,

            @Schema(description = "Interest due on the value date (R-PAYOFF)")
            long interestDueMinor,

            long feesDueMinor,
            long penaltiesDueMinor,

            @Schema(description = "True: the product needs the interest due collected before the principal moves")
            boolean interestFirst,

            @Schema(
                    description =
                            "The least to collect so that no interest is left due (0 when interest_first is false)")
            long minimumCollectMinor,

            @Schema(description = "What clears the interest, fees and penalties due, so nothing is carried")
            long suggestedCollectMinor,

            @Schema(description = "The amount this quote collects")
            long collectMinor,

            @Schema(description = "Principal that moves to the new loan, no cash")
            long principalMovedMinor,

            @Schema(description = "Interest, fees and penalties left unpaid, carried to the new loan as a memorandum")
            long carriedMinor,

            @Schema(description = "Interest of later periods that is no longer charged (R-PAYOFF rebate)")
            long rebatedMinor,

            int termCount,
            int minTermCount,
            int maxTermCount,
            String termUnit,
            int interestRateBp,
            String rateUnit,
            String interestMethod,
            String repaymentPattern,
            String instalmentFrequency,

            @Schema(
                    description = "The product's fixed renewal fee, collected with the interest (the request's payment"
                            + " method) and income once the renewal is approved; never added to the new loan")
            long renewalFeeMinor,

            LocalDate firstDueDate,
            LocalDate newDueDate,
            long firstInstalmentMinor,
            long newTotalInterestMinor,
            long newTotalDueMinor,

            @Schema(description = "Which renewal the new loan is: 1 for the first")
            int renewalNo,

            @Schema(description = "Renewals the product allows in a chain; null for no limit")
            Integer maxRenewals) {}

    @Schema(
            name = "RenewalFeeRefundRequest",
            description = "Give back the renewal fee a loan holds because no renewal used it (review of #919)")
    record FeeRefundRequest(
            @NotNull @Schema(description = "Today or earlier")
            LocalDate valueDate,

            @NotNull @Pattern(regexp = ServicingApi.METHODS) String paymentMethodKey,
            @Size(max = 100) String externalReference) {}

    @Schema(name = "LoanRenewalLinks", description = "Issue #918: the loans before and after this one in its renewals")
    record Links(
            @Schema(description = "0 for a loan that is no renewal")
            int renewalNo,

            UUID renewedFromLoanId,
            String renewedFromLoanNo,
            UUID renewedToLoanId,
            String renewedToLoanNo,
            LocalDate renewedOn) {

        /** The links of a loan for its page; null for a loan that is no renewal and was never renewed. */
        static Links of(RenewalRepository.Links l) {
            if (l.renewedFromLoanId() == null && l.renewedToLoanId() == null) {
                return null;
            }
            return new Links(
                    l.renewalNo(),
                    l.renewedFromLoanId(),
                    l.renewedFromLoanNo(),
                    l.renewedToLoanId(),
                    l.renewedToLoanNo(),
                    l.renewedOn());
        }
    }
}
