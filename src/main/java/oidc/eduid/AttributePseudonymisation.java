package oidc.eduid;

import oidc.model.OpenIDClient;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AttributePseudonymisation {

    private static final Log LOG = LogFactory.getLog(AttributePseudonymisation.class);

    private final RestTemplate restTemplate;
    private final HttpHeaders headers;
    private final URI eduIdUri;
    private final boolean enabled;

    public AttributePseudonymisation(@Value("${eduid.uri}") URI eduIdUri,
                                     @Value("${eduid.user}") String user,
                                     @Value("${eduid.password}") String password,
                                     @Value("${eduid.enabled}") boolean enabled) {
        this.restTemplate = new RestTemplate();
        this.restTemplate.setErrorHandler(new FaultTolerantResponseErrorHandler());
        this.eduIdUri = eduIdUri;
        this.enabled = enabled;

        this.headers = new HttpHeaders();
        this.headers.setContentType(MediaType.APPLICATION_JSON);
        this.headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        this.headers.setBasicAuth(user, password);
    }

    /**
     * The Resource Server (RS) needs to be able to identify the user and to make this possible we ask eduID ALA to
     * provide us with the scoped eduID for this RS and the EPPN of the user at his / hers Home Institution. This
     * scenario will only work if the user has linked his / hers eduID account with the RS / Home Institution.
     *
     * @param resourceServer the API owner RS
     * @param openIDClient   the owner of the access token who is calling an API endpoint of the resourceServer
     * @param eduId          the user eduID
     * @param uids           the user identifiers
     * @return the manipulated attributes
     */
    public Optional<Map<String, String>> pseudonymise(OpenIDClient resourceServer, OpenIDClient openIDClient, String eduId, List<String> uids) {
        boolean eduIdMissing = StringUtils.isEmpty(eduId);
        boolean resourceServerEquals = resourceServer.getClientId().equals(openIDClient.getClientId());

        LOG.debug(String.format("Starting to pseudonymise for RS %s and openIDclient %s. " +
                        "Enabled is %s, eduIdMissing is %s, resourceServerEquals is %s, uids  is %s",
                resourceServer.getClientId(), openIDClient.getClientId(), enabled, eduIdMissing, resourceServerEquals, uids));

        if (!enabled || eduIdMissing || resourceServerEquals || CollectionUtils.isEmpty(uids)) {
            LOG.debug("Returning empty result for 'pseudonymise'");
            return Optional.empty();
        }
        Map<String, String> result = new HashMap<>();
        HttpEntity<?> requestEntity = new HttpEntity<>(headers);

        String uriString = UriComponentsBuilder.fromUri(eduIdUri)
                .queryParam("uid", uids.get(0))
                .queryParam("sp_entity_id", resourceServer.getClientId())
                .queryParam("sp_institution_guid", resourceServer.getInstitutionGuid())
                .toUriString();
        ResponseEntity<Map<String, String>> responseEntity =
                restTemplate.exchange(uriString, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<Map<String, String>>() {
                });
        if (responseEntity.getStatusCode().is2xxSuccessful()) {
            Map<String, String> body = responseEntity.getBody();
            result.putAll(body);
            LOG.debug(String.format("Pseudonymise result %s for RS %s, RP %s", body, resourceServer.getClientId(), openIDClient.getClientId()));
        } else {
            LOG.error(String.format("Error %s occurred in pseudonymise for RS %s, RP %s, response %s",
                    requestEntity.getBody(), resourceServer.getClientId(), openIDClient.getClientId(), responseEntity));
            return Optional.empty();
        }
        return Optional.of(result);
    }

}
