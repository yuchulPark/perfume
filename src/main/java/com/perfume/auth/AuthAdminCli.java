package com.perfume.auth;

import java.io.Console;
import java.nio.CharBuffer;
import java.util.Arrays;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Explicit console-only bootstrap. Runs before Spring, JPA, web, AI or importer startup. */
public final class AuthAdminCli {
    private AuthAdminCli() { }
    public static boolean runIfRequested(String[] args) {
        var modes = Arrays.stream(args).filter(a -> a.startsWith("--auth-admin=")).toList();
        if (modes.isEmpty()) return false;
        if (modes.size() != 1 || args.length != 1) throw new IllegalArgumentException("Use only --auth-admin=hash-token|create|dev-create");
        Console console = System.console();
        if (console == null) throw new IllegalStateException("Bootstrap requires an interactive console; credentials cannot be supplied as arguments");
        String mode = modes.get(0).substring("--auth-admin=".length());
        var encoder = new BCryptPasswordEncoder(12);
        if (mode.equals("hash-token")) {
            char[] token = read(console, "Bootstrap token (32+ random characters): ");
            try {
                if (token.length < 32 || CharBuffer.wrap(token).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
                    throw new IllegalArgumentException("Bootstrap token must contain 32+ characters and at most 72 UTF-8 bytes");
                console.printf("%s%n", encoder.encode(CharBuffer.wrap(token)));
            } finally { Arrays.fill(token, '\0'); }
            return true;
        }
        if (!mode.equals("create") && !mode.equals("dev-create")) throw new IllegalArgumentException("Unknown bootstrap mode");
        String tokenHash = required("PERFUME_AUTH_BOOTSTRAP_TOKEN_HASH");
        if (!tokenHash.matches("\\$2[aby]\\$1[0-6]\\$[./A-Za-z0-9]{53}")) throw new IllegalStateException("A BCrypt bootstrap token hash is required");
        char[] token = read(console, "Bootstrap token: ");
        try {
            verifyBootstrapCredential(tokenHash, CharBuffer.wrap(token), encoder);
        } finally { Arrays.fill(token, '\0'); }

        var source = new DriverManagerDataSource(required("DB_URL"), required("DB_USERNAME"), required("DB_PASSWORD"));
        boolean dev = mode.equals("dev-create");
        if (dev) AuthDataConfiguration.verifyIsolatedDatabase(source, required("DB_URL"));
        else {
            // Production CLI connections must use local Unix forwarding or verified TLS.
            String url = required("DB_URL");
            if (!(url.startsWith("jdbc:postgresql://127.0.0.1:") || url.contains("sslmode=verify-full")))
                throw new IllegalStateException("Use a reviewed loopback connection or sslmode=verify-full for bootstrap");
        }
        AuthDataConfiguration.verifySchema(source);
        String username = dev ? "admin" : console.readLine("Administrator ID: ");
        char[] password = read(console, "Administrator password: ");
        char[] confirm = read(console, "Confirm administrator password: ");
        try {
            if (!Arrays.equals(password, confirm)) throw new IllegalArgumentException("Password confirmation does not match");
            new AuthUserStore(source, encoder).createAdministrator(username, CharBuffer.wrap(password), dev);
            console.printf("Initial administrator created. Bootstrap registration is now closed.%n");
        } finally { Arrays.fill(password, '\0'); Arrays.fill(confirm, '\0'); }
        return true;
    }
    static void verifyBootstrapCredential(String hash, CharSequence token, org.springframework.security.crypto.password.PasswordEncoder encoder) {
        if (token.length() < 32 || token.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72 || !encoder.matches(token, hash))
            throw new IllegalArgumentException("Bootstrap authentication failed");
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + key + " before running bootstrap");
        return value;
    }
    private static char[] read(Console console, String prompt) {
        char[] value = console.readPassword("%s", prompt);
        if (value == null) throw new IllegalArgumentException("Bootstrap cancelled");
        return value;
    }
}
