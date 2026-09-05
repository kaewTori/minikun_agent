package com.minikun.agent.minikun_agent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.SearchContextAwarenessService;
import com.minikun.search.SearchQueryExpansionService;
import com.minikun.search.SearchQueryRewriteService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("database-offline")
@AutoConfigureMockMvc
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
	private ToolRegistry toolRegistry;

	@Test
	void contextLoads() {
		org.junit.jupiter.api.Assertions.assertTrue(
				applicationContext.getBeansOfType(javax.sql.DataSource.class).isEmpty());
		var persistenceHealth = applicationContext.getBean(
				com.minikun.agent.minikun_agent.conversation.ConversationPersistenceHealthIndicator.class).health();
		org.junit.jupiter.api.Assertions.assertEquals(
				"DEGRADED", persistenceHealth.getDetails().get("status"));
		org.junit.jupiter.api.Assertions.assertNotNull(
				applicationContext.getBean(com.minikun.personality.companion.CompanionModeService.class));
		org.junit.jupiter.api.Assertions.assertNotNull(
				applicationContext.getBean(com.minikun.personality.management.PersonaManagementController.class));
		org.junit.jupiter.api.Assertions.assertNotNull(
				applicationContext.getBean(com.minikun.memory.DeferredReflectionService.class));
		org.junit.jupiter.api.Assertions.assertNotNull(
				applicationContext.getBean(com.minikun.research.AutonomousResearchService.class));
		org.junit.jupiter.api.Assertions.assertNotNull(
				applicationContext.getBean(com.minikun.knowledge.acquisition.KnowledgeAcquisitionService.class));
	}

	@Test
	void healthEndpointExposesDatabaseOfflineMode() throws Exception {
		mockMvc.perform(get("/actuator/health/conversationPersistence"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DEGRADED"));
	}

	@Test
	void contextWiresKnowledgeAcquisitionManagementApi() throws Exception {
		mockMvc.perform(get("/v1/knowledge/acquisition/topics").param("owner_id", "test-owner"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
	}

	@Test
	void contextWiresEvalLabWithoutExecutingAChatTurn() throws Exception {
		mockMvc.perform(get("/v1/evals/turn-plans/baseline"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(6))
				.andExpect(jsonPath("$.passed").value(6))
				.andExpect(jsonPath("$.scorePercent").value(100.0));

		mockMvc.perform(get("/v1/evals/turn-plans/quality").param("owner_id", "eval-http-test"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.feedbackTotal").value(0))
				.andExpect(jsonPath("$.shadowSignals").isArray());
	}

	@Test
	void cockpitHasStableEntryPointAndServesTheLocalDashboard() throws Exception {
		mockMvc.perform(get("/cockpit"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("/cockpit/index.html"));

		mockMvc.perform(get("/cockpit/"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("/cockpit/index.html"));

		mockMvc.perform(get("/cockpit/").secure(true).header("Host", "127.0.0.1:8443"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("/cockpit/index.html"));

		mockMvc.perform(get("/cockpit/").secure(true).header("Host", "mini-kun:8443"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("/cockpit/index.html"));

		mockMvc.perform(get("/pair").secure(true).header("Host", "mini-kun:8443"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("/cockpit/pair.html"));

		mockMvc.perform(get("/cockpit/index.html"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
							.string(org.hamcrest.Matchers.allOf(
									org.hamcrest.Matchers.containsString("YOUR PERSONAL AGENT"),
									org.hamcrest.Matchers.containsString("MINIKUN PULSE"),
									org.hamcrest.Matchers.containsString("href=\"#main-content\""),
									org.hamcrest.Matchers.containsString("data-cockpit-target=\"today\""),
									org.hamcrest.Matchers.containsString("data-cockpit-target=\"memory\""),
									org.hamcrest.Matchers.containsString("id=\"health-retry\""),
									org.hamcrest.Matchers.containsString("id=\"eval-lab\""),
									org.hamcrest.Matchers.containsString("id=\"health-updated\""))));

		mockMvc.perform(get("/cockpit/minikun-avatar.jpg"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
						.contentTypeCompatibleWith(MediaType.IMAGE_JPEG));

		mockMvc.perform(get("/cockpit/cockpit-chat-assets.js"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
						.string(org.hamcrest.Matchers.containsString("MinikunChatAssets")));
	}

	@Test
	void cockpitCanCreateStartAndCheckInAPersonalExperimentThroughHttp() throws Exception {
		MvcResult created = mockMvc.perform(post("/v1/personal/experiments")
				.param("owner_id", "experiment-http-test")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"conversationId":"cockpit","title":"อ่านก่อนนอน",
						 "hypothesis":"อ่านก่อนนอนช่วยลดเวลาหน้าจอ","protocol":"อ่าน 20 นาที",
						 "metricName":"นาทีอ่าน","metricUnit":"นาที","direction":"INCREASE",
						 "baselineValue":5,"targetValue":20,"durationDays":7}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.experiment.status").value("DRAFT"))
				.andExpect(jsonPath("$.outcome.status").value("PROPOSED"))
				.andReturn();
		String id = new ObjectMapper().readTree(created.getResponse().getContentAsString())
				.path("experiment").path("id").asText();

		mockMvc.perform(post("/v1/personal/experiments/{id}/transition", id)
				.param("owner_id", "experiment-http-test")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"action\":\"START\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.experiment.status").value("RUNNING"));

		mockMvc.perform(post("/v1/personal/experiments/{id}/check-ins", id)
				.param("owner_id", "experiment-http-test")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"value\":14,\"note\":\"ทำได้จริง\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.analysis.checkInCount").value(1))
				.andExpect(jsonPath("$.analysis.progressPercent").value(60));
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
		org.junit.jupiter.api.Assertions.assertTrue(names.contains("image.generate"));
		org.junit.jupiter.api.Assertions.assertTrue(names.contains("service.health"));
		org.junit.jupiter.api.Assertions.assertTrue(names.contains("system.health"));
		org.junit.jupiter.api.Assertions.assertTrue(names.contains("homelab.guardian"));
		org.junit.jupiter.api.Assertions.assertTrue(names.contains("computer.local"));
		org.junit.jupiter.api.Assertions.assertTrue(names.contains("communication.assist"));
		org.junit.jupiter.api.Assertions.assertTrue(names.contains("personal.loop"));
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
	void contextWiresPreviewFirstPersonalLoopApiWithoutDatasource() throws Exception {
		org.junit.jupiter.api.Assertions.assertNotNull(
				applicationContext.getBean(com.minikun.personalloop.OutcomeLearningService.class));
		org.junit.jupiter.api.Assertions.assertNotNull(
				applicationContext.getBean(com.minikun.personalloop.ExplainabilityService.class));

		MvcResult captured = mockMvc.perform(post("/v1/personal/inbox")
				.param("owner_id", "test-owner")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"conversationId":"home","inputType":"TEXT",
						 "content":"บันทึกไอเดียสำหรับสวน","classification":"NOTE","metadata":{}}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PREVIEW"))
				.andExpect(jsonPath("$.classification").value("NOTE"))
				.andReturn();
		String id = new ObjectMapper().readTree(captured.getResponse().getContentAsString()).path("id").asText();

		mockMvc.perform(post("/v1/personal/inbox/{id}/commit", id).param("owner_id", "test-owner"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMMITTED"))
				.andExpect(jsonPath("$.targetType").value("NOTE"));

		mockMvc.perform(get("/v1/personal/timeline").param("owner_id", "test-owner"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].eventType").value("INBOX_COMMITTED"));
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
	void contextWiresQueryExpansionPipeline() {
		org.junit.jupiter.api.Assertions.assertInstanceOf(
				RuleBasedSearchQueryExpansionService.class, searchQueryExpansionService);

		ExpandedSearchQuery result =
				searchQueryExpansionService.expand(new SearchQuery("original", "latest Java"));

		org.junit.jupiter.api.Assertions.assertEquals(
				java.util.List.of("latest Java", "current Java", "Java platform"),
				result.expandedQueries());
	}

	@Test
	void expandsAcronyms() {
		ExpandedSearchQuery result =
				searchQueryExpansionService.expand(new SearchQuery("original", "CI"));

		org.junit.jupiter.api.Assertions.assertEquals(
				java.util.List.of("CI", "Continuous Integration", "CI pipeline"),
				result.expandedQueries());
	}

	@Test
	void expandsAliases() {
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
