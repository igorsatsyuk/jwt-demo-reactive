package lt.satsyuk.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class KeycloakOpaqueRoleConverter implements Converter<Map<String, Object>, Collection<GrantedAuthority>> {

    @Override
    @SuppressWarnings("java:S2638")
    public Collection<GrantedAuthority> convert(Map<String, Object> attributes) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();

        // -----------------------------
        // 1. Realm roles
        // -----------------------------
        Object realmAccessObj = attributes.get("realm_access");
        if (realmAccessObj instanceof Map<?, ?> realmAccess) {
            Object rolesObj = realmAccess.get("roles");
            if (rolesObj instanceof List<?> roles) {
                roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
            }
        }

        // -----------------------------
        // 2. Client roles (resource_access.<client>.roles)
        // -----------------------------
        Object resourceAccessObj = attributes.get("resource_access");
        if (resourceAccessObj instanceof Map<?, ?> resourceAccess) {
            resourceAccess.forEach((_, access) -> {
                if (access instanceof Map<?, ?> accessMap) {
                    Object rolesObj = accessMap.get("roles");
                    if (rolesObj instanceof List<?> roles) {
                        roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
                    }
                }
            });
        }

        return authorities;
    }
}
