package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.documents.Documents.StoredDocument;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.Letterheads;
import com.rincoltech.bms.core.tenancy.Letterheads.Letterhead;
import com.rincoltech.bms.kernel.AmountInWords;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.AllocationRow;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.LoanBalances;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.LoanTransaction;
import com.rincoltech.bms.lending.loans.internal.ServicingApi.ScheduleRow;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.members.MemberLookup.MemberSummary;
import com.rincoltech.bms.lending.products.ProductCatalog;
import com.rincoltech.bms.lending.products.ProductCatalog.FeeCharge;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-DOC-01 for loans (ADR-034): the repayment receipt and the disbursement voucher, rendered on
 * first request and stored once (FR-DOC-02), and the repayment schedule and loan statement, rendered
 * as at the business date on each request. Every document carries the tenant's letterhead and the
 * branch of the loan; amounts appear in figures and in words.
 */
@Service
class LoanDocuments {

    static final String SUBJECT = "lending.loan";
    static final Set<String> RECEIPTED = Set.of("repayment", "recovery", "fee_upfront");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Map<String, String> METHODS = Map.of(
            "cash", "Cash",
            "bank", "Bank",
            "mtn_momo", "MTN Mobile Money",
            "airtel_money", "Airtel Money",
            "gateway", "Online payment (mobile money or card)");
    private static final Map<String, String> COMPONENTS = Map.of(
            "penalty", "Penalty",
            "fee", "Fees",
            "interest", "Interest",
            "principal", "Principal",
            "overpayment", "Credit held for the member");

    private final LoanRepository loans;
    private final ServicingRepository repo;
    private final ServicingService servicing;
    private final MemberLookup members;
    private final ProductCatalog products;
    private final Letterheads letterheads;
    private final Documents documents;
    private final CurrentTenant currentTenant;
    private final BusinessClock clock;
    private final JdbcClient jdbc;

