package oidc.endpoints;

import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.TokenIntrospectionRequest;
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.ServletUtils;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.openid.connect.sdk.UserInfoRequest;
import oidc.exceptions.UnauthorizedException;
import oidc.model.AccessToken;
import oidc.model.User;
import oidc.repository.AccessTokenRepository;
import oidc.repository.UserRepository;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Date;
import java.util.Map;

@RestController
public class IntrospectEndpoint {

    private AccessTokenRepository accessTokenRepository;
    private UserRepository userRepository;

    @PostMapping(value = {"oidc/introspect"}, consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE})
    public Map<String, Object> postUserInfo(HttpServletRequest request) throws ParseException, IOException {
        return userInfo(request);
    }

    public Map<String, Object> userInfo(HttpServletRequest request) throws ParseException, IOException {
        HTTPRequest httpRequest = ServletUtils.createHTTPRequest(request);
        TokenIntrospectionRequest tokenIntrospectionRequest = TokenIntrospectionRequest.parse(httpRequest);
        ClientAuthentication clientAuthentication = tokenIntrospectionRequest.getClientAuthentication();
        String value = tokenIntrospectionRequest.getToken().getValue();

        AccessToken accessToken = accessTokenRepository.findByValue(value);
        if (accessToken == null) {
            throw new UnauthorizedException("Access token not found");
        }
        if (accessToken.getExpiresIn().before(new Date())) {
            throw new UnauthorizedException("Access token expired");
        }
        User user = userRepository.findUserBySub(accessToken.getSub());
        if (user == null) {
            throw new UnauthorizedException("User not found");
        }
        Map<String, Object> attributes = user.getAttributes();
        attributes.put("updated_at", user.getUpdatedAt());
        return attributes;

    }
}