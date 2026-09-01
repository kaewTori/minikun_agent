package com.minikun.visual;

import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ImageStudioExceptionHandlerTest {
    private final ImageStudioExceptionHandler handler = new ImageStudioExceptionHandler();

    @Test
    void mapsOfflineTinyGradToServiceUnavailable() {
        var response = handler.generationFailure(new ImageGenerationException(
                ImageGenerationException.Code.UNAVAILABLE, "TinyGrad is offline"));

        assertEquals(503, response.getStatusCode().value());
        assertEquals("TinyGrad is offline", response.getBody().error().message());
        assertEquals("unavailable", response.getBody().error().code());
    }

    @Test
    void mapsTimeoutAndUpstreamFailuresToGatewayStatuses() {
        assertEquals(504, handler.generationFailure(new ImageGenerationException(
                ImageGenerationException.Code.TIMEOUT, "timed out")).getStatusCode().value());
        assertEquals(502, handler.generationFailure(new ImageGenerationException(
                ImageGenerationException.Code.UPSTREAM_FAILURE, "bad response")).getStatusCode().value());
    }

    @Test
    void registersAdviceWithTheImageGenerationModule() {
        new ApplicationContextRunner()
                .withUserConfiguration(StoryIllustrationConfiguration.class)
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                        ApplicationConversionService.getSharedInstance()))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("minikun.visual.generation.enabled=true")
                .run(context -> assertNotNull(context.getBean(ImageStudioExceptionHandler.class)));
    }
}
