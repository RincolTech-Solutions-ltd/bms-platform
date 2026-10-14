package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.reporting.internal.XlsxReader.Sheet;
import com.rincoltech.bms.lending.insights.InsightsSnapshots;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code lending.portfolio_outstanding} and {@code lending.par} as PDF, Excel, CSV and JSON (extras slice D2, issue #217;
 * ADR-038 decisions 3, 5 and 6; chapter 14 section 14.3; FR-RPT-02, FR-RPT-05, FR-RPT-07; extras principle 6). The
 * golden dataset is the worked example of chapter 14 (see {@link LendingBookSupport}); every figure below was computed by hand
 * and is written as a number, never recomputed by the code under test.
 *
 * <p>The tests prove, in order: the portfolio figures, the figures of every group equal the insights endpoints for the same
 * filter and caller, the PDF and the workbook carry the same numbers as the JSON and the page, the scope of a branch or an
 * officer limited caller, who may fetch a file, the queued business date across midnight in Kampala, escaping of names, and
 * that no name or number reaches a log.
 */
@Import(TestReports.class)
@TestPropertySource(
        properties = {
            "bms.reports.max-concurrent-runs=1",
            "bms.reports.timeout=PT30S",
            "bms.reports.xlsx-row-cap=500",
            "bms.reports.pdf-row-cap=200",
            "bms.reports.max-file-bytes=1048576",
            "bms.reports.cancel-poll=PT0.2S",
            "bms.reports.heartbeat=PT0.1S",
            "bms.reports.runner-enabled=false"
        })
@ExtendWith(OutputCaptureExtension.class)
class LendingPortfolioReportsIT extends LendingBookSupport {

    static final String PORT = "lending.portfolio_outstanding";
    static final String PAR = "lending.par";

    @Autowired
    InsightsSnapshots snapshotJob;

    /**
     * The golden book plus one more active loan of the member of LN0001 (head office) in Test Branch Two, a second product,
     * officer B, 600,000 current: a borrower with loans in two branches.
     *
     * <p>Head Office (officer A, "Test Product"): LN0001 4,000,000; LN0002 2,200,000; LN0003 1,000,000 + 50,000 interest,
     * 30 days late. Test Branch Two (officer B): LN0004 2,000,000; LN0005 500,000 + 10,000 fees, 31 days late; LN0006 300,000,
     * 91 days late; LN0008 600,000 ("Second Product"), whose borrower is also the head office's first.
     */
    Book book() {
        Book b = goldenBook();
        UUID second = productNamed(b.t(), b.officerB(), "PORT2", "Second Product");
        loanOfMember(b.t(), second, b.t().secondBranch(), b.officerB(), "LN0008", "MLN0001", 600_000);
        return b;
    }

    // ---- the figures -------------------------------------------------------------------------------------

    @Test
    void thePortfolioIsTheHandComputedSumPerBranchWithBorrowersCountedOncePerGroup() throws Exception {
        Book book = book();
        UUID user = UUID.randomUUID();
        JsonNode run = runReport(book, user, PAR_PERMS, PORT, Map.of("group_by", "branch"), "json");
        JsonNode file = file(book, user, PAR_PERMS, run);

        assertThat(file.get("report_key").asString()).isEqualTo(PORT);
        assertThat(file.get("parameters").get("as_at").asString()).isEqualTo("2026-10-08");
        assertThat(file.get("notes").toString())
                .contains("loan tables")
                .contains("written-off loans are not")
                .contains("once in each group")
                .contains("Fees and penalties outstanding are not shown");
        // Group, active loans, borrowers, principal outstanding, interest outstanding, arrears, average principal.
        // Head Office: 3 loans, 3 borrowers, 7,200,000, 50,000 interest, 1,050,000 arrears (1,000,000 + 50,000),
        // 2,400,000.
        // Test Branch Two: 4 loans (one of them a head office borrower), 4 borrowers, 3,400,000, no interest, 810,000
        // arrears
        // (500,000 + 10,000 + 300,000), 3,400,000 / 4 = 850,000.
        // Total: 7 loans but 6 borrowers (the shared borrower counts once), 10,600,000, 50,000, 1,860,000, and
        // 10,600,000 / 7 = 1,514,285.7 which is 1,514,286.
        assertThat(rows(table(file, "portfolio")))
                .containsExactly(
                        "[\"Head Office\",3,3,7200000,50000,1050000,2400000]",
                        "[\"Test Branch Two\",4,4,3400000,0,810000,850000]",
                        "[\"Total\",7,6,10600000,50000,1860000,1514286]");
        assertThat(table(file, "portfolio").get("columns").get(3).get("kind").asString())
                .isEqualTo("money");
        assertThat(run.get("row_count").asInt()).isEqualTo(3);
    }

    @Test
    void withNoGroupingTheTotalAloneIsAllLoansAndAGroupWithNoLoansHasNoAverage() throws Exception {
        Book book = book();
        UUID user = UUID.randomUUID();
        JsonNode all = file(book, user, PAR_PERMS, runReport(book, user, PAR_PERMS, PORT, Map.of(), "json"));
        assertThat(rows(table(all, "portfolio"))).containsExactly("[\"All loans\",7,6,10600000,50000,1860000,1514286]");

        // A branch scope of one branch that has no loan at all: zero everywhere and no average (not zero, not an
        // error).
        TestDatabase.Fixture empty = tenant(true);
        Book none = new Book(empty, user, user, UUID.randomUUID());
        JsonNode file = file(none, user, PAR_PERMS, runReport(none, user, PAR_PERMS, PORT, Map.of(), "json"));
        assertThat(rows(table(file, "portfolio"))).containsExactly("[\"All loans\",0,0,0,0,0,null]");
    }

