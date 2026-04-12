package lt.satsyuk.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lt.satsyuk.config.DpopProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DpopProofValidatorTest {

    private static final String METHOD = "GET";
    private static final String REQUEST_URI = "https://api.example.com/resource?x=1";
    private static final String ACCESS_TOKEN = "access-token-value";
    private static final RSAKey TEST_RSA_JWK = generateTestRsaJwk();

    private final DpopProofValidator validator = new DpopProofValidator(defaultProperties());

    @Test
    void validate_acceptsValidProof() throws Exception {
        ProofData proofData = buildProof(METHOD, REQUEST_URI, ACCESS_TOKEN, Instant.now(), UUID.randomUUID().toString(), "dpop+jwt");
        String proof = proofData.serializedJwt();

        assertThatCode(() -> validator.validate(METHOD, REQUEST_URI, ACCESS_TOKEN, proof, null))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_acceptsUriNormalizationRules() throws Exception {
        String normalizedRequestUri = "https://api.example.com/resource";
        String proofUri = "https://API.EXAMPLE.COM:443/resource";

        ProofData proofData = buildProof("get", proofUri, ACCESS_TOKEN, Instant.now(), UUID.randomUUID().toString(), "dpop+jwt");
        String proof = proofData.serializedJwt();

        assertThatCode(() -> validator.validate("GET", normalizedRequestUri, ACCESS_TOKEN, proof, null))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsReplayUsingSameJti() throws Exception {
        ProofData proofData = buildProof(METHOD, REQUEST_URI, ACCESS_TOKEN, Instant.now(), "replay-id", "dpop+jwt");
        String proof = proofData.serializedJwt();

        validator.validate(METHOD, REQUEST_URI, ACCESS_TOKEN, proof, null);

        assertThatThrownBy(() -> validator.validate(METHOD, REQUEST_URI, ACCESS_TOKEN, proof, null))
                .isInstanceOf(DpopProofValidationException.class)
                .hasMessage("DPoP proof replay detected");
    }

    @Test
    void validate_rejectsWrongProofType() throws Exception {
        ProofData proofData = buildProof(METHOD, REQUEST_URI, ACCESS_TOKEN, Instant.now(), UUID.randomUUID().toString(), "jwt");
        String proof = proofData.serializedJwt();

        assertThatThrownBy(() -> validator.validate(METHOD, REQUEST_URI, ACCESS_TOKEN, proof, null))
                .isInstanceOf(DpopProofValidationException.class)
                .hasMessage("Invalid DPoP proof type");
    }

    @Test
    void validate_rejectsMethodMismatch() throws Exception {
        ProofData proofData = buildProof("POST", REQUEST_URI, ACCESS_TOKEN, Instant.now(), UUID.randomUUID().toString(), "dpop+jwt");
        String proof = proofData.serializedJwt();

        assertThatThrownBy(() -> validator.validate(METHOD, REQUEST_URI, ACCESS_TOKEN, proof, null))
                .isInstanceOf(DpopProofValidationException.class)
                .hasMessage("DPoP proof method mismatch");
    }

    @Test
    void validate_rejectsExpiredProof() throws Exception {
        ProofData proofData = buildProof(METHOD, REQUEST_URI, ACCESS_TOKEN, Instant.now().minus(Duration.ofMinutes(10)), UUID.randomUUID().toString(), "dpop+jwt");
        String proof = proofData.serializedJwt();

        assertThatThrownBy(() -> validator.validate(METHOD, REQUEST_URI, ACCESS_TOKEN, proof, null))
                .isInstanceOf(DpopProofValidationException.class)
                .hasMessage("DPoP proof is expired or issued in the future");
    }

    @Test
    void validate_rejectsAthMismatch() throws Exception {
        ProofData proofData = buildProof(METHOD, REQUEST_URI, "other-token", Instant.now(), UUID.randomUUID().toString(), "dpop+jwt");
        String proof = proofData.serializedJwt();

        assertThatThrownBy(() -> validator.validate(METHOD, REQUEST_URI, ACCESS_TOKEN, proof, null))
                .isInstanceOf(DpopProofValidationException.class)
                .hasMessage("DPoP proof access token hash mismatch");
    }

    @Test
    void validate_rejectsJktMismatch() throws Exception {
        ProofData proofData = buildProof(METHOD, REQUEST_URI, ACCESS_TOKEN, Instant.now(), UUID.randomUUID().toString(), "dpop+jwt");
        String proof = proofData.serializedJwt();

        assertThatThrownBy(() -> validator.validate(METHOD, REQUEST_URI, ACCESS_TOKEN, proof, "wrong-thumbprint"))
                .isInstanceOf(DpopProofValidationException.class)
                .hasMessage("DPoP proof key thumbprint mismatch");
    }

    @Test
    void validate_rejectsMalformedJwt() {
        assertThatThrownBy(() -> validator.validate(METHOD, REQUEST_URI, ACCESS_TOKEN, "not-a-jwt", null))
                .isInstanceOf(DpopProofValidationException.class)
                .hasMessage("Invalid DPoP proof format");
    }

    @Test
    void validate_rejectsWhenProofHeaderIsMissing() {
        assertThatThrownBy(() -> validator.validate(METHOD, REQUEST_URI, ACCESS_TOKEN, " ", null))
                .isInstanceOf(DpopProofValidationException.class)
                .hasMessage("DPoP proof header is missing");
    }

    @Test
    void validate_rejectsWhenAccessTokenIsMissing() {
        assertThatThrownBy(() -> validator.validate(METHOD, REQUEST_URI, "", "proof", null))
                .isInstanceOf(DpopProofValidationException.class)
                .hasMessage("Access token is missing for DPoP validation");
    }

    @Test
    void validate_rejectsWhenHtuClaimIsMissing() throws Exception {
        ProofData proofData = buildProofWithoutHtu();
        String proof = proofData.serializedJwt();

        assertThatThrownBy(() -> validator.validate(METHOD, REQUEST_URI, ACCESS_TOKEN, proof, null))
                .isInstanceOf(DpopProofValidationException.class)
                .hasMessage("DPoP proof URI is missing");
    }

    @Test
    void validate_rejectsWhenRequestUriHasNoScheme() throws Exception {
        ProofData proofData = buildProof(METHOD, REQUEST_URI, ACCESS_TOKEN, Instant.now(), UUID.randomUUID().toString(), "dpop+jwt");
        String proof = proofData.serializedJwt();

        assertThatThrownBy(() -> validator.validate(METHOD, "/relative", ACCESS_TOKEN, proof, null))
                .isInstanceOf(DpopProofValidationException.class)
                .hasMessage("URI scheme is required for DPoP validation");
    }

    private static DpopProperties defaultProperties() {
        DpopProperties properties = new DpopProperties();
        properties.setEnabled(true);
        properties.setMaxProofAge(Duration.ofMinutes(5));
        properties.setClockSkew(Duration.ofSeconds(5));
        properties.setReplayCacheSize(1000);
        return properties;
    }

    private static ProofData buildProof(String method,
                                        String htu,
                                        String tokenForAth,
                                        Instant iat,
                                        String jti,
                                        String typ) throws Exception {
        RSAKey rsaJwk = TEST_RSA_JWK;

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(new JOSEObjectType(typ))
                .jwk(rsaJwk.toPublicJWK())
                .build();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("htm", method)
                .claim("htu", htu)
                .claim("ath", ath(tokenForAth))
                .issueTime(Date.from(iat))
                .jwtID(jti)
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claims);
        signedJWT.sign(new RSASSASigner(rsaJwk));

        return new ProofData(signedJWT.serialize(), rsaJwk.toPublicJWK().computeThumbprint().toString());
    }

    private static ProofData buildProofWithoutHtu() throws Exception {
        RSAKey rsaJwk = TEST_RSA_JWK;

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(new JOSEObjectType("dpop+jwt"))
                .jwk(rsaJwk.toPublicJWK())
                .build();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("htm", METHOD)
                .claim("ath", ath(ACCESS_TOKEN))
                .issueTime(Date.from(Instant.now()))
                .jwtID(UUID.randomUUID().toString())
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claims);
        signedJWT.sign(new RSASSASigner(rsaJwk));

        return new ProofData(signedJWT.serialize(), rsaJwk.toPublicJWK().computeThumbprint().toString());
    }

    private static String ath(String accessToken) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(accessToken.getBytes(StandardCharsets.US_ASCII));
        return com.nimbusds.jose.util.Base64URL.encode(hash).toString();
    }

    private static RSAKey generateTestRsaJwk() {
        try {
            return new RSAKeyGenerator(2048).keyID("k1").generate();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to generate test RSA key", ex);
        }
    }

    private record ProofData(String serializedJwt, String jkt) {
    }
}