    LoanDocuments(
            LoanRepository loans,
            ServicingRepository repo,
            ServicingService servicing,
            MemberLookup members,
            ProductCatalog products,
            Letterheads letterheads,
            Documents documents,
            CurrentTenant currentTenant,
            BusinessClock clock,
            JdbcClient jdbc) {
        this.loans = loans;
        this.repo = repo;
        this.servicing = servicing;
        this.members = members;
        this.products = products;
        this.letterheads = letterheads;
        this.documents = documents;
        this.currentTenant = currentTenant;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    record Issued(UUID documentId, String docType, String docNumber, boolean created) {}

    /**
     * The receipt of a repayment, recovery or upfront fee, or the voucher of a disbursement: the
     * stored copy when there is one, else rendered now and stored. The loan's row lock serialises
     * two first requests for the same transaction, so a document is stored once.
     */
    @Transactional
    Issued issue(UUID loanId, UUID txnId) {
        Loan loan = inScope(loanId);
        loans.lock(loanId);
        LoanTransaction txn = repo.transaction(loanId, txnId).orElseThrow(ApiException::notFound);
        boolean voucher = txn.txnType().equals("disbursement");
        if (!voucher && !RECEIPTED.contains(txn.txnType())) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "no_document",
                    "No document",
                    "A " + txn.txnType() + " has no receipt or voucher; it shows on the loan statement.");
        }
        String docType = voucher ? "voucher" : "receipt";
        Optional<StoredDocument> stored = documents.findGenerated(SUBJECT, loanId, docType, txn.receiptNo());
        if (stored.isPresent()) {
            return new Issued(stored.get().id(), docType, txn.receiptNo(), false);
        }
        Map<String, Object> model = common(loan, voucher ? "VOUCHER" : "RECEIPT", txn.receiptNo(), txn.valueDate());
        model.put("txn", txnModel(loan, txn));
        if (voucher) {
            voucherModel(loan, txn, model);
        } else {
            receiptModel(loan, txn, model);
        }
        byte[] pdf = documents.renderPdf(docType, model);
        StoredDocument doc = documents.storeGenerated(pdf, docType, txn.receiptNo(), SUBJECT, loanId, loan.branchId());
        return new Issued(doc.id(), docType, txn.receiptNo(), true);
    }

    /** The repayment schedule as at the business date (FR-DOC-01), never stored. */
    @Transactional(readOnly = true)
    byte[] schedule(UUID loanId) {
        Loan loan = inScope(loanId);
        requireDisbursed(loan);
        LocalDate today = today();
        Map<String, Object> model = common(loan, "LOAN SCHEDULE", series("SCH", loan), today);
        ServicingApi.Schedule schedule = servicing.schedule(loanId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ScheduleRow r : schedule.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("no", r.no());
            row.put("dueDate", date(r.dueDate()));
            row.put("principal", money(r.principalDueMinor(), loan.currency()));
            row.put("interest", money(r.interestDueMinor(), loan.currency()));
            row.put("fees", money(r.feesDueMinor(), loan.currency()));
            row.put("total", money(r.totalDueMinor(), loan.currency()));
            row.put("paid", money(r.totalPaidMinor(), loan.currency()));
            row.put("outstanding", money(r.outstandingMinor(), loan.currency()));
            row.put("status", r.status() == null ? "" : r.status().replace('_', ' '));
            rows.add(row);
        }
        ScheduleRow t = schedule.totals();
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("principal", money(t.principalDueMinor(), loan.currency()));
        totals.put("interest", money(t.interestDueMinor(), loan.currency()));
        totals.put("fees", money(t.feesDueMinor(), loan.currency()));
        totals.put("total", money(t.totalDueMinor(), loan.currency()));
        totals.put("paid", money(t.totalPaidMinor(), loan.currency()));
        totals.put("outstanding", money(t.outstandingMinor(), loan.currency()));
        totals.put("outstandingWords", AmountInWords.of(t.outstandingMinor(), loan.currency()));
        model.put("rows", rows);
        model.put("totals", totals);
        return documents.renderPdf("schedule", model);
    }

    /** The loan statement as at the business date (FR-DOC-01), never stored. */
    @Transactional(readOnly = true)
    byte[] statement(UUID loanId) {
        Loan loan = inScope(loanId);
        requireDisbursed(loan);
        LocalDate today = today();
        Map<String, Object> model = common(loan, "STATEMENT", series("ST", loan), today);
        List<LoanTransaction> txns = new ArrayList<>(repo.transactions(loanId));
        txns.sort((a, b) -> a.valueDate().equals(b.valueDate())
                ? a.createdAt().compareTo(b.createdAt())
                : a.valueDate().compareTo(b.valueDate()));
        Map<UUID, String> numbers = new LinkedHashMap<>();
        txns.forEach(t -> numbers.put(t.id(), t.receiptNo()));
        long paidOut = 0;
        long received = 0;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (LoanTransaction t : txns) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("date", date(t.valueDate()));
            line.put("number", t.receiptNo() == null ? "" : t.receiptNo());
            line.put("out", "");
            line.put("in", "");
            switch (t.txnType()) {
                case "disbursement" -> {
                    line.put("description", "Disbursement");
                    line.put("out", money(t.amountMinor(), loan.currency()));
                    paidOut = Math.addExact(paidOut, t.amountMinor());
                }
                case "repayment", "recovery", "fee_upfront" -> {
                    line.put(
                            "description",
                            switch (t.txnType()) {
                                case "recovery" -> "Recovery";
                                case "fee_upfront" -> "Upfront fee";
                                default -> "Repayment";
                            });
                    line.put("in", money(t.amountMinor(), loan.currency()));
                    received = Math.addExact(received, t.amountMinor());
                }
                case "reversal" -> {
                    String reversed = numbers.get(t.reversesTxnId());
                    line.put("description", "Reversal of " + (reversed == null ? "a repayment" : reversed));
                    line.put("in", "-" + money(t.amountMinor(), loan.currency()));
                    received = Math.subtractExact(received, t.amountMinor());
                }
                case "write_off" ->
                    line.put("description", "Written off: " + money(t.amountMinor(), loan.currency()) + " principal");
                default -> line.put("description", t.txnType().replace('_', ' '));
            }
            lines.add(line);
        }
        LoanBalances b = repo.balances(loanId).orElseThrow();
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("out", money(paidOut, loan.currency()));
        totals.put("in", money(received, loan.currency()));
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("principal", money(b.principalOutstandingMinor(), loan.currency()));
        position.put("interest", money(b.interestOutstandingMinor(), loan.currency()));
        position.put("fees", money(b.feesOutstandingMinor(), loan.currency()));
        position.put(
                "penalties",
                b.penaltiesOutstandingMinor() == 0 ? null : money(b.penaltiesOutstandingMinor(), loan.currency()));
        position.put("total", money(b.totalOutstandingMinor(), loan.currency()));
        position.put("totalWords", AmountInWords.of(b.totalOutstandingMinor(), loan.currency()));
        position.put("credit", b.creditBalanceMinor() == 0 ? null : money(b.creditBalanceMinor(), loan.currency()));
        position.put("dpd", b.daysPastDue());
        model.put("txns", lines);
        model.put("totals", totals);
        model.put("position", position);
        return documents.renderPdf("loan_statement", model);
    }

    // ---- models ------------------------------------------------------------------------------

    private Map<String, Object> common(Loan loan, String title, String number, LocalDate on) {
        Letterhead lh = letterheads.current();
        Map<String, Object> letterhead = new LinkedHashMap<>();
        letterhead.put("name", lh.displayName());
        letterhead.put("colour", lh.brandColour());
        letterhead.put("onColour", lh.textOnBrand());
        letterhead.put("tint", tint(lh.brandColour()));
        letterhead.put("footer", lh.receiptFooter());
        letterhead.put(
                "logo",
                lh.logo()
                        .map(l -> "data:" + l.contentType() + ";base64,"
                                + Base64.getEncoder().encodeToString(l.bytes()))
                        .orElse(null));
        Map<String, Object> branch = jdbc.sql("SELECT name, location FROM branches WHERE id = ?")
                .param(loan.branchId())
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("name"));
                    m.put("location", rs.getString("location"));
                    return m;
                })
                .single();
        MemberSummary member = members.find(loan.memberId()).orElseThrow();
        Map<String, Object> memberModel = new LinkedHashMap<>();
        memberModel.put("name", member.fullName());
        memberModel.put("number", member.memberNo());
        LoanBalances b = repo.balances(loan.id()).orElse(null);
        Map<String, Object> loanModel = new LinkedHashMap<>();
        loanModel.put("number", loan.loanNo());
        loanModel.put(
                "product",
                products.version(loan.productVersionId()).orElseThrow().productCode());
        loanModel.put("currency", loan.currency());
        loanModel.put("status", loan.status().replace('_', ' '));
        loanModel.put("terms", terms(loan));
        loanModel.put("disbursed", b == null || b.disbursedOn() == null ? "" : date(b.disbursedOn()));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("title", title);
        doc.put("number", number);
        doc.put("date", date(on));
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("lh", letterhead);
        model.put("branch", branch);
        model.put("member", memberModel);
        model.put("loan", loanModel);
        model.put("doc", doc);
        return model;
    }

    private Map<String, Object> txnModel(Loan loan, LoanTransaction t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currency", t.currency());
        m.put("amount", money(t.amountMinor(), t.currency()));
        m.put("words", AmountInWords.of(t.amountMinor(), t.currency()));
        m.put("valueDate", date(t.valueDate()));
        m.put("method", METHODS.getOrDefault(t.paymentMethodKey(), String.valueOf(t.paymentMethodKey())));
        m.put("reference", t.externalReference());
        m.put("recordedBy", userName(t.recordedBy()));
        m.put("recordedOn", date(dateOf(t.createdAt())));
        return m;
    }

    private void receiptModel(Loan loan, LoanTransaction t, Map<String, Object> model) {
        Map<Integer, LocalDate> dueDates = new LinkedHashMap<>();
        repo.scheduleRows(loan.id()).forEach(r -> dueDates.put(r.no(), r.dueDate()));
        List<Map<String, Object>> rows = new ArrayList<>();
        if (t.txnType().equals("fee_upfront")) {
            rows.add(allocation("", "", "Upfront fee", money(t.amountMinor(), t.currency())));
        } else if (t.txnType().equals("recovery")) {
            rows.add(allocation("", "", "Recovery on a written-off loan", money(t.amountMinor(), t.currency())));
        } else {
            for (AllocationRow a : t.allocations()) {
                if (!COMPONENTS.containsKey(a.component()) || !t.id().equals(a.appliesToTxnId())) {
                    continue; // the early settlement rebate is a waiver, not money received
                }
                rows.add(allocation(
                        a.itemNo() == null ? "" : String.valueOf(a.itemNo()),
                        a.itemNo() == null ? "" : date(dueDates.get(a.itemNo())),
                        COMPONENTS.get(a.component()),
                        money(a.amountMinor(), t.currency())));
            }
        }
        model.put("allocations", rows);
        LoanBalances b = repo.balances(loan.id()).orElseThrow();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put(
                "outstanding",
                money(b.totalOutstandingMinor(), loan.currency()) + " " + loan.currency() + " as at " + date(today()));
        after.put("credit", b.creditBalanceMinor() == 0 ? null : money(b.creditBalanceMinor(), loan.currency()));
        after.put("nextDue", b.nextDueDate() == null ? "None" : date(b.nextDueDate()));
        model.put("after", after);
    }

    private void voucherModel(Loan loan, LoanTransaction t, Map<String, Object> model) {
        long principal = t.amountMinor();
        List<FeeCharge> fees = products.fees(loan.productVersionId(), principal);
        List<Map<String, Object>> deductions = new ArrayList<>();
        long deducted = 0;
        long upfront = 0;
        long added = 0;
        for (FeeCharge f : fees) {
            switch (f.timing()) {
                case "deducted_at_disbursement" -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", f.name() + " (deducted)");
                    row.put("amount", "-" + money(f.amountMinor(), t.currency()));
                    deductions.add(row);
                    deducted = Math.addExact(deducted, f.amountMinor());
                }
                case "paid_upfront" -> upfront = Math.addExact(upfront, f.amountMinor());
                default -> added = Math.addExact(added, f.amountMinor());
            }
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("principal", money(principal, t.currency()));
        v.put("deductions", deductions);
        v.put("net", money(principal - deducted, t.currency()));
        v.put("netWords", AmountInWords.of(principal - deducted, t.currency()));
        v.put("upfront", upfront == 0 ? null : money(upfront, t.currency()) + " " + t.currency());
        v.put("added", added == 0 ? null : money(added, t.currency()) + " " + t.currency());
        model.put("voucher", v);

        Map<String, Object> signs = new LinkedHashMap<>();
        signs.put("preparedBy", userName(t.recordedBy()));
        signs.put("preparedOn", date(dateOf(t.createdAt())));
        Optional<Map<String, Object>> decided = t.approvalRequestId() == null
                ? Optional.empty()
                : jdbc.sql("""
                                SELECT u.full_name, a.decided_at FROM approval_requests a
                                  LEFT JOIN users u ON u.id = a.decided_by WHERE a.id = ?
                                """)
                        .param(t.approvalRequestId())
                        .query((rs, n) -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("name", rs.getString("full_name"));
                            m.put("at", rs.getTimestamp("decided_at"));
                            return m;
                        })
                        .optional();
        if (decided.isPresent() && decided.get().get("name") != null) {
            signs.put("approvedBy", decided.get().get("name"));
            java.sql.Timestamp at = (java.sql.Timestamp) decided.get().get("at");
            signs.put("approvedOn", at == null ? "" : date(dateOf(at.toInstant())));
        } else {
            // FR-APR-04: below the tenant's threshold the disbursement needed no checker.
            signs.put("approvedBy", "no checker needed (below the approval threshold)");
            signs.put("approvedOn", date(t.valueDate()));
        }
        model.put("signs", signs);
    }

    private static Map<String, Object> allocation(String item, String dueDate, String component, String amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", item);
        m.put("dueDate", dueDate);
        m.put("component", component);
        m.put("amount", amount);
        return m;
    }

    // ---- helpers -----------------------------------------------------------------------------

    private Loan inScope(UUID loanId) {
        Principal principal = CurrentPrincipal.require();
        return loans.find(loanId)
                .filter(l -> principal.may("lending.loans.read", l.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    private static void requireDisbursed(Loan loan) {
        if (Set.of("draft", "submitted", "appraised", "approved", "rejected", "cancelled")
                .contains(loan.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "invalid_status_transition",
                    "Not disbursed",
                    "The loan is " + loan.status() + "; it has no schedule or statement yet.");
        }
    }

    /** A readable number for a document that is not stored: prefix, branch code and loan number (ADR-034, item 5). */
    private String series(String prefix, Loan loan) {
        String code = jdbc.sql("SELECT code FROM branches WHERE id = ?")
                .param(loan.branchId())
                .query(String.class)
                .single();
        return prefix + "-" + code + "-" + loan.loanNo();
    }

    private String userName(UUID userId) {
        if (userId == null) {
            return "the system";
        }
        return jdbc.sql("SELECT full_name FROM users WHERE id = ?")
                .param(userId)
                .query(String.class)
                .optional()
                .orElse("a former user");
    }

    private LocalDate today() {
        return clock.today(zone());
    }

    private ZoneId zone() {
        return currentTenant.profile().timezone();
    }

    private LocalDate dateOf(Instant at) {
        return at == null ? today() : LocalDate.ofInstant(at, zone());
    }

    private static String terms(Loan loan) {
        int term = loan.approvedTermCount() != null ? loan.approvedTermCount() : loan.requestedTermCount();
        String unit = loan.termUnit() + (term == 1 ? "" : "s");
        String rate = String.format(Locale.ENGLISH, "%.2f%%", loan.interestRateBp() / 100.0) + " "
                + loan.rateUnit().replace('_', ' ');
        String pattern = loan.repaymentPattern().equals("bullet")
                ? "one payment at the end"
                : loan.instalmentFrequency() + " instalments";
        return term + " " + unit + ", " + loan.interestMethod() + " interest at " + rate + ", " + pattern;
    }

    static String date(LocalDate d) {
        return d == null ? "" : d.format(DATE);
    }

    /** Thousands separated; currencies with a minor unit show two decimals (UGX has none). */
    static String money(long minor, String currency) {
        if ("UGX".equals(currency)) {
            return String.format(Locale.ENGLISH, "%,d", minor);
        }
        long abs = Math.abs(minor);
        return (minor < 0 ? "-" : "") + String.format(Locale.ENGLISH, "%,d.%02d", abs / 100, abs % 100);
    }

    /** A pale tint of the brand colour for alternate table rows: 88% white over it. */
    static String tint(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        int[] c = {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
        StringBuilder out = new StringBuilder("#");
        for (int v : c) {
            out.append(String.format("%02X", Math.round(v + (255 - v) * 0.88f)));
        }
        return out.toString();
    }
}
