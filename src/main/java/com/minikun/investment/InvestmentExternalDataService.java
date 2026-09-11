package com.minikun.investment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/** Small, read-first adapters for the free external investment APIs. */
public final class InvestmentExternalDataService {
    private static final String SEC_FILINGS_HOST = "https://www.sec.gov/Archives/edgar/data/";
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final RestClient twelveData;
    private final RestClient frankfurter;
    private final RestClient sec;
    private final RestClient secTicker;
    private final RestClient alpacaPaper;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String twelveDataApiKey;
    private final String secUserAgent;
    private final String alpacaKeyId;
    private final String alpacaSecret;
    private volatile Map<String, String> tickerCik = Map.of();

    public InvestmentExternalDataService(
            RestClient twelveData,
            RestClient frankfurter,
            RestClient sec,
            RestClient alpacaPaper,
            ObjectMapper objectMapper,
            Clock clock,
            String twelveDataApiKey,
            String secUserAgent,
            String alpacaKeyId,
            String alpacaSecret) {
        this(twelveData, frankfurter, sec, sec, alpacaPaper, objectMapper, clock, twelveDataApiKey,
                secUserAgent, alpacaKeyId, alpacaSecret);
    }

    public InvestmentExternalDataService(
            RestClient twelveData,
            RestClient frankfurter,
            RestClient sec,
            RestClient secTicker,
            RestClient alpacaPaper,
            ObjectMapper objectMapper,
            Clock clock,
            String twelveDataApiKey,
            String secUserAgent,
            String alpacaKeyId,
            String alpacaSecret) {
        this.twelveData = Objects.requireNonNull(twelveData, "twelve data client must not be null");
        this.frankfurter = Objects.requireNonNull(frankfurter, "frankfurter client must not be null");
        this.sec = Objects.requireNonNull(sec, "SEC client must not be null");
        this.secTicker = Objects.requireNonNull(secTicker, "SEC ticker client must not be null");
        this.alpacaPaper = Objects.requireNonNull(alpacaPaper, "Alpaca paper client must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.twelveDataApiKey = Objects.requireNonNullElse(twelveDataApiKey, "").strip();
        this.secUserAgent = Objects.requireNonNullElse(
                secUserAgent, "MinikunAgent/1.0 (contact: minikun@example.com)").strip();
        this.alpacaKeyId = Objects.requireNonNullElse(alpacaKeyId, "").strip();
        this.alpacaSecret = Objects.requireNonNullElse(alpacaSecret, "").strip();
    }

    public boolean marketDataConfigured() {
        return !twelveDataApiKey.isBlank();
    }

    public boolean alpacaConfigured() {
        return !alpacaKeyId.isBlank() && !alpacaSecret.isBlank();
    }

    public Map<String, MarketQuote> latestQuotes(List<String> symbols) {
        List<String> requested = normalizeSymbols(symbols);
        if (requested.isEmpty()) return Map.of();
        requireConfigured(marketDataConfigured(), "Twelve Data API key is not configured");
        URI uri = UriComponentsBuilder.fromPath("/price")
                .queryParam("symbol", String.join(",", requested))
                .build().toUri();
        String response = get(twelveData, uri, Map.of("Authorization", "apikey " + twelveDataApiKey));
        try {
            JsonNode root = objectMapper.readTree(response);
            Map<String, MarketQuote> quotes = new LinkedHashMap<>();
            if (root.isObject() && root.has("price")) {
                addQuote(quotes, requested.getFirst(), root);
            } else if (root.isObject()) {
                root.fields().forEachRemaining(entry -> addQuote(quotes, entry.getKey(), entry.getValue()));
            } else if (root.isArray()) {
                root.forEach(value -> addQuote(quotes, value.path("symbol").asText(""), value));
            }
            if (quotes.isEmpty()) throw new IllegalStateException("Twelve Data returned no usable quotes");
            return Map.copyOf(quotes);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Twelve Data response is invalid", exception);
        }
    }

    public FxRate latestFxRate(String baseCurrency, String quoteCurrency) {
        String base = currency(baseCurrency, "base currency");
        String quote = currency(quoteCurrency, "quote currency");
        if (base.equals(quote)) {
            return new FxRate(base, quote, BigDecimal.ONE, clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                    "identity");
        }
        URI uri = UriComponentsBuilder.fromPath("/v2/rate/{base}/{quote}")
                .buildAndExpand(base, quote).toUri();
        String response = get(frankfurter, uri, Map.of());
        try {
            JsonNode root = objectMapper.readTree(response);
            BigDecimal rate = decimal(root, "rate");
            LocalDate date = root.hasNonNull("date")
                    ? LocalDate.parse(root.path("date").asText())
                    : clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
            return new FxRate(base, quote, rate, date, "frankfurter");
        } catch (Exception exception) {
            throw new IllegalStateException("Frankfurter response is invalid", exception);
        }
    }

    public List<SecFiling> latestSecFilings(String symbol, int limit) {
        String normalized = normalizeSymbol(symbol);
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("filing limit must be between 1 and 20");
        String cik = tickerCik().get(normalized);
        if (cik == null) return List.of();
        String response = get(sec, URI.create("/submissions/CIK" + cik + ".json"),
                Map.of("User-Agent", secUserAgent));
        try {
            JsonNode recent = objectMapper.readTree(response).path("filings").path("recent");
            JsonNode forms = recent.path("form");
            JsonNode accessionNumbers = recent.path("accessionNumber");
            JsonNode filingDates = recent.path("filingDate");
            JsonNode reportDates = recent.path("reportDate");
            JsonNode primaryDocuments = recent.path("primaryDocument");
            List<SecFiling> filings = new ArrayList<>();
            for (int index = 0; index < forms.size() && filings.size() < limit; index++) {
                String accession = textAt(accessionNumbers, index);
                String document = textAt(primaryDocuments, index);
                if (accession.isBlank() || document.isBlank()) continue;
                String accessionPath = accession.replace("-", "");
                String url = SEC_FILINGS_HOST + Long.parseLong(cik) + "/" + accessionPath + "/" + document;
                filings.add(new SecFiling(normalized, textAt(forms, index), textAt(filingDates, index),
                        textAt(reportDates, index), accession, url));
            }
            return List.copyOf(filings);
        } catch (Exception exception) {
            throw new IllegalStateException("SEC submissions response is invalid", exception);
        }
    }

    public Map<String, Object> alpacaPaperAccount() {
        requireConfigured(alpacaConfigured(), "Alpaca paper credentials are not configured");
        String response = get(alpacaPaper, URI.create("/v2/account"), alpacaHeaders());
        return parseObject(response, "Alpaca account response");
    }

    public Map<String, Object> submitAlpacaPaperOrder(
            String symbol, BigDecimal quantity, String side, String type, String timeInForce, BigDecimal limitPrice) {
        requireConfigured(alpacaConfigured(), "Alpaca paper credentials are not configured");
        String normalizedSymbol = normalizeSymbol(symbol);
        if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("quantity must be positive");
        String normalizedSide = enumValue(side, "buy", "sell", "side");
        String normalizedType = enumValue(type, "market", "limit", "order type");
        String normalizedTimeInForce = enumValue(timeInForce, "day", "gtc", "time in force");
        if (normalizedType.equals("limit") && (limitPrice == null || limitPrice.signum() <= 0)) {
            throw new IllegalArgumentException("limit price must be positive for a limit order");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("symbol", normalizedSymbol);
        request.put("qty", quantity.stripTrailingZeros().toPlainString());
        request.put("side", normalizedSide);
        request.put("type", normalizedType);
        request.put("time_in_force", normalizedTimeInForce);
        if (limitPrice != null) request.put("limit_price", limitPrice.stripTrailingZeros().toPlainString());
        try {
            String response = alpacaPaper.post().uri("/v2/orders")
                    .headers(headers -> alpacaHeaders().forEach(headers::set))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve().body(String.class);
            return parseObject(response, "Alpaca order response");
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("Alpaca paper API returned HTTP " + exception.getStatusCode().value(), exception);
        } catch (ResourceAccessException exception) {
            throw new IllegalStateException("Alpaca paper API is unavailable", exception);
        }
    }

    private Map<String, String> tickerCik() {
        Map<String, String> cached = tickerCik;
        if (!cached.isEmpty()) return cached;
        synchronized (this) {
            if (!tickerCik.isEmpty()) return tickerCik;
            String response = get(secTicker, URI.create("/files/company_tickers.json"),
                    Map.of("User-Agent", secUserAgent));
            try {
                JsonNode root = objectMapper.readTree(response);
                Map<String, String> values = new LinkedHashMap<>();
                root.fields().forEachRemaining(entry -> {
                    String ticker = entry.getValue().path("ticker").asText("").strip().toUpperCase(Locale.ROOT);
                    long cik = entry.getValue().path("cik_str").asLong(0);
                    if (!ticker.isBlank() && cik > 0) values.put(ticker, String.format("%010d", cik));
                });
                tickerCik = Map.copyOf(values);
                return tickerCik;
            } catch (Exception exception) {
                throw new IllegalStateException("SEC ticker mapping response is invalid", exception);
            }
        }
    }

    private void addQuote(Map<String, MarketQuote> quotes, String rawSymbol, JsonNode value) {
        if (rawSymbol == null || rawSymbol.isBlank() || value == null || !value.isObject()) return;
        String symbol;
        try { symbol = normalizeSymbol(rawSymbol); }
        catch (IllegalArgumentException ignored) { return; }
        JsonNode priceNode = value.get("price");
        if (priceNode == null || priceNode.isNull() || priceNode.asText().isBlank()) priceNode = value.get("close");
        if (priceNode == null || priceNode.isNull() || priceNode.asText().isBlank()) return;
        try {
            BigDecimal price = new BigDecimal(priceNode.asText());
            String currency = value.path("currency").asText("USD").toUpperCase(Locale.ROOT);
            quotes.put(symbol, new MarketQuote(symbol, price, currency, clock.instant(), "twelve-data"));
        } catch (NumberFormatException ignored) {
            // A per-symbol error in a batch response should not discard usable symbols.
        }
    }

    private String get(RestClient client, URI uri, Map<String, String> headers) {
        try {
            return client.get().uri(uri).headers(value -> headers.forEach(value::set))
                    .retrieve().body(String.class);
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("external investment API returned HTTP "
                    + exception.getStatusCode().value(), exception);
        } catch (ResourceAccessException exception) {
            throw new IllegalStateException("external investment API is unavailable", exception);
        }
    }

    private Map<String, Object> parseObject(String response, String label) {
        try {
            return objectMapper.readValue(response, OBJECT_MAP);
        } catch (Exception exception) {
            throw new IllegalStateException(label + " is invalid", exception);
        }
    }

    private Map<String, String> alpacaHeaders() {
        return Map.of("APCA-API-KEY-ID", alpacaKeyId, "APCA-API-SECRET-KEY", alpacaSecret);
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalStateException("missing " + field);
        }
        return new BigDecimal(value.asText());
    }

    private String textAt(JsonNode node, int index) {
        return node.isArray() && index < node.size() ? node.get(index).asText("") : "";
    }

    private List<String> normalizeSymbols(List<String> symbols) {
        if (symbols == null) return List.of();
        return symbols.stream().filter(Objects::nonNull).flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .map(String::strip).filter(value -> !value.isBlank()).map(this::normalizeSymbol).distinct().toList();
    }

    private String normalizeSymbol(String value) {
        String normalized = Objects.requireNonNullElse(value, "").strip().toUpperCase(Locale.ROOT);
        if (normalized.isBlank() || !normalized.matches("[A-Z0-9.-]{1,32}")) {
            throw new IllegalArgumentException("symbol must contain only letters, numbers, dots, or dashes");
        }
        return normalized;
    }

    private String currency(String value, String field) {
        String normalized = Objects.requireNonNullElse(value, "").strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) throw new IllegalArgumentException(field + " must be a 3-letter currency");
        return normalized;
    }

    private String enumValue(String value, String first, String second, String field) {
        String normalized = Objects.requireNonNullElse(value, "").strip().toLowerCase(Locale.ROOT);
        if (!normalized.equals(first) && !normalized.equals(second)) {
            throw new IllegalArgumentException(field + " must be " + first + " or " + second);
        }
        return normalized;
    }

    private void requireConfigured(boolean configured, String message) {
        if (!configured) throw new IllegalStateException(message);
    }

    public record MarketQuote(String symbol, BigDecimal price, String currency, Instant observedAt, String source) { }

    public record FxRate(String baseCurrency, String quoteCurrency, BigDecimal rate,
            LocalDate date, String source) { }

    public record SecFiling(String symbol, String form, String filingDate, String reportDate,
            String accessionNumber, String url) { }
}
