package lt.satsyuk.security;

public final class SecurityEndpointGroupResolver {

    private SecurityEndpointGroupResolver() {
    }

    public static String resolve(String path) {
        if (path == null || path.isBlank()) {
            return "unknown";
        }
        if (path.startsWith("/api/auth/")) {
            return "auth";
        }
        if (path.startsWith("/api/clients")) {
            return "clients";
        }
        if (path.startsWith("/api/requests")) {
            return "requests";
        }
        if (path.startsWith("/api/accounts")) {
            return "accounts";
        }
        if (path.startsWith("/actuator")) {
            return "actuator";
        }
        if (path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")) {
            return "docs";
        }
        return "other";
    }
}
