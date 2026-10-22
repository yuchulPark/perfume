package com.perfume.auth;

import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC deliberately keeps auth tables outside Hibernate's catalog schema lifecycle. */
public final class AuthUserStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder encoder;
    public AuthUserStore(DataSource source, PasswordEncoder encoder) {
        this.jdbc = new JdbcTemplate(source);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.encoder = encoder;
    }
    public UserDetails loadUser(String username) {
        var users = jdbc.query("SELECT username, password_hash, role, enabled FROM auth.app_users WHERE username = ?",
            (rs, row) -> User.withUsername(rs.getString("username")).password(rs.getString("password_hash"))
                .roles(rs.getString("role")).disabled(!rs.getBoolean("enabled")).build(), username);
        if (users.size() != 1) throw new UsernameNotFoundException("Invalid credentials");
        return users.get(0);
    }
    public void createAdministrator(String username, CharSequence password, boolean isolatedDevelopment) {
        if (username == null || !username.matches("[a-zA-Z0-9_.-]{3,64}")) throw new IllegalArgumentException("Invalid administrator ID");
        validatePassword(username, password, isolatedDevelopment);
        try {
            transactions.executeWithoutResult(status -> {
                if (jdbc.queryForObject("SELECT count(*) FROM auth.app_users", Long.class) != 0)
                    throw new IllegalStateException("Initial registration is already closed");
                jdbc.update("INSERT INTO auth.app_users(username, password_hash, role, enabled) VALUES (?, ?, 'ADMIN', true)",
                    username, encoder.encode(password));
            });
        } catch (DataIntegrityViolationException ex) {
            // Unique username and the single-ADMIN index also serialize concurrent first registrations.
            throw new IllegalStateException("Initial registration is already closed");
        }
    }
    static void validatePassword(String username, CharSequence password, boolean dev) {
        if (password == null || password.length() == 0 || password.toString().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Password must contain 1 to 72 UTF-8 bytes");
        if (dev) return;
        String value = password.toString();
        int groups = (value.matches(".*[a-z].*") ? 1 : 0) + (value.matches(".*[A-Z].*") ? 1 : 0)
            + (value.matches(".*[0-9].*") ? 1 : 0) + (value.matches(".*[^a-zA-Z0-9].*") ? 1 : 0);
        if (password.length() < 12 || groups < 3 || value.toLowerCase(java.util.Locale.ROOT).contains(username.toLowerCase(java.util.Locale.ROOT))
            || value.toLowerCase(java.util.Locale.ROOT).matches(".*(password|changeme|qwerty|replace.with|default).*"))
            throw new IllegalArgumentException("Use a unique password of at least 12 characters from three character groups, excluding the ID and default passwords");
    }
    void verifyReady(boolean dev) {
        var users = jdbc.query("SELECT username, password_hash, role, enabled FROM auth.app_users",
            (rs, row) -> new StoredUser(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)));
        if (users.size() != 1 || !users.get(0).enabled || !users.get(0).role.equals("ADMIN"))
            throw new IllegalStateException("Authentication requires exactly one enabled administrator; run the approved bootstrap CLI first");
        var user = users.get(0);
        if (!user.hash.matches("\\$2[aby]\\$(1[0-6])\\$[./A-Za-z0-9]{53}"))
            throw new IllegalStateException("Authentication requires a BCrypt hash with cost 10 to 16");
        if (!dev && List.of(user.username, "admin", "password", "changeme", "123456", "123456789", "Password123!").stream().anyMatch(p -> encoder.matches(p, user.hash)))
            throw new IllegalStateException("Default administrator passwords are forbidden outside the isolated development database");
    }
    private record StoredUser(String username, String hash, String role, boolean enabled) { }
}
