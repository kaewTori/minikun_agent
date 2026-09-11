package com.minikun.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class InvestmentExternalDataServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void readsBatchQuotesWithoutPuttingTheApiKeyInTheUrl() {
        RestClient.Builder twelve = RestClient.builder().baseUrl("https://twelve.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(twelve).build();
        server.expect(requestTo("https://twelve.test/price?symbol=AMZN,GIL"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "apikey twelve-secret"))
                .andRespond(withSuccess("{\"AMZN\":{\"price\":\"220.50\"},\"GIL\":{\"price\":\"54.80\"}}",
                        MediaType.APPLICATION_JSON));

        InvestmentExternalDataService service = new InvestmentExternalDataService(
                twelve.build(), RestClient.create(), RestClient.create(), RestClient.create(),
                new ObjectMapper(), CLOCK, "twelve-secret", "MinikunAgent/1.0", "", "");

        var quotes = service.latestQuotes(java.util.List.of("AMZN", "GIL"));

        assertEquals("220.50", quotes.get("AMZN").price().toPlainString());
        assertEquals("54.80", quotes.get("GIL").price().toPlainString());
        server.verify();
    }

    @Test
    void readsFrankfurterRatesAndSecFilings() {
        RestClient.Builder frankfurter = RestClient.builder().baseUrl("https://fx.test");
        MockRestServiceServer fxServer = MockRestServiceServer.bindTo(frankfurter).build();
        fxServer.expect(requestTo("https://fx.test/v2/rate/USD/THB"))
                .andRespond(withSuccess("{\"date\":\"2026-09-10\",\"base\":\"USD\",\"quote\":\"THB\",\"rate\":33.12}",
                        MediaType.APPLICATION_JSON));
        RestClient.Builder secTicker = RestClient.builder().baseUrl("https://sec-ticker.test");
        MockRestServiceServer secTickerServer = MockRestServiceServer.bindTo(secTicker).build();
        secTickerServer.expect(requestTo("https://sec-ticker.test/files/company_tickers.json"))
                .andExpect(header("User-Agent", "MinikunAgent/1.0 (contact: minikun@example.com)"))
                .andRespond(withSuccess("{\"0\":{\"cik_str\":1018724,\"ticker\":\"AMZN\",\"title\":\"Amazon\"}}",
                        MediaType.APPLICATION_JSON));
        RestClient.Builder sec = RestClient.builder().baseUrl("https://sec.test");
        MockRestServiceServer secServer = MockRestServiceServer.bindTo(sec).build();
        secServer.expect(requestTo("https://sec.test/submissions/CIK0001018724.json"))
                .andExpect(header("User-Agent", "MinikunAgent/1.0 (contact: minikun@example.com)"))
                .andRespond(withSuccess("{\"filings\":{\"recent\":{\"form\":[\"10-Q\"],\"accessionNumber\":[\"0001018724-26-000001\"],\"filingDate\":[\"2026-07-30\"],\"reportDate\":[\"2026-06-30\"],\"primaryDocument\":[\"amzn-20260630.htm\"]}}}",
                        MediaType.APPLICATION_JSON));

        InvestmentExternalDataService service = new InvestmentExternalDataService(
                RestClient.create(), frankfurter.build(), sec.build(), secTicker.build(), RestClient.create(),
                new ObjectMapper(), CLOCK, "", "MinikunAgent/1.0 (contact: minikun@example.com)", "", "");

        var rate = service.latestFxRate("USD", "THB");
        var filings = service.latestSecFilings("AMZN", 5);

        assertEquals("33.12", rate.rate().toPlainString());
        assertEquals("10-Q", filings.getFirst().form());
        assertTrue(filings.getFirst().url().contains("/1018724/000101872426000001/amzn-20260630.htm"));
        fxServer.verify();
        secServer.verify();
    }
}
