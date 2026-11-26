package org.myorg.dispatchpay.imports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.myorg.dispatchpay.customer.CustomerRules;
import org.myorg.dispatchpay.customer.CustomerService;
import org.myorg.dispatchpay.customer.CustomerService.NewCustomer;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.iam.access.Permission;
import org.springframework.stereotype.Component;

/**
 * Customer rows: {@code username, name} required; {@code contactPerson, phone, address,
 * paymentTermsDays, creditLimit} (paise) and {@code aliases} ({@code a|b}) optional. Terms and credit
 * need {@link Permission#CUSTOMER_RECEIVABLES_READ}, as in the API. Imports only create customers: a
 * username that already exists, or appears twice in the file, is an error. Names are never matched
 * or merged.
 */
@Component
class CustomerRows implements RowRules {

    /**
     * @param username the row's username when that cell is valid, so duplicates are found whatever else
     *                 is wrong
     * @param customer the whole row, only when every cell is valid
     */
    private record Parsed(String username, NewCustomer customer, List<String> aliases) {
    }

    private final CustomerService customers;

    CustomerRows(CustomerService customers) {
        this.customers = customers;
    }

    @Override
    public ImportType type() {
        return ImportType.CUSTOMERS;
    }

    @Override
    public List<List<RowError>> validate(BusinessAccess access, List<Row> rows) {
        List<RowReader> readers = new ArrayList<>();
        List<Parsed> parsed = new ArrayList<>();
        for (Row row : rows) {
            RowReader reader = new RowReader(row.values());
            readers.add(reader);
            parsed.add(parse(reader, access));
        }

        List<String> usernames = parsed.stream().map(Parsed::username).filter(Objects::nonNull).toList();
        Set<String> taken = customers.takenUsernames(access.businessId(), usernames);
        Map<String, Integer> firstRow = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String username = parsed.get(i).username();
            if (username == null) {
                continue;
            }
            Integer first = firstRow.putIfAbsent(username, rows.get(i).number());
            if (first != null) {
                readers.get(i).error("username", RowError.DUPLICATE_IN_FILE, "Same username as row " + first);
            } else if (taken.contains(username)) {
                readers.get(i).error("username", RowError.ALREADY_EXISTS,
                        "A customer with this username already exists");
            }
        }
        return readers.stream().map(RowReader::errors).toList();
    }

    @Override
    public List<UUID> commit(BusinessAccess access, List<Row> rows) {
        List<UUID> ids = new ArrayList<>();
        for (Row row : rows) {
            Parsed parsed = parse(new RowReader(row.values()), access);
            if (parsed.customer() == null) {
                throw new IllegalStateException("Row " + row.number() + " is not valid");
            }
            ids.add(customers.createImported(access.businessId(), access.membershipId(), parsed.customer(),
                    parsed.aliases()));
        }
        return ids;
    }

    /** What a row describes; invalid cells are recorded on the reader. */
    private static Parsed parse(RowReader row, BusinessAccess access) {
        String username = row.required("username", 60);
        if (username != null) {
            username = CustomerRules.username(username);
            if (username == null) {
                row.error("username", RowError.INVALID_FORMAT, CustomerRules.USERNAME_MESSAGE);
            }
        }
        String name = row.required("name", CustomerRules.NAME_MAX);
        String contactPerson = row.optional("contactPerson", CustomerRules.CONTACT_MAX);
        String phone = row.optional("phone", CustomerRules.PHONE_MAX);
        if (phone != null && !CustomerRules.PHONE_PATTERN.matcher(phone).matches()) {
            row.error("phone", RowError.INVALID_FORMAT, CustomerRules.PHONE_MESSAGE);
        }
        String address = row.optional("address", CustomerRules.ADDRESS_MAX);
        Long terms = receivable(row, access, "paymentTermsDays", CustomerRules.TERMS_MAX);
        Long creditLimit = receivable(row, access, "creditLimit", CustomerRules.CREDIT_LIMIT_MAX);
        List<String> aliases = row.aliases("aliases", CustomerRules.ALIAS_MAX);
        if (!row.valid()) {
            return new Parsed(username, null, aliases);
        }
        return new Parsed(username, new NewCustomer(username, name, contactPerson, phone, address,
                terms == null ? null : terms.intValue(), creditLimit), aliases);
    }

    private static Long receivable(RowReader row, BusinessAccess access, String column, long max) {
        Long value = row.wholeNumber(column, 0, max);
        if (row.text(column) != null && !access.has(Permission.CUSTOMER_RECEIVABLES_READ)) {
            row.error(column, RowError.PERMISSION_REQUIRED,
                    "Setting " + column + " needs the " + Permission.CUSTOMER_RECEIVABLES_READ + " permission");
            return null;
        }
        return value;
    }
}
