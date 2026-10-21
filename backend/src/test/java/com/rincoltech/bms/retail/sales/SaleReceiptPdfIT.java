package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Issue #439, ADR-061: the receipt PDF a seller shares from their own phone. It is the sale's receipt rendered now and
 * never stored, so it needs neither the documents capability nor {@code retail.document.issue}; a seller who sees only
 * their own figures (ADR-055) gets only their own sales' receipts, even of another seller's credit sale they may read to
 * take a payment; a storekeeper gets none; a supervisor and an administrator get every seller's. Names and amounts are
 * fabricated.
 */
class SaleReceiptPdfIT extends IntegrationTest {

    static final String STOREKEEPER =
            "retail.stock.read,retail.stock.transfer,retail.stocktake.commit,retail.usage.report";
    static final String SUPERVISOR = "retail.sale.read,retail.sale.all_sellers,retail.stock.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport admin;
    RetailTestSupport sellerA;
    RetailTestSupport sellerB;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-receipt", false, true);
        admin = new RetailTestSupport(http, t);
        sellerA = new RetailTestSupport(http, t);
        sellerB = new RetailTestSupport(http, t);
        product = admin.product("RCPT-1", 3_000, 5_000);
        admin.stockUp(t.headOffice(), product, "50");
    }

    UUID sellAs(RetailTestSupport seller, String method) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", method);
        if (method.equals("credit")) {
            body.put("buyer_name", "Test Buyer 01");
        }
        body.put("lines", List.of(Map.of("product_id", product, "qty", "2")));
        return RetailTestSupport.id(
                seller.postKeyed("/sales", body, SALES, "*", UUID.randomUUID().toString()));
    }

    ResponseEntity<byte[]> receipt(RetailTestSupport who, UUID sale, String permissions) {
        return http.exchange(
                RetailTestSupport.BASE + "/sales/" + sale + "/receipt/pdf",
                HttpMethod.GET,
                new HttpEntity<>(who.headers(permissions, "*", Map.of())),
                byte[].class);
    }

    static void isPdf(ResponseEntity<byte[]> r) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(r.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(new String(r.getBody(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    long documents() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM retail_documents WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    @Test
    void aSellerGetsTheReceiptOfTheirOwnSaleWithTheDocumentsCapabilityOffAndNothingIsStored() throws Exception {
        UUID sale = sellAs(sellerA, "cash");
        ResponseEntity<byte[]> r = receipt(sellerA, sale, SALES);
        isPdf(r);
        String text = RetailDocumentsIT.text(r.getBody());
        assertThat(text).contains("RECEIPT").contains("Paid in full by Cash.").contains("10,000");
        assertThat(documents()).isZero();
    }

    @Test
    void aSellerCannotFetchAnotherSellersReceiptNotEvenOfACreditSaleTheyMayRead() {
        UUID cash = sellAs(sellerA, "cash");
        UUID credit = sellAs(sellerA, "credit");
        assertThat(receipt(sellerB, cash, SALES).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // The sale itself is readable to take a payment on (ADR-055); its receipt is not.
        assertThat(sellerB.get("/sales/" + credit, SALES).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(receipt(sellerB, credit, SALES).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aStorekeeperCannotFetchAReceiptAtAll() {
        UUID sale = sellAs(sellerA, "cash");
        ResponseEntity<byte[]> r = receipt(sellerB, sale, STOREKEEPER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(new String(r.getBody(), StandardCharsets.UTF_8)).contains("permission_denied");
    }

    @Test
    void aSupervisorAndAnAdministratorGetEverySellersReceipt() {
        UUID sale = sellAs(sellerA, "cash");
        isPdf(receipt(sellerB, sale, SUPERVISOR));
        isPdf(receipt(admin, sale, ADMIN));
    }

    @Test
    void aVoidedSaleHasNoReceipt() {
        UUID sale = sellAs(sellerA, "cash");
        assertThat(admin.post("/sales/" + sale + "/void", Map.of("reason", "Wrong item sold"), ADMIN)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        ResponseEntity<byte[]> r = receipt(sellerA, sale, SALES);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(new String(r.getBody(), StandardCharsets.UTF_8)).contains("sale_voided");
    }
}
