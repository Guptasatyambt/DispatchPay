package org.myorg.dispatchpay;

import org.junit.jupiter.api.BeforeEach;
import org.myorg.dispatchpay.support.CapturingInvitationSender;
import org.myorg.dispatchpay.support.CapturingOtpSender;
import org.myorg.dispatchpay.support.RecordingEventHandler;
import org.myorg.dispatchpay.support.WidgetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Full application against a shared PostgreSQL container; background schedulers are off. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected RecordingEventHandler recordingHandler;

    @Autowired
    protected WidgetService widgetService;

    @Autowired
    protected CapturingOtpSender otpSender;

    @Autowired
    protected CapturingInvitationSender invitationSender;

    @BeforeEach
    void resetState() {
        jdbc.sql("""
                TRUNCATE idempotency_record, outbox_event, processed_event, rate_limit_bucket,
                         otp_challenge, refresh_token, auth_session, audit_event, worker_invitation,
                         membership_permission, membership_godown, godown, product_unit_conversion,
                         dispatch_line, dispatch, sales_order_revision, sales_order_cancellation_line,
                         sales_order_cancellation, sales_order_charge, sales_order_line, sales_order,
                         sales_order_number, stock_discrepancy, stock_movement, stock_balance, stock_document,
                         product_alias, product, customer_alias, customer, import_row, import_job, business,
                         business_membership, user_account
                """).update();
        recordingHandler.reset();
        widgetService.reset();
        otpSender.reset();
        invitationSender.reset();
    }

    protected long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
