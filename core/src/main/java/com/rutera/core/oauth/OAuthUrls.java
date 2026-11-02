package com.rutera.core.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Public addresses of the OAuth endpoints. {@code rutera.public-url} (env RUTERA_PUBLIC_URL, e.g.
 * https://rutera.example.com) is the issuer and the base of every published URL; it must be set behind a reverse
 * proxy. Without it the base comes from the request (scheme, host and port as the server sees them).
 */
@Component
public class OAuthUrls {

    public static final String PROTECTED_RESOURCE = "/.well-known/oauth-protected-resource";
    public static final String AUTHORIZATION_SERVER = "/.well-known/oauth-authorization-server";

    private final String publicUrl;

    public OAuthUrls(@Value("${rutera.public-url:}") String publicUrl) {
        String url = publicUrl == null ? "" : publicUrl.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.publicUrl = url;
    }

    /** Scheme, host and port without a trailing slash: the issuer and the resource server. */
    public String base(HttpServletRequest request) {
        if (!publicUrl.isEmpty()) {
            return publicUrl;
        }
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort || port <= 0 ? "" : ":" + port);
    }

    /** The protected resource metadata of the API part the request went to (RFC 9728). */
    public String resourceMetadata(HttpServletRequest request) {
        String path = request.getRequestURI();
        String suffix = path != null && path.startsWith("/api/") ? "/api/v1" : "/mcp";
        return base(request) + PROTECTED_RESOURCE + suffix;
    }

    /**
     * {@code WWW-Authenticate} of a 401 from /mcp or /api: where an MCP client finds the authorization server.
     * {@code invalidToken}: a token was sent but cannot be used (RFC 6750 error="invalid_token").
     */
    public void challenge(HttpServletRequest request, HttpServletResponse response, boolean invalidToken) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer "
                + (invalidToken ? "error=\"invalid_token\", " : "")
                + "resource_metadata=\"" + resourceMetadata(request) + "\"");
    }
}
