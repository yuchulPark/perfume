package com.perfume.auth;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthAdminCliTests {
    @Test void bootstrapRequiresTheSeparateStrongCredentialBeforeAnyDatabaseMutation() {
        var encoder = new BCryptPasswordEncoder(10);
        String token = java.util.UUID.randomUUID().toString();
        String hash = encoder.encode(token);
        AuthAdminCli.verifyBootstrapCredential(hash, token, encoder);
        assertThrows(IllegalArgumentException.class, () -> AuthAdminCli.verifyBootstrapCredential(hash, java.util.UUID.randomUUID().toString(), encoder));
        String tooShort = "admin";
        assertThrows(IllegalArgumentException.class, () -> AuthAdminCli.verifyBootstrapCredential(encoder.encode(tooShort), tooShort, encoder));
        assertFalse(AuthAdminCli.runIfRequested(new String[] {}));
        assertThrows(IllegalArgumentException.class, () -> AuthAdminCli.runIfRequested(new String[] {"--auth-admin=create", "--password=forbidden"}));
    }
}
