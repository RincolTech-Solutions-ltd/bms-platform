package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loan documents (chapter 7 section 7.11.13, FR-DOC-01, ADR-034). A receipt or voucher is issued
 * once and then downloaded through {@code POST /api/v1/documents/{id}/download-url} (FR-DOC-03);
 * the schedule and statement come back as the PDF itself, as at the business date.
 */
@RestController
@RequestMapping(path = "/api/v1/lending/loans/{loan_id}")
@Tag(name = "lending-loans")
class LoanDocumentController {

    @Schema(name = "LoanDocumentIssued")
    record IssuedResponse(
            UUID documentId,
            @Schema(description = "receipt or voucher") String docType,

            @Schema(description = "The transaction's RC- or VC- number")
            String docNumber,

            @Schema(description = "True when this request rendered and stored it")
            boolean created) {}

    private final LoanDocuments documents;

    LoanDocumentController(LoanDocuments documents) {
        this.documents = documents;
    }

    @PostMapping(path = "/transactions/{txn_id}/document", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("lending.loans.read")
    @Operation(
            summary = "The receipt or voucher of a loan transaction, stored on first request (FR-DOC-01)",
            operationId = "issueLoanTransactionDocument")
    IssuedResponse issue(@PathVariable("loan_id") UUID loanId, @PathVariable("txn_id") UUID txnId) {
        LoanDocuments.Issued i = documents.issue(loanId, txnId);
        return new IssuedResponse(i.documentId(), i.docType(), i.docNumber(), i.created());
    }

    @PostMapping(
            path = "/documents/{doc_type}",
            produces = {MediaType.APPLICATION_PDF_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @RequiresPermission("lending.loans.read")
    @Operation(
            summary = "The repayment schedule (schedule) or loan statement (loan_statement) as a PDF, as at today",
            operationId = "renderLoanDocument")
    ResponseEntity<byte[]> render(@PathVariable("loan_id") UUID loanId, @PathVariable("doc_type") String docType) {
        byte[] pdf = switch (docType) {
            case "schedule" -> documents.schedule(loanId);
            case "loan_statement" -> documents.statement(loanId);
            default -> throw ApiException.notFound();
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(docType + ".pdf")
                                .build()
                                .toString())
                .body(pdf);
    }
}
