package org.myorg.dispatchpay.customer;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.myorg.dispatchpay.audit.AuditChange;
import org.myorg.dispatchpay.audit.AuditEvent;
import org.myorg.dispatchpay.audit.AuditWriter;
import org.myorg.dispatchpay.customer.CustomerRepository.CustomerRow;
import org.myorg.dispatchpay.customer.CustomerRepository.Fields;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.iam.access.Permission;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.myorg.dispatchpay.platform.pagination.PageResponse;
import org.myorg.dispatchpay.platform.search.SearchText;
import org.myorg.dispatchpay.platform.web.PatchRequest.Change;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The business's customers. Reading needs {@link Permission#CUSTOMER_READ}; changes need
 * {@link Permission#CUSTOMER_MANAGE}, are versioned, attributed to the member and audited.
 *
 * <p>Payment terms and credit limit are receivables data: they are only shown to, and can only be
 * set by, holders of {@link Permission#CUSTOMER_RECEIVABLES_READ}. Similar or identical customer
 * names are never merged: every customer stays separate and aliases are approved explicitly.
 */
@Service
public class CustomerService {

    private static final String RESOURCE = "customer";

    public record CustomerSummary(
            UUID id,
            String username,
            String name,
            String contactPerson,
            String phone,
            List<String> matchedAliases) {
    }

    /**
     * @param creditLimit in paise; {@code null} when no limit has been set
     */
    public record Credit(Integer paymentTermsDays, Long creditLimit) {
    }

    public record AliasView(UUID id, String alias, UUID approvedByMembershipId, Instant approvedAt) {
    }

    /** @param credit {@code null} unless the caller holds {@link Permission#CUSTOMER_RECEIVABLES_READ} */
    public record CustomerDetail(
            UUID id,
            String username,
            String name,
            String contactPerson,
            String phone,
            String address,
            Credit credit,
            List<AliasView> aliases,
            UUID createdByMembershipId,
            UUID updatedByMembershipId,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    public record NewCustomer(
            String username,
            String name,
            String contactPerson,
            String phone,
            String address,
            Integer paymentTermsDays,
            Long creditLimit) {
    }

    /**
     * Fields to change; a {@code null} field is left as it is, and a {@link Change} with a null value
     * clears an optional field. Username and name cannot be cleared.
     */
    public record CustomerChanges(
            String username,
            String name,
            Change<String> contactPerson,
            Change<String> phone,
            Change<String> address,
            Change<Integer> paymentTermsDays,
            Change<Long> creditLimit) {
    }

    private final CustomerRepository customers;
    private final AuditWriter audit;
    private final Clock clock;

    public CustomerService(CustomerRepository customers, AuditWriter audit, Clock clock) {
        this.customers = customers;
        this.audit = audit;
        this.clock = clock;
    }

    // --- M03.S05 search ---------------------------------------------------------------------------

    /**
     * Customers of the selected business matching {@code query} by username, name, phone or approved
     * alias, exact matches first. Every plausible match is returned; none is picked silently.
     */
    @Transactional(readOnly = true)
    public PageResponse<CustomerSummary> search(BusinessAccess access, String query, PageQuery page) {
        access.require(Permission.CUSTOMER_READ);
        String normalized = query == null || query.isBlank() ? null : SearchText.normalize(query);
        String pattern = normalized == null ? null : SearchText.containsPattern(normalized);
        List<CustomerRow> rows = customers.search(access.businessId(), normalized, pattern, page.offset(),
                page.size());
        Map<UUID, List<String>> aliases = customers.matchingAliases(access.businessId(),
                rows.stream().map(CustomerRow::id).toList(), pattern);
        List<CustomerSummary> items = rows.stream()
                .map(row -> new CustomerSummary(row.id(), row.fields().username(), row.fields().name(),
                        row.fields().contactPerson(), row.fields().phone(), aliases.getOrDefault(row.id(), List.of())))
                .toList();
        return PageResponse.of(items, page, customers.count(access.businessId(), pattern));
    }

    @Transactional(readOnly = true)
    public CustomerDetail get(BusinessAccess access, UUID id) {
        access.require(Permission.CUSTOMER_READ);
        CustomerRow row = customers.find(access.businessId(), id).orElseThrow(CustomerService::notFound);
        return detail(access, row);
    }

    // --- M03.S06 create and edit ------------------------------------------------------------------

    /** Runs inside the idempotent command's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CustomerDetail create(BusinessAccess access, NewCustomer customer) {
        access.require(Permission.CUSTOMER_MANAGE);
        if (customer.paymentTermsDays() != null || customer.creditLimit() != null) {
            access.require(Permission.CUSTOMER_RECEIVABLES_READ);
        }
        UUID id = UUID.randomUUID();
        Fields fields = new Fields(customer.username(), customer.name(), customer.contactPerson(), customer.phone(),
                customer.address(), customer.paymentTermsDays(), customer.creditLimit());
        requireUniqueUsername(access.businessId(), fields.username(), id);
        try {
            customers.insert(id, access.businessId(), fields, access.membershipId(), clock.instant());
        } catch (DuplicateKeyException concurrentCreate) {
            throw usernameConflict();
        }
        audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "customer.create", RESOURCE, id,
                diff(new Fields(null, null, null, null, null, null, null), fields)));
        return reload(access, id);
    }

    /**
     * Edits a customer last read at {@code expectedVersion}; a stale version conflicts. Runs inside
     * the idempotent command's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CustomerDetail update(BusinessAccess access, UUID id, CustomerChanges requested, long expectedVersion) {
        access.require(Permission.CUSTOMER_MANAGE);
        if (requested.paymentTermsDays() != null || requested.creditLimit() != null) {
            access.require(Permission.CUSTOMER_RECEIVABLES_READ);
        }
        CustomerRow customer = customers.lock(access.businessId(), id).orElseThrow(CustomerService::notFound);
        if (customer.version() != expectedVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "This customer was changed by another request; reload and retry");
        }
        Fields before = customer.fields();
        Fields after = new Fields(
                requested.username() == null ? before.username() : requested.username(),
                requested.name() == null ? before.name() : requested.name(),
                apply(requested.contactPerson(), before.contactPerson()),
                apply(requested.phone(), before.phone()),
                apply(requested.address(), before.address()),
                apply(requested.paymentTermsDays(), before.paymentTermsDays()),
                apply(requested.creditLimit(), before.creditLimit()));
        Map<String, AuditChange> changes = diff(before, after);
        if (changes.isEmpty()) {
            return reload(access, id);
        }
        if (changes.containsKey("username")) {
            requireUniqueUsername(access.businessId(), after.username(), id);
        }
        try {
            customers.update(id, after, access.membershipId(), clock.instant());
        } catch (DuplicateKeyException concurrentRename) {
            throw usernameConflict();
        }
        audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "customer.update", RESOURCE, id,
                changes));
        return reload(access, id);
    }

    // --- M03.S07 aliases --------------------------------------------------------------------------

    /**
     * Adds an alias, explicitly approved by the caller. The same alias may be approved for several
     * customers; search then returns all of them. Runs inside the idempotent command's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CustomerDetail addAlias(BusinessAccess access, UUID id, String alias) {
        access.require(Permission.CUSTOMER_MANAGE);
        customers.lock(access.businessId(), id).orElseThrow(CustomerService::notFound);
        String normalized = SearchText.normalize(alias);
        if (customers.aliasExists(access.businessId(), id, normalized)) {
            throw new ApiException(ErrorCode.CONFLICT, "This customer already has this alias");
        }
        customers.insertAlias(UUID.randomUUID(), access.businessId(), id, alias, normalized, access.membershipId(),
                clock.instant());
        audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "customer.alias.add", RESOURCE,
                id, Map.of("alias", new AuditChange(null, alias))));
        return reload(access, id);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private CustomerDetail reload(BusinessAccess access, UUID id) {
        return detail(access, customers.find(access.businessId(), id).orElseThrow(CustomerService::notFound));
    }

    private CustomerDetail detail(BusinessAccess access, CustomerRow row) {
        Fields fields = row.fields();
        Credit credit = access.has(Permission.CUSTOMER_RECEIVABLES_READ)
                ? new Credit(fields.paymentTermsDays(), fields.creditLimit())
                : null;
        List<AliasView> aliases = customers.findAliases(access.businessId(), row.id()).stream()
                .map(alias -> new AliasView(alias.id(), alias.alias(), alias.approvedByMembershipId(),
                        alias.approvedAt()))
                .toList();
        return new CustomerDetail(row.id(), fields.username(), fields.name(), fields.contactPerson(), fields.phone(),
                fields.address(), credit, aliases, row.createdByMembershipId(), row.updatedByMembershipId(),
                row.createdAt(), row.updatedAt(), row.version());
    }

    private static <T> T apply(Change<T> change, T current) {
        return change == null ? current : change.value();
    }

    /** The fields that differ, in a stable order, for the audit diff. */
    private static Map<String, AuditChange> diff(Fields before, Fields after) {
        Map<String, AuditChange> changes = new LinkedHashMap<>();
        put(changes, "username", before.username(), after.username());
        put(changes, "name", before.name(), after.name());
        put(changes, "contactPerson", before.contactPerson(), after.contactPerson());
        put(changes, "phone", before.phone(), after.phone());
        put(changes, "address", before.address(), after.address());
        put(changes, "paymentTermsDays", before.paymentTermsDays(), after.paymentTermsDays());
        put(changes, "creditLimit", before.creditLimit(), after.creditLimit());
        return changes;
    }

    private static void put(Map<String, AuditChange> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.put(field, new AuditChange(before, after));
        }
    }

    private void requireUniqueUsername(UUID businessId, String username, UUID exceptId) {
        if (customers.usernameTaken(businessId, username, exceptId)) {
            throw usernameConflict();
        }
    }

    private static ApiException usernameConflict() {
        return new ApiException(ErrorCode.CONFLICT, "A customer with this username already exists");
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Customer not found");
    }
}
