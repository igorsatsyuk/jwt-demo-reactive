package lt.satsyuk.security;

public final class SecurityEndpointGroupResolver {

    private SecurityEndpointGroupResolver() {
    }

    public static String resolve(String path) {
        if (path == null || path.isBlank()) {
            return "unknown";
        }
        if (path.equals("/api/auth") || path.startsWith("/api/auth/")) {
            return "auth";
        }
        if (path.equals("/api/clients") || path.startsWith("/api/clients/")) {
            return "clients";
        }
        if (path.equals("/api/requests") || path.startsWith("/api/requests/")) {
            return "requests";
        }
        if (path.equals("/api/accounts") || path.startsWith("/api/accounts/")) {
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
