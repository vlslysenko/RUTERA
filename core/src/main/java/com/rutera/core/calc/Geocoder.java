package com.rutera.core.calc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Addresses to coordinates with a Nominatim server ({@code rutera.geocoding.url}; the public instance only for
 * development). Its usage policy is kept: a User-Agent that names the application and a contact, at most one request
 * per second, answers cached (1000 addresses, in memory). An empty URL switches geocoding off.
 */
@Component
public class Geocoder {

    /** A found place. */
    public record Place(double lat, double lng, String label) {
    }

    /** The geocoding server is unreachable or answered nonsense (answers 502). */
    public static final class Failed extends RuntimeException {
        public Failed(String message) {
            super(message);
        }
    }

    private static final int CACHE_SIZE = 1000;

    private final String url;
    private final String userAgent;
    private final ObjectMapper json;
    private final RestClient client;
    private final Map<String, Optional<Place>> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<Place>> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private long lastCall;

    public Geocoder(@Value("${rutera.geocoding.url:}") String url,
                    @Value("${rutera.geocoding.user-agent:RUTERA}") String userAgent, ObjectMapper json) {
        this.url = url == null ? "" : url.strip().replaceAll("/+$", "");
        this.userAgent = userAgent;
        this.json = json;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public boolean enabled() {
        return !url.isEmpty();
    }

    /** The best match of the address, or empty when nothing is found. */
    public Optional<Place> geocode(String address, String language) {
        String key = (language + "|" + address.strip().toLowerCase(Locale.ROOT));
        synchronized (cache) {
            Optional<Place> cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        if (!enabled()) {
            throw new Failed("geocoding is not configured");
        }
        Optional<Place> found = search(address, language);
        synchronized (cache) {
            cache.put(key, found);
        }
        return found;
    }

    private Optional<Place> search(String address, String language) {
        throttle();
        // A URI, not a String: RestClient treats a String as a template and would encode the query a second time.
        URI uri = UriComponentsBuilder.fromUriString(url + "/search").queryParam("q", address)
                .queryParam("format", "jsonv2").queryParam("limit", 1).encode().build().toUri();
        try {
            ResponseEntity<String> res = client.get().uri(uri).header("User-Agent", userAgent)
                    .header("Accept-Language", language == null ? "en" : language)
                    .accept(MediaType.APPLICATION_JSON).retrieve().onStatus(HttpStatusCode::isError, (req, r) -> {
                    }).toEntity(String.class);
            if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                throw new Failed("Nominatim answered " + res.getStatusCode().value());
            }
            JsonNode list = json.readTree(res.getBody());
            if (!list.isArray() || list.isEmpty()) {
                return Optional.empty();
            }
            JsonNode first = list.get(0);
            return Optional.of(new Place(first.path("lat").asDouble(), first.path("lon").asDouble(),
                    first.path("display_name").asString(address)));
        } catch (RestClientException | JacksonException e) {
            throw new Failed(e.getMessage());
        }
    }

    /** At most one request per second (Nominatim usage policy). */
    private synchronized void throttle() {
        long wait = lastCall + 1000 - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Failed("interrupted");
            }
        }
        lastCall = System.currentTimeMillis();
    }
}
