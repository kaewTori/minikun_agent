package com.minikun.agent.minikun_agent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.search.AcronymDictionary;
import com.minikun.search.AliasDictionary;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.SearchContextAwarenessService;
import com.minikun.search.SearchQueryExpansionService;
import com.minikun.search.SearchQueryRewriteService;
import com.minikun.search.SynonymDictionary;
import com.minikun.search.dictionary.ImmutableSynonymDictionary;
import com.minikun.search.dictionary.ImmutableAcronymDictionary;
import com.minikun.search.dictionary.ImmutableAliasDictionary;
import com.minikun.search.internal.RuleBasedSearchQueryExpansionService;
import com.minikun.search.model.ExpandedSearchQuery;
import com.minikun.search.model.SearchQuery;
import com.minikun.tools.ToolRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
	"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration",
	"minikun.memory.persistence.enabled=false",
	"minikun.planner.enabled=false"
})
@AutoConfigureMockMvc
@Import(TestChatMemoryConfiguration.class)
class MinikunAgentApplicationTests {

	private static final String DECISION_METRIC = "minikun.search.decision.duration";
	private static final Set<String> ALLOWED_TAGS = Set.of(
			"mode", "provider", "cache", "failure_type", "outcome");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private SearchDecisionService searchDecisionService;

	@Autowired
	private SearchContextAwarenessService searchContextAwarenessService;

	@Autowired
	private SearchQueryRewriteService searchQueryRewriteService;

	@Autowired
	private SearchQueryExpansionService searchQueryExpansionService;

	@Autowired
	private SynonymDictionary synonymDictionary;

	@Autowired
	private AcronymDictionary acronymDictionary;

	@Autowired
	private AliasDictionary aliasDictionary;

	@Autowired
	private ToolRegistry toolRegistry;

	@Test
	void contextLoads() {
	}

	@Test
	void contextRegistersNativeCapabilityTools() {
		Set<String> names = toolRegistry.definitions().stream()
				.map(com.minikun.tools.ToolDefinition::name)
				.collect(java.util.stream.Collectors.toSet());

		org.junit.jupiter.api.Assertions.assertTrue(names.contains("weather.get_forecast"));
		org.junit.jupiter.api.Assertions.assertTrue(names.contains("location.resolve"));
		org.junit.jupiter.api.Assertions.assertTrue(names.contains("time.get_current_time"));
		org.junit.jupiter.api.Assertions.assertTrue(names.contains("web.search"));
		org.junit.jupiter.api.Assertions.assertTrue(names.contains("web.open_url"));
		org.junit.jupiter.api.Assertions.assertTrue(names.contains("service.health"));
		org.junit.jupiter.api.Assertions.assertTrue(names.contains("system.health"));
	}

	@Test
	void contextWiresDeterministicSearchContextAwarenessService() {
		org.junit.jupiter.api.Assertions.assertInstanceOf(
				com.minikun.search.internal.DefaultSearchContextAwarenessService.class,
				searchContextAwarenessService);
	}

	@Test
	void contextWiresCompositeInterestSignalProducerAsPrimaryProducer() {
		org.junit.jupiter.api.Assertions.assertInstanceOf(
				com.minikun.pcs.NoOpInterestSelectionSignalProducer.class,
				applicationContext.getBean("noOpInterestSelectionSignalProducer"));
		org.junit.jupiter.api.Assertions.assertInstanceOf(
				com.minikun.search.SearchSelectionSignalMapper.class,
				applicationContext.getBean("searchSelectionSignalMapper"));
		org.junit.jupiter.api.Assertions.assertInstanceOf(
				com.minikun.pcs.SearchInterestSelectionSignalProducer.class,
				applicationContext.getBean("searchInterestSelectionSignalProducer"));
		org.junit.jupiter.api.Assertions.assertInstanceOf(
				com.minikun.pcs.CompositeInterestSelectionSignalProducer.class,
				applicationContext.getBean(com.minikun.pcs.InterestSelectionSignalProducer.class));
	}

	@Test
	void contextWiresDeterministicQueryRewriteService() {
		String input = "\uFEFF\tＡ\u2003News  ";
		SearchQuery result = searchQueryRewriteService.rewrite(input);

		org.junit.jupiter.api.Assertions.assertEquals(input, result.originalQuery());
		org.junit.jupiter.api.Assertions.assertEquals("A News", result.rewrittenQuery());
	}

