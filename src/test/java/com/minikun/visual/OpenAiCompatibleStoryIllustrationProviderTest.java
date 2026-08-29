package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleStoryIllustrationProviderTest {
    @Test
    void sendsOpenAiCompatibleRequestAndDecodesBase64Image() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://images.example/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        byte[] png = GeneratedImageStoreTest.png();
        server.expect(requestTo("https://images.example/v1/images/generations"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"model":"gpt-image-2","prompt":"a decisive scene","n":1,
                         "size":"1536x1024","quality":"medium"}
                        """))
                .andRespond(withSuccess("""
                        {"data":[{"b64_json":"%s","revised_prompt":"A moonlit scene"}]}
                        """.formatted(Base64.getEncoder().encodeToString(png)), MediaType.APPLICATION_JSON));
        StoryIllustrationProvider provider = new OpenAiCompatibleStoryIllustrationProvider(
                builder.build(), new ObjectMapper(), "gpt-image-2", "1536x1024", "medium", 1024);

        GeneratedImage image = provider.generate("a decisive scene");

        assertArrayEquals(png, image.bytes());
        assertEquals("A moonlit scene", image.revisedPrompt());
        assertEquals("gpt-image-2", image.provider());
        server.verify();
    }
}
