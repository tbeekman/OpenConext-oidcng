package oidc.secure;

import com.google.common.collect.Lists;
import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEDecrypter;
import com.nimbusds.jose.JWEEncrypter;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.nimbusds.jose.crypto.RSAEncrypter;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.crypto.impl.RSACryptoProvider;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.claims.AccessTokenHash;
import oidc.exceptions.InvalidSignatureException;
import oidc.model.User;
import org.apache.commons.io.IOUtils;
import org.springframework.core.io.Resource;
import org.springframework.util.CollectionUtils;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.text.ParseException;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

public class TokenGenerator {

    private static char[] DEFAULT_CODEC = "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            .toCharArray();

    private Random random = new SecureRandom();

    private String issuer;

    private JWKSet jwkSet;

    private RSASSASigner signer;

    private RSASSAVerifier verifier;

    private JWEEncrypter encrypter;

    private JWEDecrypter decrypter;

    private String kid;

    private Map<String, RSAKey> publicKeys;

    private JWSAlgorithm signingAlg = JWSAlgorithm.RS256;

    private JWEEncrypter symmetricEncryper;

    private JWEDecrypter symmetricDecriptor;

    public TokenGenerator(Resource jwksKeyStorePath, String issuer, String secureSecret) throws IOException, ParseException, JOSEException {
        this.issuer = issuer;
        String s = IOUtils.toString(jwksKeyStorePath.getInputStream(), Charset.defaultCharset());
        jwkSet = JWKSet.parse(s);
        RSAKey rsaJWK = (RSAKey) jwkSet.getKeys().get(0);
        this.publicKeys = Collections.singletonMap(kid, rsaJWK.toPublicJWK());
        this.kid = rsaJWK.getKeyID();

        this.signer = new RSASSASigner(rsaJWK);
        this.verifier = new RSASSAVerifier(rsaJWK);
        this.encrypter = new RSAEncrypter(rsaJWK);
        this.decrypter = new RSADecrypter(rsaJWK);

        SecretKey secretKey = new SecretKeySpec(Base64.getDecoder().decode(secureSecret), "AES");
        this.symmetricDecriptor = new DirectDecrypter(secretKey);
        this.symmetricEncryper = new DirectEncrypter(secretKey);

    }

    public String generateAccessToken() {
        return UUID.randomUUID().toString();
    }

    public String generateRefreshToken() {
        return UUID.randomUUID().toString();
    }

    public String generateEncryptedAccessToken(Map<String, Object> input) throws JOSEException {
        return encryptedAccessToken(input, this.encrypter);
    }

    public Map<String, Object> decryptAccessToken(String jweString) throws ParseException, JOSEException {
        return doDecryptAccessToken(jweString, decrypter);
    }

    public String generateSymmetricEncryptedAccessToken(Map<String, Object> input) throws JOSEException {
        return encryptedAccessToken(input, this.symmetricEncryper);
    }

    public Map<String, Object> decryptSymmtricAccessToken(String jweString) throws ParseException, JOSEException {
        return doDecryptAccessToken(jweString, this.symmetricDecriptor);
    }


    public String generateAuthorizationCode() {
        byte[] verifierBytes = new byte[12];
        random.nextBytes(verifierBytes);
        char[] chars = new char[verifierBytes.length];
        for (int i = 0; i < verifierBytes.length; i++) {
            chars[i] = DEFAULT_CODEC[((verifierBytes[i] & 0xFF) % DEFAULT_CODEC.length)];
        }
        return new String(chars);
    }

    public String generateIDTokenForTokenEndpoint(Optional<User> user, String clientId, List<String> idTokenClaims) throws JOSEException {
        return idToken(clientId, user, Collections.emptyMap(), idTokenClaims);
    }

    public String generateIDTokenForAuthorizationEndpoint(User user, String clientId, Nonce nonce,
                                                          ResponseType responseType, String accessToken,
                                                          List<String> claims) throws JOSEException {
        Map<String, Object> additionalClaims = new HashMap<>();
        if (nonce != null) {
            additionalClaims.put("nonce", nonce.getValue());
        }
        if (AccessTokenHash.isRequiredInIDTokenClaims(responseType)) {
            additionalClaims.put("at_hash",
                    AccessTokenHash.compute(new BearerAccessToken(accessToken), signingAlg).getValue());
        }
        return idToken(clientId, Optional.of(user), additionalClaims, claims);
    }

    private void addClaimsRequested(User user, List<String> claims, Map<String, Object> additionalClaims) {
        Map<String, Object> attributes = user.getAttributes();
        if (!CollectionUtils.isEmpty(claims)) {
            claims.forEach(claim -> {
                if (attributes.containsKey(claim)) {
                    additionalClaims.put(claim, attributes.get(claim));
                }
            });
        }
    }

    public Map<String, Object> verifyClaims(SignedJWT signedJWT) throws ParseException, JOSEException {
        if (!signedJWT.verify(verifier)) {
            throw new InvalidSignatureException("Tampered JWT");
        }
        return signedJWT.getJWTClaimsSet().getClaims();
    }

    private Map<String, Object> doDecryptAccessToken(String jweString, JWEDecrypter decrypter) throws ParseException, JOSEException {
        JWEObject jweObject = JWEObject.parse(jweString);
        jweObject.decrypt(decrypter);
        SignedJWT signedJWT = jweObject.getPayload().toSignedJWT();
        return verifyClaims(signedJWT);
    }

    private String encryptedAccessToken(Map<String, Object> input, JWEEncrypter encrypter) throws JOSEException {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        input.forEach((name, value) -> builder.claim(name, value));

        SignedJWT signedJWT = getSignedJWT(builder);
        JWEHeader header = encrypter instanceof RSACryptoProvider ?
                new JWEHeader(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM) :
                new JWEHeader(JWEAlgorithm.DIR, EncryptionMethod.A256CBC_HS512);

        JWEObject jweObject = new JWEObject(header, new Payload(signedJWT));
        jweObject.encrypt(encrypter);
        return jweObject.serialize();
    }

    private String idToken(String clientId, Optional<User> user, Map<String, Object> additionalClaims,
                           List<String> idTokenClaims) throws JOSEException {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .audience(Lists.newArrayList(clientId))
                .expirationTime(new Date(System.currentTimeMillis() + (60 * 5 * 1000L)))
                .jwtID(UUID.randomUUID().toString())
                .issuer(issuer)
                .issueTime(new Date())
                .subject(user.map(u -> u.getSub()).orElse(clientId))
                .notBeforeTime(new Date(System.currentTimeMillis()));

        additionalClaims.forEach((name, value) -> builder.claim(name, value));

        if (user.isPresent() && !CollectionUtils.isEmpty(idTokenClaims)) {
            Map<String, Object> attributes = user.get().getAttributes();
            idTokenClaims.forEach(claim -> {
                if (attributes.containsKey(claim)) {
                    builder.claim(claim, attributes.get(claim));
                }
            });
        }

        SignedJWT signedJWT = getSignedJWT(builder);
        return signedJWT.serialize();
    }

    private SignedJWT getSignedJWT(JWTClaimsSet.Builder builder) throws JOSEException {
        JWTClaimsSet claimsSet = builder.build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(kid).build();
        SignedJWT signedJWT = new SignedJWT(header, claimsSet);
        signedJWT.sign(this.signer);
        return signedJWT;
    }

    public Map<String, ? extends JWK> getAllPublicKeys() {
        return this.publicKeys;
    }
}