	@Test
	void contextWiresImmutableOrderedSynonymExpansionPipeline() {
		org.junit.jupiter.api.Assertions.assertInstanceOf(
				RuleBasedSearchQueryExpansionService.class, searchQueryExpansionService);
		org.junit.jupiter.api.Assertions.assertInstanceOf(
				ImmutableSynonymDictionary.class, synonymDictionary);

		ExpandedSearchQuery result =
				searchQueryExpansionService.expand(new SearchQuery("original", "latest Java"));

		org.junit.jupiter.api.Assertions.assertEquals(
				java.util.List.of("latest Java", "current Java", "Java platform"),
				result.expandedQueries());
	}

	@Test
	void contextWiresAcronymDictionaryAfterSynonymRule() {
		org.junit.jupiter.api.Assertions.assertInstanceOf(
				ImmutableAcronymDictionary.class, acronymDictionary);

		ExpandedSearchQuery result =
				searchQueryExpansionService.expand(new SearchQuery("original", "CI"));

		org.junit.jupiter.api.Assertions.assertEquals(
				java.util.List.of("CI", "Continuous Integration", "CI pipeline"),
				result.expandedQueries());
	}

	@Test
	void contextWiresAliasDictionaryAfterAcronymRule() {
		org.junit.jupiter.api.Assertions.assertInstanceOf(
				ImmutableAliasDictionary.class, aliasDictionary);

		ExpandedSearchQuery result =
				searchQueryExpansionService.expand(new SearchQuery("original", "postgres"));

		org.junit.jupiter.api.Assertions.assertEquals(
				java.util.List.of("postgres", "postgresql"), result.expandedQueries());
	}

	@Test
	void metricsDiscoveryListsMinikunMetricWithoutAssumingTotalMetricCount() throws Exception {
		searchDecisionService.decide("latest Java");

		MvcResult result = mockMvc.perform(get("/actuator/metrics"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.names").isArray())
				.andReturn();
		JsonNode names = new ObjectMapper().readTree(result.getResponse().getContentAsString()).path("names");

		org.junit.jupiter.api.Assertions.assertTrue(
				containsText(names, DECISION_METRIC),
				"Minikun decision metric must be discoverable");
	}

	@Test
	void metricDetailExposesMeasurementsAndAllowedTags() throws Exception {
		searchDecisionService.decide("latest Java");

		mockMvc.perform(get("/actuator/metrics/{name}", DECISION_METRIC))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value(DECISION_METRIC))
				.andExpect(jsonPath("$.measurements").isArray())
				.andExpect(jsonPath("$.measurements").isNotEmpty())
				.andExpect(jsonPath("$.availableTags").isArray());

		JsonNode detail = new ObjectMapper().readTree(
				mockMvc.perform(get("/actuator/metrics/{name}", DECISION_METRIC))
						.andReturn().getResponse().getContentAsString());
		for (JsonNode tag : detail.path("availableTags")) {
			org.junit.jupiter.api.Assertions.assertTrue(
					ALLOWED_TAGS.contains(tag.path("tag").asText()),
					"unexpected metric tag: " + tag.path("tag").asText());
		}
	}

	@Test
	void endpointOutsideExposureListIsNotExternallyAccessible() throws Exception {
		MvcResult result = mockMvc.perform(get("/actuator/env")).andReturn();

		org.junit.jupiter.api.Assertions.assertNotEquals(
				200, result.getResponse().getStatus(),
				"unexposed Actuator endpoint must not be accessible");
	}

	@Test
	void repeatedMetricReadsDoNotChangeRegisteredMeters() throws Exception {
		searchDecisionService.decide("latest Java");
		Set<String> minikunMetersBefore = minikunMeterNames();
		long timerCountBefore = meterRegistry.get(DECISION_METRIC).timer().count();

		mockMvc.perform(get("/actuator/metrics"));
		mockMvc.perform(get("/actuator/metrics/{name}", DECISION_METRIC));
		mockMvc.perform(get("/actuator/metrics/{name}", DECISION_METRIC));

		org.junit.jupiter.api.Assertions.assertEquals(minikunMetersBefore, minikunMeterNames());
		org.junit.jupiter.api.Assertions.assertEquals(
				timerCountBefore, meterRegistry.get(DECISION_METRIC).timer().count());
	}

	private Set<String> minikunMeterNames() {
		return meterRegistry.getMeters().stream()
				.map(meter -> meter.getId().getName())
				.filter(name -> name.startsWith("minikun."))
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private static boolean containsText(JsonNode values, String expected) {
		for (JsonNode value : values) {
			if (expected.equals(value.asText())) {
				return true;
			}
		}
		return false;
	}

}
