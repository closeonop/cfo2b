package com.empmg.platform.common;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Cors cors = new Cors();
    private Encryption encryption = new Encryption();
    private Mail mail = new Mail();
    private RateLimit rateLimit = new RateLimit();
    /** Base URL of the frontend, used in emails (password reset). */
    private String frontendUrl = "http://localhost:3000";

    @Getter
    @Setter
    public static class Jwt {
        private String accessSecret;
        private String refreshSecret;
        private int accessTtlMinutes = 15;
        private int refreshTtlDays = 7;
    }

    @Getter
    @Setter
    public static class Cors {
        private String origins = "http://localhost:3000";
    }

    @Getter
    @Setter
    public static class Encryption {
        /** Base64-encoded 32-byte AES-256 key used for salary fields at rest. */
        private String key;
    }

    @Getter
    @Setter
    public static class Mail {
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class RateLimit {
        private int loginMaxFailures = 5;
        private int loginWindowMinutes = 15;
        private int loginLockMinutes = 15;
    }
}