    @Test
    void groupedByProductAndByOfficerEachGroupIsItsOwnHandComputedRow() throws Exception {
        Book book = book();
        UUID user = UUID.randomUUID();
        JsonNode byProduct = file(
                book, user, PAR_PERMS, runReport(book, user, PAR_PERMS, PORT, Map.of("group_by", "product"), "json"));
        // "Second Product" has LN0008 alone; "Test Product" the other six loans (9,... = 10,000,000).
        assertThat(rows(table(byProduct, "portfolio")))
                .containsExactly(
                        "[\"Second Product\",1,1,600000,0,0,600000]",
                        "[\"Test Product\",6,6,10000000,50000,1860000,1666667]",
                        "[\"Total\",7,6,10600000,50000,1860000,1514286]");
        JsonNode byOfficer = file(
                book, user, PAR_PERMS, runReport(book, user, PAR_PERMS, PORT, Map.of("group_by", "officer"), "json"));
        String a = "Staff "
                + book.officerA()
                        .toString()
                        .substring(book.officerA().toString().length() - 4);
        String b = "Staff "
                + book.officerB()
                        .toString()
                        .substring(book.officerB().toString().length() - 4);
        List<String> officerRows = rows(table(byOfficer, "portfolio"));
        // Officer A: LN0001 to LN0003 (3 loans, 7,200,000). Officer B: LN0004 to LN0006 and LN0008 (4 loans,
        // 3,400,000).
        assertThat(officerRows)
                .contains(
                        "[\"" + a + "\",3,3,7200000,50000,1050000,2400000]",
                        "[\"" + b + "\",4,4,3400000,0,810000,850000]",
                        "[\"Total\",7,6,10600000,50000,1860000,1514286]")
                .hasSize(3);
    }

    // ---- the same numbers as the insights endpoints ----------------------------------------------------------

    @Test
    void everyGroupOfTheReportEqualsTheInsightsPortfolioForTheSameFilter() throws Exception {
        Book book = book();
        UUID user = UUID.randomUUID();
        String insights = "lending.insights.read,lending.insights.all_officers";
        UUID second = UUID.fromString(TestDatabase.owner()
                .sql("SELECT id::text FROM lending_loan_products WHERE tenant_id = ? AND code = 'PORT2'")
                .param(book.t().tenantId())
                .query(String.class)
                .single());

        // The whole book, then each branch, product and officer as the page filters them.
        for (String by : List.of("none", "branch", "product", "officer")) {
            JsonNode file =
                    file(book, user, PAR_PERMS, runReport(book, user, PAR_PERMS, PORT, Map.of("group_by", by), "json"));
            JsonNode total = last(table(file, "portfolio"));
            JsonNode page = insightsPortfolio(book, user, insights, null, "");
            assertRowEqualsPage(total, page);
        }
        JsonNode byBranch = file(
                book, user, PAR_PERMS, runReport(book, user, PAR_PERMS, PORT, Map.of("group_by", "branch"), "json"));
        assertRowEqualsPage(
                row(byBranch, "Head Office"),
                insightsPortfolio(
                        book, user, insights, null, "&branch_id=" + book.t().headOffice()));
        assertRowEqualsPage(
                row(byBranch, "Test Branch Two"),
                insightsPortfolio(
                        book, user, insights, null, "&branch_id=" + book.t().secondBranch()));
        JsonNode byProduct = file(
                book, user, PAR_PERMS, runReport(book, user, PAR_PERMS, PORT, Map.of("group_by", "product"), "json"));
        assertRowEqualsPage(
                row(byProduct, "Second Product"),
                insightsPortfolio(book, user, insights, null, "&product_id=" + second));
        assertRowEqualsPage(
                row(byProduct, "Test Product"),
                insightsPortfolio(book, user, insights, null, "&product_id=" + book.product()));
        JsonNode byOfficer = file(
                book, user, PAR_PERMS, runReport(book, user, PAR_PERMS, PORT, Map.of("group_by", "officer"), "json"));
        assertRowEqualsPage(
                row(byOfficer, "Staff " + tail(book.officerA())),
                insightsPortfolio(book, user, insights, null, "&officer_id=" + book.officerA()));
        assertRowEqualsPage(
                row(byOfficer, "Staff " + tail(book.officerB())),
                insightsPortfolio(book, user, insights, null, "&officer_id=" + book.officerB()));

        // And two branches asked for by name give the same as the page does with both.
        JsonNode both = file(
                book,
                user,
                PAR_PERMS,
                runReport(
                        book,
                        user,
                        PAR_PERMS,
                        PORT,
                        Map.of(
                                "branch_id",
                                List.of(
                                        book.t().headOffice().toString(),
                                        book.t().secondBranch().toString())),
                        "json"));
        assertRowEqualsPage(
                last(table(both, "portfolio")),
                insightsPortfolio(
                        book,
                        user,
                        insights,
                        null,
                        "&branch_id=" + book.t().headOffice() + "&branch_id="
                                + book.t().secondBranch()));
    }

