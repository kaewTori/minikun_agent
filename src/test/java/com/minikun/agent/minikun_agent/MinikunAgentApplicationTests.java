package com.minikun.agent.minikun_agent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;

@SpringBootTest(properties = {
	"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration",
	"minikun.memory.persistence.enabled=false",
	"minikun.planner.enabled=false",
	"minikun.task.enabled=false",
	"minikun.agent.execution.enabled=false",
	"minikun.personal-knowledge.enabled=false"
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
		org.junit.jupiter.api.Assertions.assertNotNull(
				applicationContext.getBean(com.minikun.personality.companion.CompanionModeService.class));
	}

	@Test
	void contextWiresOwnerScopedAdaptiveCompanionApi() throws Exception {
		org.junit.jupiter.api.Assertions.assertNotNull(
				applicationContext.getBean(com.minikun.personality.learning.AdaptivePreferenceLearningService.class));

		mockMvc.perform(get("/v1/adaptation").param("owner_id", "test-owner"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ownerId").value("test-owner"))
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.activePreferences").isArray())
				.andExpect(jsonPath("$.evidence").isArray());

		mockMvc.perform(post("/v1/adaptation/feedback")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"owner_id":"test-owner","dimension":"response_length",
						 "value":"concise","positive":true}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.activePreferences[0].key").value("adaptive.response_length"))
				.andExpect(jsonPath("$.activePreferences[0].value").value("concise"));

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/v1/adaptation").param("owner_id", "test-owner"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deletedSignals").value(1))
				.andExpect(jsonPath("$.deletedPreferences").value(1));
	}

	@Test
	void contextWiresVisionInputAndRejectsUnsupportedImageThroughHttp() throws Exception {
		org.junit.jupiter.api.Assertions.assertNotNull(
				applicationContext.getBean(com.minikun.vision.VisionInputService.class));

		mockMvc.perform(post("/v1/chat/completions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"model":"mini-kun","messages":[{"role":"user","content":[
						  {"type":"text","text":"describe"},
						  {"type":"image_url","image_url":{"url":"data:image/gif;base64,R0lGODlh"}}
						]}]}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("invalid_image"))
				.andExpect(jsonPath("$.error.message").value(
						"unsupported image media type; use JPEG, PNG, or WebP"));
	}

	@Test
	void contextWiresLocalVoiceStatusWithoutExposingAudioStorage() throws Exception {
		org.junit.jupiter.api.Assertions.assertNotNull(
				applicationContext.getBean(com.minikun.voice.VoiceService.class));
		org.junit.jupiter.api.Assertions.assertNotNull(
				applicationContext.getBean(com.minikun.voice.VoiceHealthIndicator.class));

		mockMvc.perform(get("/v1/audio/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.localOnly").value(true))
				.andExpect(jsonPath("$.storesAudio").value(false))
				.andExpect(jsonPath("$.defaultVoice").value("minikun"));
	}

	@Test
	void voiceTranscriptionRejectsSpoofedAudioBeforeInvokingRuntime() throws Exception {
		org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
				"file", "voice.wav", "audio/wav", "not-a-wave".getBytes(java.nio.charset.StandardCharsets.UTF_8));

		mockMvc.perform(multipart("/v1/audio/transcriptions").file(file))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("invalid_audio"));
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
		org.junit.jupiter.api.Assertions.assertTrue(names.contains("homelab.guardian"));
		org.junit.jupiter.api.Assertions.assertTrue(names.contains("computer.local"));
		org.junit.jupiter.api.Assertions.assertTrue(names.contains("communication.assist"));
	}

	@Test
	void contextWiresDraftOnlyCommunicationAssistantStatus() throws Exception {
		org.junit.jupiter.api.Assertions.assertNotNull(
				applicationContext.getBean(com.minikun.communication.CommunicationService.class));

		mockMvc.perform(get("/v1/communication/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.storesContent").value(false))
				.andExpect(jsonPath("$.sendSupported").value(false))
				.andExpect(jsonPath("$.actions[0]").value("draft"));
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
