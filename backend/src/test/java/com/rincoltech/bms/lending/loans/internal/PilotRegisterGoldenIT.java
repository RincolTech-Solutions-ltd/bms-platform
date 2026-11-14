package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.CommitApproval;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportRuns.PolicyDecision;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.Api;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The golden test of issue #110 (SDD chapter 13 section 13.12, chapter 15 section 15.7): the fabricated pilot register
 * {@code fixtures/pilot_loan_register_sample.csv}, alone, through {@code lending_loans_v1} and its first mapping, on
 * PostgreSQL as {@code bms_app}, through the routes: the run opened and the file uploaded through the quarantine, the check
 * in the background (202, polled), the review with the standard resolutions of 13.12, the dry run in the background, ready,
 * the dump recorded and checked from the operator portal, the commit refused by the portal before approval, the maker's
 * {@code import_commit} request refused to the maker and approved by a checker, the commit started from the portal and run
 * on in the background, the reconciliation and the sign-off in the background to {@code signed_off}. Two steps have no
 * route on main and are shortcuts here: the cutover moment of 13.12 (30 June 2026, 21:00 in Kampala) is set on the run by
 * the owner role, and the decision that the requested principal was disbursed (13.8 row 15) is recorded through
 * {@code ImportRuns.recordPolicy}, dated the business's today. It asserts the issues of the 13.12 table as the check stored
 * them and everything 13.12 says the commit creates, read from the lending tables and the ledger, and the reconciliation's
 * counts and loan principal.
 * {@code LendingLoansV1TemplateIT} runs the same register beside a second branch's through the services; this one is the
 * register on its own, so the counts are exactly those of 13.12. Fabricated values only.
 */
class PilotRegisterGoldenIT extends IntegrationTest {

    static final String MANAGE = "core.imports.manage";

    static final String SIGN = "core.imports.manage,core.imports.sign_off";

    @Autowired
    ImportRuns runs;

    @Autowired
    ImportMappings mappings;

    @Autowired
    BusinessClock clock;

