package com.rincoltech.bms.retail.catalogue.internal;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * An import whose rows clash with a row added after the check read the catalogue is a 409
 * {@code import_conflict}, and nothing from the file is added (#146). Fabricated rows.
 */
class RetailProductImportConflictIT extends IntegrationTest {

    static final String ADMINISTRATOR = ADMIN + ",core.settings.manage";

    @Autowired
    TestRestTemplate http;

    @MockitoSpyBean
    CatalogueRepository repo;

    TestDatabase.Fixture t;
    RetailTestSupport api;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-impc", false, true);
        api = new RetailTestSupport(http, t);
    }

    long count(String table) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM " + table + " WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    @Test
    void aNameAddedAfterTheReadIsA409AndNothingIsAdded() {
        // Issue #562: the import uses no typed code on a new row, so the race it guards is a name, held once per unit.
        api.product("IMP-CLASH", 100, 200);
        long products = count("retail_products");
        long categories = count("retail_categories");
        // The import reads the names before the clashing item is committed: it sees none.
        doReturn(Map.of()).when(repo).productNames();

        ResponseEntity<JsonNode> r = api.post(
                "/products/import?dry_run=false",
                Map.of(
                        "csv",
                        "code,description,category,unit,sell price\n"
                                + "IMP-NEW,Test new,Test New Category,roll,100\n"
                                + ",Test Product IMP-CLASH,Test New Category,u-imp-clash,100\n"),
                ADMINISTRATOR);

        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("import_conflict");
        assertThat(count("retail_products")).isEqualTo(products);
        assertThat(count("retail_categories")).isEqualTo(categories);
    }
}
