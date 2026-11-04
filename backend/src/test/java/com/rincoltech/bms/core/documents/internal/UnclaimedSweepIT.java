package com.rincoltech.bms.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.retail.cashbook.CashbookTestSupport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

/**
 * Issue #819 (ADR-124, the lead's decision on the review of #846): a receipt photo sent before its expense that no expense
 * names within 24 hours has its file deleted by the nightly sweep, audited; one an expense names, and one sent in the last
 * 24 hours, keep theirs. Every file, name and amount is fabricated.
 */
class UnclaimedSweepIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    UnclaimedSweep sweep;

    @Autowired
    ObjectStorage storage;

    @Autowired
    BusinessClock clock;

    static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    String send(RetailTestSupport as) throws IOException {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(png()) {
            @Override
            public String getFilename() {
                return "receipt.png";
            }
        });
        HttpHeaders h = as.headers("retail.expense.record", "*", Map.of());
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<JsonNode> r = http.exchange(
                RetailTestSupport.BASE + "/expenses/receipt-photos",
                HttpMethod.POST,
                new HttpEntity<>(form, h),
                JsonNode.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("photo_id").asString();
    }

    static String key(String documentId) {
        return TestDatabase.owner()
                .sql("SELECT object_key FROM documents WHERE id = ?::uuid")
                .param(documentId)
                .query(String.class)
                .single();
    }

    void backdate(String documentId, Duration by) {
        TestDatabase.owner()
                .sql("UPDATE documents SET created_at = ? WHERE id = ?::uuid")
                .params(Timestamp.from(clock.now().minus(by)), documentId)
                .update();
    }

    @Test
    void frRet89APhotoNoExpenseNamesIn24HoursLosesItsFileAndANamedOrFreshOneKeepsIt() throws IOException {
        TestDatabase.Fixture t = TestDatabase.tenant("unclaimed", false, true);
        CashbookTestSupport cb = new CashbookTestSupport(http, t);
        String unclaimed = send(cb.api);
        String named = send(cb.api);
        String fresh = send(cb.api);

        UUID[] item = cb.categoryWithItem("Test Transport", "Test Boda", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("category_id", item[0]);
        body.put("item_id", item[1]);
        body.put("amount_minor", 2_000);
        body.put("receipt_document_id", named);
        assertThat(cb.post("/expenses", body, CashbookTestSupport.CASHBOOK_ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        backdate(unclaimed, Duration.ofHours(25));
        backdate(named, Duration.ofHours(25));

        int removed = TenantContext.callAs(t.tenantId(), sweep::sweep);

        assertThat(removed).isEqualTo(1);
        assertThat(storage.get(key(unclaimed))).isEmpty();
        assertThat(storage.get(key(named))).isPresent();
        assertThat(storage.get(key(fresh))).isPresent();
        // The row stays, as every document's does, and the removal is audited.
        assertThat(cb.auditPayloads("core.document.unclaimed_removed")).hasSize(1);
        // A second run finds the file gone and removes nothing more.
        assertThat(TenantContext.callAs(t.tenantId(), sweep::sweep)).isZero();
    }
}