    @Test
    void everyGroupOfTheParReportEqualsTheInsightsPageForTheSameFilter() throws Exception {
        Book book = book();
        UUID user = UUID.randomUUID();
        String insights = "lending.insights.read,lending.insights.all_officers";
        JsonNode byBranch = file(
                book, user, PAR_PERMS, runReport(book, user, PAR_PERMS, PAR, Map.of("group_by", "branch"), "json"));
        for (Map.Entry<String, UUID> e : Map.of(
                        "Head Office",
                        book.t().headOffice(),
                        "Test Branch Two",
                        book.t().secondBranch())
                .entrySet()) {
            JsonNode page = insightsPortfolio(book, user, insights, null, "&branch_id=" + e.getValue());
            JsonNode r = row(byBranch, e.getKey(), "par");
            assertThat(r.get(1).asLong()).as(e.getKey()).isEqualTo(metric(page, "portfolio.active_loans"));
            assertThat(r.get(2).asLong()).isEqualTo(metric(page, "portfolio.principal_outstanding"));
            assertThat(r.get(3).asLong()).isEqualTo(metric(page, "portfolio.arrears"));
            assertThat(r.get(4).isNull() ? 0 : r.get(4).asLong()).isEqualTo(metric(page, "portfolio.par1"));
            assertThat(r.get(5).isNull() ? 0 : r.get(5).asLong()).isEqualTo(metric(page, "portfolio.par30"));
            assertThat(r.get(6).isNull() ? 0 : r.get(6).asLong()).isEqualTo(metric(page, "portfolio.par60"));
            assertThat(r.get(7).isNull() ? 0 : r.get(7).asLong()).isEqualTo(metric(page, "portfolio.par90"));
        }
    }

    private static String tail(UUID id) {
        String s = id.toString();
        return s.substring(s.length() - 4);
    }

    private static JsonNode last(JsonNode table) {
        return table.get("rows").get(table.get("rows").size() - 1);
    }

    private static JsonNode row(JsonNode file, String label) {
        return row(file, label, "portfolio");
    }

    private static JsonNode row(JsonNode file, String label, String table) {
        for (JsonNode r : table(file, table).get("rows")) {
            if (r.get(0).asString().equals(label)) {
                return r;
            }
        }
        throw new AssertionError("no row " + label);
    }

    /** A portfolio report row: loans, borrowers, principal, interest, arrears, average; against the page's own metrics. */
    private static void assertRowEqualsPage(JsonNode r, JsonNode page) {
        String label = r.get(0).asString();
        assertThat(r.get(1).asLong()).as("%s active loans", label).isEqualTo(metric(page, "portfolio.active_loans"));
        assertThat(r.get(2).asLong()).as("%s borrowers", label).isEqualTo(metric(page, "portfolio.borrowers"));
        assertThat(r.get(3).asLong())
                .as("%s principal", label)
                .isEqualTo(metric(page, "portfolio.principal_outstanding"));
        assertThat(r.get(4).asLong()).as("%s interest", label).isEqualTo(metric(page, "portfolio.interest_receivable"));
        assertThat(r.get(5).asLong()).as("%s arrears", label).isEqualTo(metric(page, "portfolio.arrears"));
    }

    // ---- the PDF and the workbook ---------------------------------------------------------------------------

    @Test
    void thePortfolioPdfPrintsTheHandComputedFiguresAndTheWorkbookTheSameNumbers() throws Exception {
        Book book = book();
        UUID user = UUID.randomUUID();
        JsonNode pdfRun = runReport(book, user, PAR_PERMS, PORT, Map.of("group_by", "branch"), "pdf");
        assertThat(pdfRun.get("status").asString()).as("%s", pdfRun).isEqualTo("ready");
        byte[] bytes = downloadBytes(
                book.t(), user, PAR_PERMS, pdfRun.get("document_id").asString());
        ReportFormatsIT.sample("portfolio-by-branch.pdf", bytes);
        PdfProbe pdf = new PdfProbe(bytes);
        String text = pdf.text();

        assertThat(pdf.allFontsEmbedded()).isTrue();
        assertThat(pdf.pageSize(0)[0])
                .as("seven columns stay portrait")
                .isEqualTo(595f, org.assertj.core.data.Offset.offset(1f));
        assertThat(text)
                .contains("LOAN PORTFOLIO OUTSTANDING")
                .contains("As at 8 Oct 2026")
                .doesNotContain("As at 8 Oct 2026 As at")
                .contains("Branches All in your access")
                .contains("Group by Branch")
                .contains("Currency UGX")
                .contains("All branches in your access")
                .contains("Figures read from the loan tables.")
                .contains("Only active loans are counted; written-off loans are not.")
                .contains("Borrowers are counted once in each group, so the groups can add up to more than the total.")
                .contains("Active loans")
                .contains("Principal outstanding (UGX)")
                .contains("Arrears (UGX)")
                .contains("Head Office 3 3 7,200,000 50,000 1,050,000 2,400,000")
                .contains("Test Branch Two 4 4 3,400,000 0 810,000 850,000")
                .contains("Total 7 6 10,600,000 50,000 1,860,000 1,514,286")
                .contains("page 1 of 1");

        // The workbook: the same numbers, exact, as numeric cells; the PDF total is the sheet's total row.
        JsonNode xlsxRun = runReport(book, user, PAR_PERMS, PORT, Map.of("group_by", "branch"), "xlsx");
        byte[] x = downloadBytes(
                book.t(), user, PAR_PERMS, xlsxRun.get("document_id").asString());
        ReportFormatsIT.sample("portfolio-by-branch.xlsx", x);
        Sheet sh = XlsxReader.read(x).sheet("Loan portfolio");
        assertThat(sh.text("A2")).isEqualTo("Head Office");
        assertThat(List.of("B2", "C2", "D2", "E2", "F2", "G2").stream()
                        .map(sh::text)
                        .toList())
                .containsExactly("3", "3", "7200000", "50000", "1050000", "2400000");
        assertThat(List.of("B3", "C3", "D3", "E3", "F3", "G3").stream()
                        .map(sh::text)
                        .toList())
                .containsExactly("4", "4", "3400000", "0", "810000", "850000");
        assertThat(sh.text("A4")).isEqualTo("Total");
        assertThat(List.of("B4", "C4", "D4", "E4", "F4", "G4").stream()
                        .map(sh::text)
                        .toList())
                .containsExactly("7", "6", "10600000", "50000", "1860000", "1514286");
        assertThat(sh.text("D1")).isEqualTo("Principal outstanding (UGX)");
    }

