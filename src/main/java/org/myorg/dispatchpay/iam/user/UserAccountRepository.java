package org.myorg.dispatchpay.iam.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class UserAccountRepository {

    private final JdbcClient jdbc;
    private final Clock clock;

    public UserAccountRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Returns the account for a verified mobile, creating it on first sign-in (race-safe). */
    @Transactional(propagation = Propagation.MANDATORY)
    public UserAccount findOrCreateByMobile(String mobile) {
        Instant now = clock.instant();
        jdbc.sql("""
                        INSERT INTO user_account (id, mobile, status, created_at, updated_at)
                        VALUES (:id, :mobile, 'ACTIVE', :now, :now)
                        ON CONFLICT (mobile) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("mobile", mobile)
                .param("now", Timestamp.from(now))
                .update();
        return findByMobile(mobile).orElseThrow();
    }

    public Optional<UserAccount> findByMobile(String mobile) {
        return jdbc.sql("SELECT id, mobile, status FROM user_account WHERE mobile = :mobile")
                .param("mobile", mobile)
                .query(UserAccountRepository::map)
                .optional();
    }

    public Optional<UserAccount> findById(UUID id) {
        return jdbc.sql("SELECT id, mobile, status FROM user_account WHERE id = :id")
                .param("id", id)
                .query(UserAccountRepository::map)
                .optional();
    }

    private static UserAccount map(ResultSet rs, int rowNum) throws SQLException {
        return new UserAccount(rs.getObject("id", UUID.class), rs.getString("mobile"),
                UserAccount.Status.valueOf(rs.getString("status")));
    }
}
