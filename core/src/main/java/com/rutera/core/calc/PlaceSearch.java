package com.rutera.core.calc;

import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Places for search-as-you-type ({@code GET /app/calc/places}) from a Photon server ({@code rutera.places.url},
 * RUTERA_GEOCODER_URL): a self-hosted one, or photon.komoot.io for development (fair use). Public Nominatim forbids
 * autocomplete, so it is not used here; the route keeps geocoding typed addresses with {@link Geocoder}.
 * <p>
 * Answers are cached (2000 queries, one day); calls to the server are spread to at most
 * {@code rutera.places.max-per-second}, a caller waits up to a second for its turn, else 429. Texts in the user's
 * language where Photon has it (en, de, fr), else the local names.
 */
@Service
public class PlaceSearch {

    static final int MIN_QUERY = 3;
    static final int MAX_LIMIT = 10;
    private static final int CACHE_SIZE = 2000;
    private static final long CACHE_MILLIS = Duration.ofDays(1).toMillis();
    private static final long MAX_WAIT_MILLIS = 1000;
    private static final Set<String> LANGUAGES = Set.of("en", "de", "fr");

    private record Cached(List<CalcDto.Place> places, long at) {
    }

    private final String url;
    private final String userAgent;
    private final long intervalMillis;
    private final ObjectMapper json;
    private final RestClient client;
    private final Map<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private long nextCall;

    public PlaceSearch(@Value("${rutera.places.url:}") String url,
                       @Value("${rutera.places.max-per-second:5}") int maxPerSecond,
                       @Value("${rutera.geocoding.user-agent:RUTERA}") String userAgent, ObjectMapper json) {
        this.url = url == null ? "" : url.strip().replaceAll("/+$", "");
        this.userAgent = userAgent;
        this.intervalMillis = 1000L / Math.max(1, maxPerSecond);
        this.json = json;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /** Up to {@code limit} places for the text; none below 3 characters. */
    @RequiresPrivilege(Privileges.TRIP_TRIP_VIEW)
    public List<CalcDto.Place> search(String query, Integer limit) {
        String q = query == null ? "" : query.strip().replaceAll("\\s+", " ");
        if (q.length() < MIN_QUERY) {
            return List.of();
        }
        int max = limit == null ? 5 : Math.clamp(limit, 1, MAX_LIMIT);
        String userLang = CurrentUser.get().map(u -> u.lang()).orElse("en");
        String lang = userLang != null && LANGUAGES.contains(userLang) ? userLang : "default";
        String key = lang + "|" + max + "|" + q.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        synchronized (cache) {
            Cached hit = cache.get(key);
            if (hit != null && now - hit.at() < CACHE_MILLIS) {
                return hit.places();
            }
        }
        if (url.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.CALC_GEOCODE_FAILED);
        }
        waitForTurn();
        List<CalcDto.Place> found = fetch(q, max, lang);
        synchronized (cache) {
            cache.put(key, new Cached(found, System.currentTimeMillis()));
        }
        return found;
    }

    /** Spreads the calls to the server; 429 when the wait would be longer than a second. */
    private void waitForTurn() {
        long wait;
        synchronized (this) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, nextCall);
            wait = slot - now;
            if (wait > MAX_WAIT_MILLIS) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.API_RATE_LIMITED);
            }
            nextCall = slot + intervalMillis;
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.CALC_GEOCODE_FAILED);
            }
        }
    }

    private List<CalcDto.Place> fetch(String q, int limit, String lang) {
        String uri = UriComponentsBuilder.fromUriString(url + "/api").queryParam("q", q).queryParam("limit", limit)
                .queryParam("lang", lang).encode().toUriString();
        try {
            ResponseEntity<String> res = client.get().uri(uri).header("User-Agent", userAgent)
                    .accept(MediaType.APPLICATION_JSON).retrieve().onStatus(HttpStatusCode::isError, (req, r) -> {
                    }).toEntity(String.class);
            if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.CALC_GEOCODE_FAILED);
            }
            JsonNode features = json.readTree(res.getBody()).path("features");
            List<CalcDto.Place> places = new ArrayList<>();
            for (JsonNode f : features) {
                JsonNode xy = f.path("geometry").path("coordinates");
                String label = label(f.path("properties"));
                if (xy.size() >= 2 && !label.isEmpty()) {
                    String country = f.path("properties").path("countrycode").asString("");
                    places.add(new CalcDto.Place(label, xy.get(1).asDouble(), xy.get(0).asDouble(),
                            country.isEmpty() ? null : country.toUpperCase(Locale.ROOT)));
                }
            }
            return List.copyOf(places);
        } catch (RestClientException | JacksonException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.CALC_GEOCODE_FAILED);
        }
    }

    /**
     * One line for a Photon feature: an address as "street number, postcode city, country", a town as "name, region,
     * country", anything else as "name, street, city, country"; empty parts and repeats left out.
     */
    static String label(JsonNode p) {
        String name = text(p, "name");
        String street = text(p, "street");
        String number = text(p, "housenumber");
        String city = text(p, "city");
        if (city.isEmpty()) {
            city = text(p, "locality");
        }
        List<String> parts = new ArrayList<>();
        if (!number.isEmpty() && !street.isEmpty()) {
            parts.add(name);
            parts.add(street + " " + number);
            parts.add((text(p, "postcode") + " " + city).strip());
        } else if ("place".equals(text(p, "osm_key")) || "boundary".equals(text(p, "osm_key"))) {
            parts.add(name);
            parts.add(text(p, "county").isEmpty() || name.equals(city) ? "" : text(p, "county"));
            parts.add(text(p, "state"));
        } else {
            parts.add(name);
            parts.add(street);
            parts.add(city);
        }
        parts.add(text(p, "country"));
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty() && (out.isEmpty() || !out.getLast().equalsIgnoreCase(part)) && !out.contains(part)) {
                out.add(part);
            }
        }
        return String.join(", ", out);
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asString("").strip();
    }
}