    @Test
    void theParPdfAndWorkbookCarryTheChapter14WorkedExample() throws Exception {
        Book book = goldenBook();
        UUID user = UUID.randomUUID();
        JsonNode pdfRun = runReport(book, user, PAR_PERMS, PAR, Map.of("group_by", "branch"), "pdf");
        byte[] bytes = downloadBytes(
                book.t(), user, PAR_PERMS, pdfRun.get("document_id").asString());
        ReportFormatsIT.sample("par-by-branch.pdf", bytes);
        PdfProbe pdf = new PdfProbe(bytes);
        String text = pdf.text();
        assertThat(pdf.pageSize(0)[0])
                .as("eight columns turn the page landscape")
                .isEqualTo(842f, org.assertj.core.data.Offset.offset(1f));
        assertThat(pdf.allFontsEmbedded()).isTrue();
        // PAR 1 18.0, PAR 30 8.0, PAR 60 and 90 3.0 percent: the worked example. Head Office 13.9 (1,000,000 of
        // 7,200,000) and
        // Test Branch Two 28.6, 28.6, 10.7, 10.7 (800,000 and 300,000 of 2,800,000), half up from the basis points.
        assertThat(text)
                .contains("PORTFOLIO AT RISK AND AGEING")
                .contains("Active loans")
                .contains("Arrears (UGX)")
                .contains("PAR 1 PAR 30 PAR 60 PAR 90")
                .contains("Head Office 3 7,200,000 1,050,000 13.9% 0.0% 0.0% 0.0%")
                .contains("Test Branch Two 3 2,800,000 810,000 28.6% 28.6% 10.7% 10.7%")
                .contains("Total 6 10,000,000 1,860,000 18.0% 8.0% 3.0% 3.0%")
                .contains("Ageing of the active loans")
                .contains("Share of principal")
                .contains("Total Current (not late) 3 8,200,000 82.0% 0")
                .contains("Total 1 to 30 days late 1 1,000,000 10.0% 1,050,000")
                .contains("Total Over 90 days late 1 300,000 3.0% 300,000");

        JsonNode xlsxRun = runReport(book, user, PAR_PERMS, PAR, Map.of("group_by", "branch"), "xlsx");
        byte[] x = downloadBytes(
                book.t(), user, PAR_PERMS, xlsxRun.get("document_id").asString());
        ReportFormatsIT.sample("par-by-branch.xlsx", x);
        XlsxReader wb = XlsxReader.read(x);
        assertThat(wb.sheets)
                .extracting(Sheet::name)
                .containsExactly("Parameters", "Portfolio at risk", "Ageing of the active loans");
        Sheet par = wb.sheet("Portfolio at risk");
        // Rates are fractions shown as percentages: 1800 bp is 0.18, and PAR 90 of 300 bp is 0.03.
        assertThat(par.text("A4")).isEqualTo("Total");
        assertThat(List.of("B4", "C4", "D4", "E4", "F4", "G4", "H4").stream()
                        .map(par::text)
                        .toList())
                .containsExactly("6", "10000000", "1860000", "0.18", "0.08", "0.03", "0.03");
        assertThat(wb.numFmts.get(wb.styles.get(par.cell("E4").style()).numFmt()))
                .isEqualTo("0.0%");
        assertThat(wb.styles.get(par.cell("E4").style()).bold()).isTrue();
        assertThat(par.text("E2")).isEqualTo("0.1389");
        assertThat(par.text("F2")).isEqualTo("0");
        assertThat(par.orientation()).isEqualTo("landscape");
        Sheet ageing = wb.sheet("Ageing of the active loans");
        // Head Office, 1 to 30 days late: 1 loan, 1,000,000, 13.89 percent, 1,050,000 arrears.
        assertThat(ageing.text("B3")).isEqualTo("1 to 30 days late");
        assertThat(List.of("C3", "D3", "E3", "F3").stream().map(ageing::text).toList())
                .containsExactly("1", "1000000", "0.1389", "1050000");
    }