    @Autowired
    CurrentTenant currentTenant;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    UUID maker;
    final UUID checker = UUID.randomUUID();
    final UUID signer = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("pilot-golden", true);
        maker = Api.staff(t, Api.email("pilot-maker"));
        LendingLoansV1TemplateIT.product(t, maker);
    }

    <T> T as(Supplier<T> work) {
        return TenantContext.callAs(t.tenantId(), work);
    }

    @Test
    void thePilotRegisterGoesFromCheckToSignOffAsSection1312Says() throws Exception {
        UUID run = uploaded();
        String base = "/api/v1/imports/runs/" + run;

        // The check in the background, then the issues of the 13.12 table as stored.
        assertThat(started(maker, MANAGE, base + "/check", null)
                        .get("job")
                        .get("kind")
                        .asString())
                .isEqualTo("check");
        assertThat(jobEnded(run).get("state").asString()).isEqualTo("done");
        assertThat(codesByLine(run))
                .as("13.12 leaves out only the infos PHONE_NORMALISED and COLLATERAL_PARSED")
                .containsExactlyEntriesOf(new TreeMap<>(Map.of(
                        2, "SUBHEADER_ROW",
                        4, "DATE_SWAP_REPAIRED,DURATION_NORMALISED,KIN_IS_MEMBER",
                        5, "DATE_TEXT_PARSED,DATE_TEXT_PARSED",
                        6, "RATE_OUT_OF_RANGE",
                        7, "PLACEHOLDER_ROW",
                        8, "SUBHEADER_ROW",
                        9, "AMOUNTS_ONLY_ROW",
                        11, "DATE_SWAP_REPAIRED,DATE_SWAP_REPAIRED,KIN_IS_MEMBER",
                        12, "DUPLICATE_BORROWER,MULTIPLE_ACTIVE_LOANS",
                        13, "COLLATERAL_UNRECOGNISED,DATE_INCONSISTENT,NIN_MISSING")));
        assertThat(onRun(run, "i.severity = 'blocking'"))
                .containsExactlyInAnyOrder(
                        "6|RATE_OUT_OF_RANGE", "7|PLACEHOLDER_ROW", "9|AMOUNTS_ONLY_ROW", "13|DATE_INCONSISTENT");
        // The two infos the table leaves out are there, as infos: line 3's phones and its LAND pledge, and nothing
        // else.
        assertThat(onRun(run, "i.source_row_no = 3"))
                .contains("3|COLLATERAL_PARSED", "3|PHONE_NORMALISED")
                .allMatch(s -> s.equals("3|COLLATERAL_PARSED") || s.equals("3|PHONE_NORMALISED"));
        assertThat(onRun(run, "i.code IN ('PHONE_NORMALISED', 'COLLATERAL_PARSED') AND i.severity <> 'info'"))
                .isEmpty();

        // The standard resolutions of 13.12 through the review's route. The decision of 13.8 row 15 has no route on
        // main: recorded through the service, dated the business's today.
        settle(base);
        LocalDate today = as(() -> clock.today(currentTenant.profile().timezone()));
        as(() -> runs.recordPolicy(
                run,
                LendingLoansV1Template.DISBURSED,
                new PolicyDecision(
                        LendingLoansV1Template.REQUESTED_AS_DISBURSED,
                        today,
                        "Question list item 1, signed",
                        maker,
                        null,
                        null)));

        // The dry run in the background writes nothing (no row added, changed or removed) and matches the preview.
        Map<String, String> before = books();
        started(maker, MANAGE, base + "/dry-run", null);
        assertThat(jobEnded(run).get("state").asString()).isEqualTo("done");
        assertThat(books()).as("the dry run writes nothing").isEqualTo(before);
        JsonNode dry =
                send(maker, MANAGE, HttpMethod.GET, base + "/dry-run", null).getBody();
        assertThat(dry.get("failure").isNull()).as("%s", dry).isTrue();
        assertThat(dry.get("preview_matches").asBoolean()).as("%s", dry).isTrue();
        assertThat(dry.get("current").asBoolean()).isTrue();

        // Ready, and the dump recorded and checked from the operator portal.
        ResponseEntity<JsonNode> ready = send(maker, MANAGE, HttpMethod.POST, base + "/ready", null);
        assertThat(ready.getStatusCode()).as("%s", ready.getBody()).isEqualTo(HttpStatus.OK);
        Api platform = Api.platform(http);
        String email = Api.email("pilot-operator");
        String token = platform.signIn(Api.platformUser(email), email, Api.PASSWORD, null)
                .accessToken();
        String portal = "/api/v1/platform/imports/businesses/" + t.slug() + "/runs/" + run;
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("file", "pre-import-fabricated.dump");
        dump.put("taken_at", Instant.now().toString());
        dump.put("sha256", LendingLoansV1TemplateIT.SHA);
        ResponseEntity<JsonNode> recorded = platform.post(portal + "/dump", dump, token);
        assertThat(recorded.getStatusCode()).as("%s", recorded.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> checked = platform.post(
                portal + "/dump/check",
                Map.of("sha256", LendingLoansV1TemplateIT.SHA, "listed_entries", CommitApproval.LISTED),
                token);
        assertThat(checked.getStatusCode()).as("%s", checked.getBody()).isEqualTo(HttpStatus.OK);

        // Maker and checker: the portal cannot start a commit nobody approved, nor one only its maker approved.
        ResponseEntity<JsonNode> early = platform.post(portal + "/start", null, token);
        assertThat(early.getStatusCode()).as("%s", early.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(early.getBody().get("code").asString()).isEqualTo("commit_not_approved");
        assertThat(status(run)).isEqualTo("ready");
        ResponseEntity<JsonNode> asked = send(maker, MANAGE, HttpMethod.POST, base + "/commit-request", null);
        assertThat(asked.getStatusCode()).as("%s", asked.getBody()).isEqualTo(HttpStatus.OK);
        UUID approval =
                UUID.fromString(asked.getBody().get("approval_request_id").asString());
        ResponseEntity<JsonNode> own = CommitApproval.approve(http, t, approval, maker);
        assertThat(own.getStatusCode().is4xxClientError())
                .as("%s", own.getBody())
                .isTrue();
        assertThat(own.getBody().get("code").asString()).isEqualTo("self_approval_forbidden");
        assertThat(as(() -> runs.get(run).commitApproved())).isFalse();
        ResponseEntity<JsonNode> stillEarly = platform.post(portal + "/start", null, token);
        assertThat(stillEarly.getStatusCode()).as("%s", stillEarly.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stillEarly.getBody().get("code").asString()).isEqualTo("commit_not_approved");
        assertThat(status(run)).isEqualTo("ready");
        assertThat(count("SELECT count(*) FROM lending_loans WHERE tenant_id = ?"))
                .isZero();
        ResponseEntity<JsonNode> approved = CommitApproval.approve(http, t, approval, checker);
        assertThat(approved.getStatusCode()).as("%s", approved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(as(() -> runs.get(run).commitApproved())).isTrue();

        // The commit, started from the operator portal and run on in the background; a stop or a failure ends the wait.
        ResponseEntity<JsonNode> start = platform.post(portal + "/start", null, token);
        assertThat(start.getStatusCode()).as("%s", start.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        long until = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        JsonNode state = send(maker, MANAGE, HttpMethod.GET, base, null).getBody();
        while ("committing".equals(state.get("status").asString())
                && state.get("loop_stopped").isNull()
                && System.nanoTime() < until) {
            Thread.sleep(100);
            state = send(maker, MANAGE, HttpMethod.GET, base, null).getBody();
        }
        assertThat(state.get("status").asString()).as("%s", state).isEqualTo("committed");

        assertTheBooksOf1312();

        // The reconciliation ties, and says what 13.12 says: the records created and the principal of the one branch.
        JsonNode r = reconciliation(base);
        assertThat(r.get("signable").asBoolean()).as("%s", r).isTrue();
        assertThat(facts(r, "counts"))
                .as("%s", r)
                .containsEntry("Records created as lending.member", "6")
                .containsEntry("Records created as lending.next_of_kin", "6")
                .containsEntry("Records created as lending.collateral", "7")
                .containsEntry("Records created as lending.loan", "7");
        List<String> principal = new ArrayList<>();
        section(r, "loan_principal")
                .get("checks")
                .forEach(c -> principal.add(c.get("system").asString() + "|"
                        + c.get("unitemised").asString() + "|" + c.get("ties").asBoolean()));
        assertThat(principal).as("%s", r).containsExactly("2810000|0|true");
        List<String> sections = new ArrayList<>();
        r.get("sections").forEach(s -> sections.add(s.get("key").asString()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reconciliation_id", r.get("id").asString());
        body.put("sha256", r.get("sha256").asString());
        body.put("sections", sections);
        body.put("name", "Fabricated Signer");
        body.put(
                "date",
                as(() -> clock.today(currentTenant.profile().timezone())).toString());
        assertThat(started(signer, SIGN, base + "/sign-off", body)
                        .get("job")
                        .get("kind")
                        .asString())
                .isEqualTo("sign_off");
        JsonNode signed = signOffEnded(base);
        assertThat(signed.get("job").get("state").asString())
                .as("%s", signed.get("job"))
                .isEqualTo("done");
        assertThat(signed.get("signed").isNull()).isFalse();
        assertThat(status(run)).isEqualTo("signed_off");
    }

    /** Everything 13.12 says the commit creates, from the tables. */
    void assertTheBooksOf1312() {
        // 6 members: rows 3 and 12 are one borrower, with both loans.
        assertThat(count("SELECT count(*) FROM lending_members WHERE tenant_id = ? AND source = 'import'"))
                .isEqualTo(6);
        assertThat(count("""
                        SELECT count(*) FROM lending_loans l JOIN lending_members m ON m.id = l.member_id
                         WHERE l.tenant_id = ? AND m.national_id = 'CMTEST0000001A'
                        """)).isEqualTo(2);

        // 6 next of kin, 2 linked to members: row 4's kin is the borrower of row 3, row 11's that of row 5.
        assertThat(count("SELECT count(*) FROM lending_next_of_kin WHERE tenant_id = ?"))
                .isEqualTo(6);
        assertThat(lines("""
                        SELECT o.national_id || '|' || m.national_id || '|' || k.link_method || '|' || k.link_status
                          FROM lending_next_of_kin k JOIN lending_members o ON o.id = k.member_id
                          JOIN lending_members m ON m.id = k.linked_member_id
                         WHERE k.tenant_id = ?
                        """))
                .containsExactlyInAnyOrder(
                        "CMTEST0000002A|CMTEST0000001A|nin|confirmed", "CFTEST0000006A|CFTEST0000003A|nin|confirmed");

        // 7 loans, one bullet item each: principal, rate, disbursed, due, interest, status (row 6 at the accepted 1000
        // bp).
        assertThat(lines("""
                        SELECT l.requested_principal_minor || '|' || l.interest_rate_bp || '|' || l.disbursed_on || '|'
                               || s.due_date || '|' || s.interest_due_minor || '|' || l.status
                          FROM lending_loans l JOIN lending_schedule_items s ON s.loan_id = l.id
                         WHERE l.tenant_id = ? AND l.channel = 'import'
                        """))
                .containsExactlyInAnyOrder(
                        "500000|2000|2026-03-16|2026-04-16|100000|active",
                        "300000|2000|2026-03-05|2026-04-05|60000|active",
                        "200000|2000|2026-03-20|2026-04-03|40000|active",
                        "1000000|1000|2026-03-24|2026-04-24|100000|active",
                        "800000|2000|2026-04-14|2026-06-14|160000|active",
                        "250000|2000|2026-04-07|2026-05-07|50000|active",
                        "400000|2000|2026-04-20|2026-05-20|80000|closed");
        assertThat(lines("SELECT closed_on::text FROM lending_loans WHERE tenant_id = ? AND status = 'closed'"))
                .containsExactly("2026-05-18");

        // 7 collateral items; row 4's vehicle by its plate.
        assertThat(count("SELECT count(*) FROM lending_collateral_items WHERE tenant_id = ?"))
                .isEqualTo(7);
        assertThat(lines("""
                        SELECT reference_no_normalised FROM lending_collateral_items
                         WHERE tenant_id = ? AND collateral_type = 'vehicle'
                        """)).containsExactly("UXX001X");

        // 2 historic repayments with no journal; row 10's 400,000 to interest 160,000 then principal 240,000.
        assertThat(lines("""
                        SELECT amount_minor || '|' || value_date FROM lending_loan_transactions
                         WHERE tenant_id = ? AND txn_type = 'repayment' AND is_historic AND journal_entry_id IS NULL
                           AND source = 'import'
                        """)).containsExactlyInAnyOrder("400000|2026-05-14", "480000|2026-05-18");
        assertThat(
                        count(
                                "SELECT count(*) FROM lending_loan_transactions WHERE tenant_id = ? AND txn_type = 'repayment'"))
                .isEqualTo(2);
        assertThat(lines("""
                        SELECT a.component || '|' || a.amount_minor FROM lending_repayment_allocations a
                          JOIN lending_loan_transactions x ON x.id = a.transaction_id
                         WHERE a.tenant_id = ? AND x.amount_minor = 400000
                        """)).containsExactlyInAnyOrder("interest|160000", "principal|240000");

        // Principal outstanding and days past due as at 30 June 2026.
        assertThat(lines("""
                        SELECT principal_outstanding_minor || '|' || days_past_due FROM lending_loans
                         WHERE tenant_id = ? AND status = 'active'
                        """))
                .containsExactlyInAnyOrder(
                        "500000|75", "300000|86", "200000|88", "1000000|67", "560000|16", "250000|54");

        // One opening journal: loans_receivable 2,810,000 in 6 lines, each naming its loan; equity the total.
        assertThat(count("SELECT count(*) FROM journal_entries WHERE tenant_id = ?"))
                .isEqualTo(1);
        assertThat(lines("""
                        SELECT b.code || '|' || a.system_key || '|' || l.debit || '|' || l.credit FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id JOIN gl_accounts a ON a.id = l.account_id
                          JOIN branches b ON b.id = e.branch_id
                         WHERE l.tenant_id = ?
                        """))
                .containsExactlyInAnyOrder(
                        "HQ|loans_receivable|500000|0",
                        "HQ|loans_receivable|300000|0",
                        "HQ|loans_receivable|200000|0",
                        "HQ|loans_receivable|1000000|0",
                        "HQ|loans_receivable|560000|0",
                        "HQ|loans_receivable|250000|0",
                        "HQ|opening_balance_equity|0|2810000");
        assertThat(count("""
                        SELECT count(*) FROM journal_lines l WHERE l.tenant_id = ? AND l.debit > 0
                           AND (l.subledger_type <> 'lending.loan'
                                OR NOT EXISTS (SELECT 1 FROM lending_loans x WHERE x.id = l.subledger_id
                                                  AND x.principal_outstanding_minor = l.debit))
                        """)).as("each debit line names its open loan").isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // The routes, as the import screens call them.
    // ---------------------------------------------------------------------------------------------

    /** The register uploaded through the quarantine with the pilot mapping, the cutover at 30 June 2026; not checked. */
    UUID uploaded() throws Exception {
        Map<String, Object> document = JsonMapper.builder()
                .build()
                .readValue(Files.readString(LendingLoansV1TemplateIT.MAPPING), new TypeReference<>() {});
        byte[] bytes = Files.readAllBytes(LendingLoansV1TemplateIT.PILOT);
        // No route saves a mapping or sets the cutover on main: the mapping through the service, the cutover by the
        // owner.
        UUID mapping =
                as(() -> mappings.save("pilot-register", document, maker, null).id());
        ResponseEntity<JsonNode> opened = send(maker, MANAGE, HttpMethod.POST, "/api/v1/imports/runs", null);
        assertThat(opened.getStatusCode()).as("%s", opened.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID run = UUID.fromString(opened.getBody().get("id").asString());
        TestDatabase.owner()
                .sql("UPDATE import_runs SET cutover_at = ? WHERE id = ?")
                .params(Timestamp.from(LendingLoansV1TemplateIT.CUTOVER), run)
                .update();
        HttpHeaders h = headers(maker, MANAGE);
        h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<JsonNode> file = http.exchange(
                "/api/v1/imports/runs/" + run + "/files?file_name=pilot_loan_register_sample.csv&mapping_id=" + mapping
                        + "&source=register",
                HttpMethod.POST,
                new HttpEntity<>(bytes, h),
                JsonNode.class);
        assertThat(file.getStatusCode()).as("%s", file.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(file.getBody().get("status").asString()).isEqualTo("parsed");
        return run;
    }

    HttpHeaders headers(UUID user, String permissions) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", user.toString());
        h.add("X-Dev-Permissions", permissions);
        h.add("X-Dev-Branch-Ids", "*");
        return h;
    }

    ResponseEntity<JsonNode> send(UUID user, String permissions, HttpMethod method, String path, Object body) {
        HttpHeaders h = headers(user, permissions);
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    /** A background job started: 202, asked again while the job before it still gives back the business's claim. */
    JsonNode started(UUID user, String permissions, String path, Object body) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
        ResponseEntity<JsonNode> answer = send(user, permissions, HttpMethod.POST, path, body);
        while (answer.getStatusCode() == HttpStatus.CONFLICT
                && "IMPORT_IN_PROGRESS".equals(answer.getBody().get("code").asString())
                && System.nanoTime() < end) {
            Thread.sleep(100);
            answer = send(user, permissions, HttpMethod.POST, path, body);
        }
        assertThat(answer.getStatusCode()).as("%s %s", path, answer.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        return answer.getBody();
    }

    /** The run's job once it no longer runs, read from the run as the screen polls it. */
    JsonNode jobEnded(UUID run) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (System.nanoTime() < end) {
            JsonNode job = send(maker, MANAGE, HttpMethod.GET, "/api/v1/imports/runs/" + run, null)
                    .getBody()
                    .get("job");
            if (!"running".equals(job.get("state").asString())) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the job of run " + run + " still runs after two minutes");
    }

    /** The reconciliation the commit made as it finished. */
    JsonNode reconciliation(String base) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
        ResponseEntity<JsonNode> read = send(signer, SIGN, HttpMethod.GET, base + "/reconciliation", null);
        while (read.getStatusCode() != HttpStatus.OK && System.nanoTime() < end) {
            Thread.sleep(100);
            read = send(signer, SIGN, HttpMethod.GET, base + "/reconciliation", null);
        }
        assertThat(read.getStatusCode()).as("%s", read.getBody()).isEqualTo(HttpStatus.OK);
        return read.getBody();
    }

    /** The reconciliation once the sign-off job no longer runs. */
    JsonNode signOffEnded(String base) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        while (System.nanoTime() < end) {
            JsonNode read = reconciliation(base);
            if (!"running".equals(read.get("job").get("state").asString())) {
                return read;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the sign-off still runs after three minutes");
    }

    /**
     * The standard resolutions of 13.12 through {@code POST /issues/{id}/resolve}: a suggestion is accepted (rows 4, 6 and
     * 11), a blocking issue without one leaves its row out (rows 7, 9 and 13), any other warning is overridden with a note.
     */
    void settle(String base) {
        for (int pass = 0; pass < 100; pass++) {
            JsonNode items = send(maker, MANAGE, HttpMethod.GET, base + "/issues?status=open&limit=100", null)
                    .getBody()
                    .get("items");
            if (items.isEmpty()) {
                return;
            }
            JsonNode next = items.get(0);
            JsonNode suggestion = next.get("suggestion");
            boolean suggested = suggestion != null && !suggestion.isNull() && suggestion.has("value");
            String kind = suggested
                    ? "accept_suggestion"
                    : "blocking".equals(next.get("severity").asString()) ? "exclude_row" : "override";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("resolution", kind);
            body.put("version", next.get("version").asInt());
            body.put("note", "Fabricated review note");
            ResponseEntity<JsonNode> done = send(
                    maker,
                    MANAGE,
                    HttpMethod.POST,
                    "/api/v1/imports/issues/" + next.get("id").asString() + "/resolve",
                    body);
            assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        }
        throw new AssertionError("the review did not settle");
    }

    // ---------------------------------------------------------------------------------------------
    // The tables.
    // ---------------------------------------------------------------------------------------------

    /** The codes of each line as the check stored them, sorted, without the infos 13.12 leaves out of its table. */
    Map<Integer, String> codesByLine(UUID run) {
        Map<Integer, String> out = new TreeMap<>();
        TestDatabase.owner()
                .sql("""
                        SELECT i.source_row_no, string_agg(i.code, ',' ORDER BY i.code) FROM import_issues i
                          JOIN import_batches b ON b.id = i.batch_id
                         WHERE b.run_id = ? AND i.code NOT IN ('PHONE_NORMALISED', 'COLLATERAL_PARSED')
                         GROUP BY i.source_row_no
                        """)
                .param(run)
                .query((rs, n) -> out.put(rs.getInt(1), rs.getString(2)))
                .list();
        return out;
    }

    // JUSTIFICATION-A3: review of 6e055e5 on #926, points 3, 4 and 6; no test helper digests a table's rows or reads a
    // reconciliation's sections from the route.
    /** {@code line|code} of the run's issues that match {@code where} (on {@code i}, the issue). */
    List<String> onRun(UUID run, String where) {
        return TestDatabase.owner()
                .sql("SELECT i.source_row_no || '|' || i.code FROM import_issues i JOIN import_batches b"
                        + " ON b.id = i.batch_id WHERE b.run_id = ? AND " + where)
                .param(run)
                .query(String.class)
                .list();
    }

    /**
     * The books a dry run must leave as they were: an MD5 over every row of the business in each lending and ledger table,
     * so a row added, changed or removed shows, and the sequences.
     */
    Map<String, String> books() {
        Map<String, String> out = new TreeMap<>();
        List<String> tables = TestDatabase.owner().sql("""
                        SELECT table_name FROM information_schema.columns
                         WHERE table_schema = 'public' AND column_name = 'tenant_id'
                           AND (table_name LIKE 'lending\\_%' OR table_name LIKE 'journal\\_%')
                         ORDER BY 1
                        """).query(String.class).list();
        for (String table : tables) {
            out.put(
                    table,
                    TestDatabase.owner()
                            .sql(
                                    "SELECT count(*) || ':' || coalesce(md5(string_agg(x::text, ',' ORDER BY x::text)), '-')"
                                            + " FROM \"" + table + "\" x WHERE x.tenant_id = ?")
                            .param(t.tenantId())
                            .query(String.class)
                            .single());
        }
        TestDatabase.rowCounts(t.tenantId()).forEach((k, v) -> {
            if (k.startsWith("sequence ")) {
                out.put(k, String.valueOf(v));
            }
        });
        assertThat(out).containsKeys("lending_loans", "journal_entries", "journal_lines");
        return out;
    }

    /** One section of a reconciliation by its key. */
    static JsonNode section(JsonNode reconciliation, String key) {
        for (JsonNode s : reconciliation.get("sections")) {
            if (key.equals(s.get("key").asString())) {
                return s;
            }
        }
        throw new AssertionError("no section " + key + " in " + reconciliation);
    }

    /** The facts of one section, label to value. */
    static Map<String, String> facts(JsonNode reconciliation, String key) {
        Map<String, String> out = new LinkedHashMap<>();
        section(reconciliation, key)
                .get("facts")
                .forEach(f -> out.put(f.get("label").asString(), f.get("value").asString()));
        return out;
    }

    static String status(UUID run) {
        return TestDatabase.owner()
                .sql("SELECT status FROM import_runs WHERE id = ?")
                .param(run)
                .query(String.class)
                .single();
    }

    List<String> lines(String sql) {
        return LendingLoansV1TemplateIT.lines(t, sql);
    }

    long count(String sql) {
        return LendingLoansV1TemplateIT.count(t, sql);
    }
}
