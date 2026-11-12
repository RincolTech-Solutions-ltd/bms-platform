package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.loans.internal.RenewalApi.Quote;
import com.rincoltech.bms.lending.loans.internal.RenewalApi.RenewalRequest;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.ActionOutcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/lending/loans/{loan_id}/renewal-quote} and {@code /renewals} (issue #918, ADR-128; chapter 7 section
 * 7.11.13): the review step's figures and the renewal request. The request moves money (the collection), so it requires an
 * {@code Idempotency-Key}; a replay answers {@code Idempotent-Replayed: true}.
 */
@RestController
@RequestMapping(path = "/api/v1/lending/loans/{loan_id}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-loans")
class RenewalController {

    private final RenewalService service;

    RenewalController(RenewalService service) {
        this.service = service;
    }

    @GetMapping("/renewal-quote")
    @RequiresPermission(RenewalService.REQUEST_PERMISSION)
    @Operation(
            summary = "What renewing the loan would do on a date: interest due, what to collect, principal moved, the"
                    + " new term, fee and due date (issue #918)",
            operationId = "getLoanRenewalQuote")
    Quote quote(
            @PathVariable("loan_id") UUID id,
            @RequestParam(name = "value_date", required = false) LocalDate valueDate,
            @RequestParam(name = "collect_minor", required = false) Long collectMinor,
            @RequestParam(name = "term_count", required = false) Integer termCount,
            @RequestParam(name = "interest_rate_bp", required = false) Integer interestRateBp) {
        return service.quote(id, valueDate, collectMinor, termCount, interestRateBp);
    }

    @PostMapping("/renewals")
    @RequiresPermission(RenewalService.REQUEST_PERMISSION)
    @Operation(
            summary =
                    "Renew an active loan: collect what is due as a repayment now, and ask a second person to move the"
                            + " principal to a new loan (issue #918); 202 while it waits",
            operationId = "requestLoanRenewal")
    ResponseEntity<ActionOutcome> renew(
            @PathVariable("loan_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody RenewalRequest request) {
        Outcome<ActionOutcome> o = service.request(id, key, request);
        var response = ResponseEntity.status(o.body().executed() ? HttpStatus.CREATED : HttpStatus.ACCEPTED);
        if (o.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(o.body());
    }
}