    @Test
    void thePdfTotalsTheWorkbookTotalsAndTheInsightsPageAreOneNumber() throws Exception {
        Book book = book();
        UUID user = UUID.randomUUID();
        String insights = "lending.insights.read,lending.insights.all_officers";
        JsonNode page = insightsPortfolio(book, user, insights, null, "");
        long po = metric(page, "portfolio.principal_outstanding");
        long io = metric(page, "portfolio.interest_receivable");
        long arrears = metric(page, "portfolio.arrears");
        long loans = metric(page, "portfolio.active_loans");
        long borrowers = metric(page, "portfolio.borrowers");

        PdfProbe pdf = new PdfProbe(downloadBytes(
                book.t(),
                user,
                PAR_PERMS,
                runReport(book, user, PAR_PERMS, PORT, Map.of(), "pdf")
                        .get("document_id")
                        .asString()));
        String expected = "All loans " + String.format("%,d %,d %,d %,d %,d", loans, borrowers, po, io, arrears);
        assertThat(pdf.text()).contains(expected);
        Sheet sh = XlsxReader.read(downloadBytes(
                        book.t(),
                        user,
                        PAR_PERMS,
                        runReport(book, user, PAR_PERMS, PORT, Map.of(), "xlsx")
                                .get("document_id")
                                .asString()))
                .sheet("Loan portfolio");
        assertThat(List.of("B2", "C2", "D2", "E2", "F2").stream().map(sh::text).toList())
                .containsExactly(
                        String.valueOf(loans),
                        String.valueOf(borrowers),
                        String.valueOf(po),
                        String.valueOf(io),
                        String.valueOf(arrears));
        // And the page's own PAR figures are the PAR report's, in the PDF: PAR 1 is 1,800,000 of 10,600,000 = 1698
        // basis
        // points (17.0%), PAR 30 is 800,000 = 755 (7.5% shown 7.6% since the percentage is made from the integer, half
        // up),
        // PAR 60 and 90 are 300,000 = 283 (2.8%).
        assertThat(metric(page, "portfolio.par1")).isEqualTo(1698L);
        assertThat(metric(page, "portfolio.par30")).isEqualTo(755L);
        assertThat(metric(page, "portfolio.par60")).isEqualTo(283L);
        assertThat(metric(page, "portfolio.par90")).isEqualTo(283L);
        PdfProbe parPdf = new PdfProbe(downloadBytes(
                book.t(),
                user,
                PAR_PERMS,
                runReport(book, user, PAR_PERMS, PAR, Map.of(), "pdf")
                        .get("document_id")
                        .asString()));
        assertThat(parPdf.text()).contains("All loans 7 10,600,000 1,860,000 17.0% 7.6% 2.8% 2.8%");
    }

