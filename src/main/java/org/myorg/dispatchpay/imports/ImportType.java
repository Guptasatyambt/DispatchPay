package org.myorg.dispatchpay.imports;

import java.util.List;
import java.util.Set;

import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.iam.access.Permission;

/**
 * What a CSV file imports, its columns, and the permission it needs on top of
 * {@link Permission#IMPORT_MANAGE}.
 */
public enum ImportType {

    PRODUCTS(Permission.PRODUCT_MANAGE, true,
            List.of("sku", "name", "baseUnit", "brand", "packSize", "price", "aliases", "conversions"),
            Set.of("sku", "name", "baseUnit")),

    CUSTOMERS(Permission.CUSTOMER_MANAGE, true,
            List.of("username", "name", "contactPerson", "phone", "address", "paymentTermsDays", "creditLimit",
                    "aliases"),
            Set.of("username", "name")),

    /** Validated now; committing needs the stock ledger, which does not exist yet. */
    OPENING_STOCK(Permission.STOCK_INWARD, false,
            List.of("sku", "godown", "quantity", "unit"),
            Set.of("sku", "godown", "quantity"));

    private final Permission permission;
    private final boolean committable;
    private final List<String> columns;
    private final Set<String> requiredColumns;

    ImportType(Permission permission, boolean committable, List<String> columns, Set<String> requiredColumns) {
        this.permission = permission;
        this.committable = committable;
        this.columns = columns;
        this.requiredColumns = requiredColumns;
    }

    /** Every column the file may have, in the documented order. */
    public List<String> columns() {
        return columns;
    }

    public Set<String> requiredColumns() {
        return requiredColumns;
    }

    public boolean committable() {
        return committable;
    }

    public void requireAccess(BusinessAccess access) {
        access.require(Permission.IMPORT_MANAGE);
        access.require(permission);
    }

    public boolean allows(BusinessAccess access) {
        return access.has(Permission.IMPORT_MANAGE) && access.has(permission);
    }
}
