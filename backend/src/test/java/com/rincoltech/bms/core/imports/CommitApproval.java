package com.rincoltech.bms.core.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The steps a run takes between {@code ready} and its commit since issue #133, for the integration tests: the dump's check,
 * the maker's request of {@code import_commit} as a fabricated staff user with {@code core.imports.manage}, and the approval
 * by a different fabricated user with {@code core.imports.approve_commit} through the approvals route, as a checker would.
 * No real person: every id is random.
 */
public final class CommitApproval {

    /** The number of entries a fabricated {@code pg_restore --list} shows. */
    public static final int LISTED = 42;

    private CommitApproval() {}

    /** Records the check of the dump already recorded on the run, with the SHA-256 it was recorded with. */
    public static ImportRuns.Run verifyDump(TestDatabase.Fixture t, ImportRuns runs, UUID runId, String sha256) {
        return TenantContext.callAs(t.tenantId(), () -> runs.verifyPreImportDump(runId, sha256, LISTED));
    }

    /** The maker's request of import_commit, as a staff user holding {@code core.imports.manage} in every branch. */
    public static UUID request(TestDatabase.Fixture t, ImportRuns runs, UUID runId, UUID maker) {
        return TenantContext.callAs(t.tenantId(), () -> {
            CurrentPrincipal.set(Principal.uniform(maker, "staff", Set.of("core.imports.manage"), true, Set.of()));
            try {
                return runs.requestCommit(runId);
            } finally {
                CurrentPrincipal.clear();
            }
        });
    }

    /** The checker's decision through {@code POST /approvals/{id}/approve}, with the development principal headers. */
    public static ResponseEntity<JsonNode> approve(
            TestRestTemplate http, TestDatabase.Fixture t, UUID approvalId, UUID checker) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", checker.toString());
        h.add("X-Dev-Permissions", "core.approvals.read,core.imports.approve_commit");
        h.add("X-Dev-Branch-Ids", "*");
        return http.exchange(
                "/api/v1/approvals/" + approvalId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "Checked the fabricated dry run"), h),
                JsonNode.class);
    }

    /** Request by one fabricated user and approval by another; the run is then approved for commit as it is now. */
    public static UUID approved(TestRestTemplate http, TestDatabase.Fixture t, ImportRuns runs, UUID runId) {
        UUID approval = request(t, runs, runId, UUID.randomUUID());
        ResponseEntity<JsonNode> decided = approve(http, t, approval, UUID.randomUUID());
        assertThat(decided.getStatusCode()).as("%s", decided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(TenantContext.callAs(t.tenantId(), () -> runs.get(runId).commitApproved()))
                .isTrue();
        return approval;
    }

    /** Checks the dump and has the commit approved: what every commit of a ready run needs since issue #133. */
    public static void readyToCommit(
            TestRestTemplate http, TestDatabase.Fixture t, ImportRuns runs, UUID runId, String sha256) {
        verifyDump(t, runs, runId, sha256);
        approved(http, t, runs, runId);
    }
}
