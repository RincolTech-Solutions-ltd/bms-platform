package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.NewItem;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.ProductSnapshot;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.internal.StockApi.QuickAdd;
import com.rincoltech.bms.retail.stock.internal.StockApi.QuickAddRequest;
import com.rincoltech.bms.retail.stock.internal.StockApi.Stocktake;
import com.rincoltech.bms.retail.stock.internal.StockApi.StocktakeLineRequest;
import com.rincoltech.bms.retail.stock.internal.StockApi.StocktakeRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quick add (issue #632): a shop owner needed nine steps on four screens to add one item and its stock. One request makes the
 * item through the catalogue's own rules (the code from the category, the name check, General and Piece made when missing)
 * and records how many there are as a committed stock count of that one line, in one transaction: the count only ever names
 * this item, so no other item's stock is touched, and its difference posts as any count's does. An item already in the list
 * (chosen instead of a duplicate) gets only the count.
 */
@Service
class QuickAddService {

    static final String PATH = "/api/v1/retail/quick-add";
    static final String COUNT = "retail.stocktake.commit";
    static final String NOTE = "Quick add";

    private final RetailCatalogue catalogue;
    private final StockService stock;
    private final RetailBranchContext branches;
    private final Idempotency idempotency;

    QuickAddService(
            RetailCatalogue catalogue, StockService stock, RetailBranchContext branches, Idempotency idempotency) {
        this.catalogue = catalogue;
        this.stock = stock;
        this.branches = branches;
        this.idempotency = idempotency;
    }

    @Transactional
    Outcome<QuickAdd> add(String idempotencyKey, QuickAddRequest r) {
        return idempotency.once(idempotencyKey, "POST", PATH, r, QuickAdd.class, () -> addNow(r));
    }

    private QuickAdd addNow(QuickAddRequest r) {
        List<FieldProblem> problems = new ArrayList<>();
        if (r.productId() == null) {
            if (r.description() == null || r.description().isBlank()) {
                problems.add(new FieldProblem("description", "required", "Type the item's name."));
            }
            if (r.sellMinor() == null) {
                problems.add(new FieldProblem("sell_minor", "required", "Type the selling price."));
            }
        } else if (r.qty() == null) {
            problems.add(new FieldProblem("qty", "required", "Type how many you have here now."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        // The branch first: someone who may not count stock there is refused before anything is made.
        UUID branch = r.qty() == null ? null : branches.resolveStocking(COUNT, r.branchId());
        ProductSnapshot product;
        boolean categoryMade = false;
        boolean unitMade = false;
        if (r.productId() != null) {
            product = catalogue
                    .find(r.productId())
                    .orElseThrow(() -> ApiException.validation(
                            List.of(new FieldProblem("product_id", "unknown_product", "No such item."))));
        } else {
            NewItem made = catalogue.createItem(
                    r.description().trim(),
                    r.categoryId(),
                    r.unitId(),
                    r.costMinor() == null ? 0 : r.costMinor(),
                    r.sellMinor(),
                    r.duplicateReason());
            product = made.product();
            categoryMade = made.categoryMade();
            unitMade = made.unitMade();
        }
        UUID stocktakeId = null;
        if (branch != null) {
            Stocktake count = stock.createStocktake(
                    new StocktakeRequest(branch, List.of(new StocktakeLineRequest(product.id(), r.qty())), NOTE));
            stock.commit(count.id());
            stocktakeId = count.id();
        }
        return new QuickAdd(
                product.id(),
                product.code(),
                product.description(),
                product.unit(),
                r.productId() == null,
                categoryMade,
                unitMade,
                branch,
                r.qty() == null ? null : r.qty().stripTrailingZeros().toPlainString(),
                stocktakeId);
    }
}