    @Test
    void aPastDateReadsTheSnapshotInThePdfAndTheWorkbookAndSaysSo() throws Exception {
        Book book = book();
        UUID user = UUID.randomUUID();
        tenantJobs.callAs(
                book.t().tenantId(),
                () -> new TransactionTemplate(transactions)
                        .execute(x -> snapshotJob.backfill(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 7))));
        JsonNode run = runReport(book, user, PAR_PERMS, PORT, Map.of("as_at", "2026-10-07"), "pdf");
        assertThat(run.get("status").asString()).as("%s", run).isEqualTo("ready");
        PdfProbe pdf = new PdfProbe(
                downloadBytes(book.t(), user, PAR_PERMS, run.get("document_id").asString()));
        // A day earlier, the same six loans (LN0008 is also active in the snapshot: it was disbursed in January).
        assertThat(pdf.text())
                .contains("As at 7 Oct 2026")
                .contains("Figures read from the nightly snapshot of 2026-10-07.")
                .contains("All loans 7 6 10,600,000 50,000 1,860,000 1,514,286");
    }

    // ---- scope ---------------------------------------------------------------------------------------------

    @Test
    void aBranchLimitedCallerGetsTheirBranchInEveryFormatAndItEqualsTheirInsightsPage() throws Exception {
        Book book = book();
        UUID user = UUID.randomUUID();
        String second = book.t().secondBranch().toString();
        var h = headers(book.t(), user, PAR_PERMS);
        h.set("X-Dev-Branch-Ids", second);
        // Asking for the whole book, the caller gets the branch they hold, and the page agrees (principal 3,400,000).
        for (String format : List.of("json", "pdf", "xlsx")) {
            ResponseEntity<JsonNode> posted = http.exchange(
                    "/api/v1/report-runs",
                    HttpMethod.POST,
                    new HttpEntity<>(body(PORT, Map.of(), format), h),
                    JsonNode.class);
            assertThat(posted.getStatusCode().value())
                    .as("%s", posted.getBody())
                    .isEqualTo(202);
            drain();
            JsonNode run =
                    run(book.t(), user, PAR_PERMS, posted.getBody().get("id").asString());
            assertThat(run.get("status").asString()).as("%s", run).isEqualTo("ready");
            // Downloaded with the same branch header the requester holds.
            String doc = run.get("document_id").asString();
            ResponseEntity<JsonNode> url = downloadUrl(book, user, PAR_PERMS, second, doc);
            assertThat(url.getStatusCode().value()).isEqualTo(200);
        }
        JsonNode page = insightsPortfolio(book, user, PAR_PERMS, second, "");
        assertThat(metric(page, "portfolio.principal_outstanding")).isEqualTo(3_400_000L);
        // The scope the run froze names that branch only.
        String scope = TestDatabase.owner()
                .sql("SELECT scope::text FROM report_runs WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 1")
                .param(book.t().tenantId())
                .query(String.class)
                .single();
        assertThat(scope).contains(second).doesNotContain(book.t().headOffice().toString());
    }

    @Test
    void withoutAllOfficersACallerSeesOnlyTheirOwnLoansInThePdfAndTheWorkbook() throws Exception {
        Book book = book();
        String perms = PORTFOLIO + ",lending.insights.read";
        // Officer B's four loans: 3,400,000 principal; 4 borrowers; the page, as officer B, says the same.
        PdfProbe pdf = new PdfProbe(downloadBytes(
                book.t(),
                book.officerB(),
                perms,
                runReport(book, book.officerB(), perms, PORT, Map.of(), "pdf")
                        .get("document_id")
                        .asString()));
        assertThat(pdf.text()).contains("All loans 4 4 3,400,000 0 810,000 850,000");
        JsonNode page = insightsPortfolio(book, book.officerB(), "lending.insights.read", null, "");
        assertThat(metric(page, "portfolio.principal_outstanding")).isEqualTo(3_400_000L);
        assertThat(metric(page, "portfolio.active_loans")).isEqualTo(4L);
        Sheet sh = XlsxReader.read(downloadBytes(
                        book.t(),
                        book.officerB(),
                        perms,
                        runReport(book, book.officerB(), perms, PORT, Map.of(), "xlsx")
                                .get("document_id")
                                .asString()))
                .sheet("Loan portfolio");
        assertThat(sh.text("D2")).isEqualTo("3400000");
    }

    private ResponseEntity<JsonNode> downloadUrl(
            Book book, UUID user, String permissions, String branches, String documentId) {
        var h = headers(book.t(), user, permissions);
        h.set("X-Dev-Branch-Ids", branches);
        return http.exchange(
                "/api/v1/documents/" + documentId + "/download-url",
                HttpMethod.POST,
                new HttpEntity<>(h),
                JsonNode.class);
    }

    @Test
    void anotherTenantAnotherBranchAnotherUserAndSwitchedOffReportsAllGetNotFoundForThePdfAndTheWorkbook()
            throws Exception {
        Book book = book();
        UUID user = UUID.randomUUID();
        String head = book.t().headOffice().toString();
        var h = headers(book.t(), user, PAR_PERMS);
        h.set("X-Dev-Branch-Ids", head);
        List<String> documents = new ArrayList<>();
        List<String> runs = new ArrayList<>();
        for (String format : List.of("pdf", "xlsx")) {
            ResponseEntity<JsonNode> posted = http.exchange(
                    "/api/v1/report-runs",
                    HttpMethod.POST,
                    new HttpEntity<>(body(PORT, Map.of(), format), h),
                    JsonNode.class);
            drain();
            JsonNode run =
                    run(book.t(), user, PAR_PERMS, posted.getBody().get("id").asString());
            assertThat(run.get("status").asString()).isEqualTo("ready");
            documents.add(run.get("document_id").asString());
            runs.add(run.get("id").asString());
        }
        // Another tenant, with every permission and branch: the run and the file are not theirs to know about.
        TestDatabase.Fixture other = tenant(true);
        for (int i = 0; i < 2; i++) {
            assertThat(call(HttpMethod.GET, "/api/v1/report-runs/" + runs.get(i), other, user, PAR_PERMS)
                            .getStatusCode()
                            .value())
                    .as("another tenant, run")
                    .isEqualTo(404);
            ResponseEntity<JsonNode> url = http.exchange(
                    "/api/v1/documents/" + documents.get(i) + "/download-url",
                    HttpMethod.POST,
                    new HttpEntity<>(headers(other, user, PAR_PERMS)),
                    JsonNode.class);
            assertThat(url.getStatusCode().value()).as("another tenant, file").isEqualTo(404);
            // Another branch: the requester now holds only the other branch.
            assertThat(downloadUrl(
                                    book,
                                    user,
                                    PAR_PERMS,
                                    book.t().secondBranch().toString(),
                                    documents.get(i))
                            .getStatusCode()
                            .value())
                    .as("another branch")
                    .isEqualTo(404);
            // Another user of the same tenant and branches.
            assertThat(call(
                                    HttpMethod.GET,
                                    "/api/v1/report-runs/" + runs.get(i),
                                    book.t(),
                                    UUID.randomUUID(),
                                    PAR_PERMS)
                            .getStatusCode()
                            .value())
                    .as("another user, run")
                    .isEqualTo(404);
            ResponseEntity<JsonNode> otherUser = http.exchange(
                    "/api/v1/documents/" + documents.get(i) + "/download-url",
                    HttpMethod.POST,
                    new HttpEntity<>(headers(book.t(), UUID.randomUUID(), PAR_PERMS)),
                    JsonNode.class);
            assertThat(otherUser.getStatusCode().value())
                    .as("another user, file")
                    .isEqualTo(404);
            // The requester still has theirs.
            assertThat(downloadUrl(book, user, PAR_PERMS, head, documents.get(i))
                            .getStatusCode()
                            .value())
                    .isEqualTo(200);
        }
        TestDatabase.owner()
                .sql(
                        "UPDATE tenant_settings SET settings = settings || '{\"reports_enabled\": false}' WHERE tenant_id = ?")
                .param(book.t().tenantId())
                .update();
        assertThat(downloadUrl(book, user, PAR_PERMS, head, documents.get(0))
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    @Test
    void theCatalogueOffersBothReportsInAllFourFormatsAndRefusesCallersWithoutInsightsAccess() {
        Book book = book();
        UUID user = UUID.randomUUID();
        JsonNode catalogue = call(HttpMethod.GET, "/api/v1/reports", book.t(), user, PAR_PERMS)
                .getBody();
        List<String> keys = new ArrayList<>();
        for (JsonNode r : catalogue) {
            if (r.get("key").asString().startsWith("lending.")) {
                keys.add(r.get("key").asString());
                assertThat(r.get("formats"))
                        .extracting(JsonNode::asString)
                        .containsExactly("csv", "json", "pdf", "xlsx");
                assertThat(r.get("permission").asString()).isEqualTo(PORTFOLIO);
            }
        }
        assertThat(keys).containsExactlyInAnyOrder(PAR, PORT);
        for (String key : List.of(PAR, PORT)) {
            for (String format : List.of("pdf", "xlsx")) {
                ResponseEntity<JsonNode> refused = post(book.t(), user, PORTFOLIO, key, Map.of(), format);
                assertThat(refused.getStatusCode().value()).isEqualTo(403);
                assertThat(refused.getBody().get("code").asString()).isEqualTo("permission_denied");
                assertThat(post(book.t(), user, "lending.members.read", key, Map.of(), format)
                                .getStatusCode()
                                .value())
                        .isEqualTo(403);
            }
            assertThat(post(book.t(), user, PAR_PERMS, key, Map.of("group_by", "colour"), "pdf")
                            .getStatusCode()
                            .value())
                    .isEqualTo(422);
            assertThat(post(book.t(), user, PAR_PERMS, key, Map.of("as_at", "2026-10-09"), "pdf")
                            .getStatusCode()
                            .value())
                    .isEqualTo(422);
        }
        // With reports off for the tenant nothing is listed or queued.
        TestDatabase.Fixture off = tenant(false);
        assertThat(call(HttpMethod.GET, "/api/v1/reports", off, user, PAR_PERMS)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(post(off, user, PAR_PERMS, PORT, Map.of(), "pdf")
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    // ---- the business date ---------------------------------------------------------------------------------

    @Test
    void theQueueTimeBusinessDateIsUsedAcrossMidnightInKampalaForEveryFormat() throws Exception {
        // Kampala is UTC+3 all year: 21:00 UTC is midnight. 20:59 UTC is 23:59 on the 8th; 21:00 and 21:01 are the 9th.
        Book book = book();
        UUID user = UUID.randomUUID();
        // Queued at 20:59:30 UTC (23:59:30 on the 8th), executed at 21:01:00 UTC (00:01 on the 9th, before tonight's
        // snapshot job has written the 8th): still the 8th, read from the loan tables, in every format.
        for (String format : List.of("pdf", "xlsx", "json")) {
            for (String key : List.of(PORT, PAR)) {
                clock.set(Instant.parse("2026-10-08T20:59:30Z"));
                ResponseEntity<JsonNode> posted = post(
                        book.t(),
                        user,
                        PAR_PERMS,
                        key,
                        Map.of("group_by", format.equals("json") ? "branch" : "none"),
                        format);
                assertThat(posted.getStatusCode().value())
                        .as("%s", posted.getBody())
                        .isEqualTo(202);
                assertThat(posted.getBody().get("as_of").asString()).isEqualTo("2026-10-08");
                clock.set(Instant.parse("2026-10-08T21:01:00Z"));
                drain();
                JsonNode run = run(
                        book.t(), user, PAR_PERMS, posted.getBody().get("id").asString());
                assertThat(run.get("status").asString())
                        .as("%s %s: %s", key, format, run)
                        .isEqualTo("ready");
                assertThat(run.get("as_of").asString()).isEqualTo("2026-10-08");
                String doc = run.get("document_id").asString();
                if (format.equals("pdf")) {
                    String text = new PdfProbe(downloadBytes(book.t(), user, PAR_PERMS, doc)).text();
                    // The figures are of the 8th; the file was made on the 9th, in the tenant's own time (00:01).
                    assertThat(text).contains("As at 8 Oct 2026").contains("Made 9 Oct 2026 00:01 (Africa/Kampala)");
                    assertThat(text).contains("Figures read from the loan tables.");
                } else if (format.equals("xlsx")) {
                    XlsxReader wb = XlsxReader.read(downloadBytes(book.t(), user, PAR_PERMS, doc));
                    assertThat(wb.sheet("Parameters").text("B4"))
                            .as("as at, a real date")
                            .isEqualTo("46303");
                    assertThat(wb.sheet("Parameters").text("B6")).isEqualTo("9 Oct 2026 00:01 (Africa/Kampala)");
                } else {
                    JsonNode file = file(book, user, PAR_PERMS, run);
                    assertThat(file.get("parameters").get("as_at").asString()).isEqualTo("2026-10-08");
                    assertThat(file.get("notes").toString()).contains("loan tables");
                }
            }
        }
        // Queued at 20:59:59 it is the 8th; at 21:00:00 and 21:01:00 it is the 9th, whatever the format.
        for (Map.Entry<String, String> e : Map.of(
                        "2026-10-08T20:59:59Z",
                        "2026-10-08",
                        "2026-10-08T21:00:00Z",
                        "2026-10-09",
                        "2026-10-08T21:01:00Z",
                        "2026-10-09")
                .entrySet()) {
            clock.set(Instant.parse(e.getKey()));
            ResponseEntity<JsonNode> posted =
                    post(book.t(), user, PAR_PERMS, PORT, Map.of("group_by", "product"), "pdf");
            assertThat(posted.getBody().get("as_of").asString()).as(e.getKey()).isEqualTo(e.getValue());
            drain();
            assertThat(run(book.t(), user, PAR_PERMS, posted.getBody().get("id").asString())
                            .get("status")
                            .asString())
                    .as("%s is read live", e.getKey())
                    .isEqualTo("ready");
        }
    }

    /** One more run, two minutes after the last, so a test that makes sixteen runs stays under the ten a minute limit. */
    private JsonNode later(Book book, UUID user, String key, String groupBy, String format) throws Exception {
        clock.set(clock.instant().plusSeconds(120));
        return runReport(book, user, PAR_PERMS, key, Map.of("group_by", groupBy), format);
    }

    // ---- names -----------------------------------------------------------------------------------------------

    @Test
    void branchAndProductNamesWithMarkupQuotesAndNonLatinTextAreEscapedInEveryFormat() throws Exception {
        Book book = book();
        UUID user = UUID.randomUUID();
        String branch = "Test <Branch> & \"Co\" 'North' Ünï 日本語";
        String product = "</td><b>Prod</b> & \"Q\" Тест";
        TestDatabase.owner()
                .sql("UPDATE branches SET name = ? WHERE id = ?")
                .params(branch, book.t().secondBranch())
                .update();
        TestDatabase.owner()
                .sql("UPDATE lending_loan_products SET name = ? WHERE tenant_id = ? AND code = 'PORT2'")
                .params(product, book.t().tenantId())
                .update();
        for (String key : List.of(PORT, PAR)) {
            for (String by : List.of("branch", "product")) {
                String name = by.equals("branch") ? branch : product;
                // JSON and CSV keep the text exactly (CSV quoted).
                JsonNode file = file(book, user, PAR_PERMS, later(book, user, key, by, "json"));
                String tableKey = key.equals(PORT) ? "portfolio" : "par";
                assertThat(row(file, name, tableKey).get(0).asString()).isEqualTo(name);
                String csv = download(
                        book.t(),
                        user,
                        PAR_PERMS,
                        later(book, user, key, by, "csv").get("document_id").asString());
                assertThat(csv).contains("\"" + name.replace("\"", "\"\"") + "\"");
                // The workbook keeps every character, scripts included.
                XlsxReader wb = XlsxReader.read(downloadBytes(
                        book.t(),
                        user,
                        PAR_PERMS,
                        later(book, user, key, by, "xlsx").get("document_id").asString()));
                Sheet sh = wb.sheets.get(1);
                List<String> names = new ArrayList<>();
                for (var r : sh.rows()) {
                    names.add(r.values().iterator().next().value());
                }
                assertThat(names).contains(name);
                // The PDF prints markup as typed and the scripts its font lacks as question marks; the page is whole.
                PdfProbe pdf = new PdfProbe(downloadBytes(
                        book.t(),
                        user,
                        PAR_PERMS,
                        later(book, user, key, by, "pdf").get("document_id").asString()));
                // The Latin and Cyrillic letters print; the Japanese the font lacks prints as three question marks.
                assertThat(pdf.text()).contains(name.replace("日本語", "???"));
                assertThat(pdf.allFontsEmbedded()).isTrue();
            }
        }
    }

    // ---- logs ------------------------------------------------------------------------------------------------

    @Test
    void noNameNumberOrAmountOfTheBookReachesALogOrTheStoredFileName(CapturedOutput output) throws Exception {
        Book book = book();
        UUID user = UUID.randomUUID();
        for (String key : List.of(PORT, PAR)) {
            for (String format : List.of("pdf", "xlsx", "csv", "json")) {
                JsonNode run = runReport(book, user, PAR_PERMS, key, Map.of("group_by", "branch"), format);
                assertThat(run.get("status").asString()).isEqualTo("ready");
            }
        }
        // The runs did log (eight finished lines), so the absences below are not an empty capture.
        assertThat(output.getAll().split("ready:", -1).length - 1).isGreaterThanOrEqualTo(8);
        assertThat(output.getAll())
                .doesNotContain("Test Borrower")
                .doesNotContain("+256700000001")
                .doesNotContain("CMTEST")
                .doesNotContain("Test Branch Two")
                .doesNotContain("10,600,000")
                .doesNotContain("10600000")
                .doesNotContain("7200000");
        // The run row and its failure code hold no name either.
        List<String> errors = TestDatabase.owner()
                .sql("SELECT coalesce(error, '') FROM report_runs WHERE tenant_id = ?")
                .param(book.t().tenantId())
                .query(String.class)
                .list();
        assertThat(errors).allMatch(String::isEmpty);
    }
}
