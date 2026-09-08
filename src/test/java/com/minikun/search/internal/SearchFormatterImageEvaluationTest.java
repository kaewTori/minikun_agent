package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.model.ImageSearchResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchFormatterImageEvaluationTest {
    @Test
    void keepsPrecisionAtSixAcrossRepresentativeImageQueries() {
        List<EvaluationCase> cases = List.of(
                new EvaluationCase("หารูปแมว", "แมว", List.of(
                        image("https://cdn.example/cat.jpg", "แมวบ้าน", "https://cats.example/cat", "แมวน่ารัก"),
                        image("https://cdn.example/mountain.jpg", "ภูเขา", "https://travel.example/mountain", "วิว"))),
                new EvaluationCase("RenaRaziel artwork", "renaraziel", List.of(
                        image("https://cdn.example/rena.jpg", "RenaRaziel artwork", "https://artist.example/rena", "portfolio"),
                        image("https://cdn.example/generic.jpg", "Artwork portfolio", "https://art.example/gallery", "artwork"))),
                new EvaluationCase("Tesla Model 3", "tesla", List.of(
                        image("https://cdn.example/tesla.jpg", "Tesla Model 3", "https://cars.example/tesla", "electric car"),
                        image("https://cdn.example/model.jpg", "Model 3D render", "https://render.example/model", "3D model"))),
                new EvaluationCase("Eiffel Tower", "eiffel tower", List.of(
                        image("https://cdn.example/eiffel.jpg", "Eiffel Tower", "https://travel.example/eiffel", "Paris landmark"),
                        image("https://cdn.example/crane.jpg", "Tower crane", "https://construction.example/crane", "construction"))),
                new EvaluationCase("เชียงใหม่", "เชียงใหม่", List.of(
                        image("https://cdn.example/chiangmai.jpg", "เชียงใหม่", "https://thailand.example/chiangmai", "เมืองเชียงใหม่"),
                        image("https://cdn.example/bangkok.jpg", "กรุงเทพ", "https://thailand.example/bangkok", "เมือง"))));

        for (EvaluationCase testCase : cases) {
            KnowledgeContext result = new SearchFormatter().formatImages(testCase.candidates(), 6, testCase.query());

            assertTrue(!result.images().isEmpty(), testCase.query());
            long relevant = result.images().stream()
                    .filter(image -> (image.title() + " " + image.description())
                            .toLowerCase().contains(testCase.anchor()))
                    .count();
            assertEquals(result.images().size(), relevant, "precision@6 for " + testCase.query());
            assertTrue(result.images().size() <= 6);
        }
    }

    @Test
    void limitsOneSourceAndDeduplicatesCdnCopies() {
        SearchFormatter formatter = new SearchFormatter();
        KnowledgeContext result = formatter.formatImages(List.of(
                image("https://cdn-a.example/one.jpg", "Cat one", "https://cats.example/gallery", "cat"),
                image("https://cdn-b.example/two.jpg?resize=1", "Cat one", "https://cats.example/gallery", "cat"),
                image("https://cdn-a.example/three.jpg", "Cat three", "https://cats.example/three", "cat"),
                image("https://cdn-a.example/four.jpg", "Cat four", "https://cats.example/four", "cat"),
                image("https://cdn-a.example/five.jpg", "Cat five", "https://other.example/five", "cat")),
                10, "cat");

        assertEquals(3, result.images().size());
        assertEquals(2, result.images().stream()
                .filter(image -> image.sourceUrl().contains("cats.example"))
                .count());
        assertEquals(List.of("https://cdn-a.example/one.jpg", "https://cdn-a.example/three.jpg",
                "https://cdn-a.example/five.jpg"), result.images().stream().map(image -> image.url()).toList());
    }

    @Test
    void recordsCandidateAcceptedRejectedProviderAndDomainMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SearchFormatter formatter = new SearchFormatter(registry);

        formatter.formatImages(List.of(
                new ImageSearchResult("https://cdn.example/cat.jpg", "Cat", "https://cats.example/cat",
                        "cat", "", null, null, "searxng", ""),
                new ImageSearchResult("https://cdn.example/mountain.jpg", "Mountain", "https://mountains.example/mountain",
                        "mountain", "", null, null, "searxng", "")), 6, "cat");

        assertEquals(2.0, registry.get("minikun.search.images.candidates")
                .tag("provider", "searxng").tag("domain", "cats.example").counter().count()
                + registry.get("minikun.search.images.candidates")
                .tag("provider", "searxng").tag("domain", "mountains.example").counter().count());
        assertEquals(1.0, registry.get("minikun.search.images.accepted")
                .tag("provider", "searxng").tag("domain", "cats.example").counter().count());
        assertEquals(1.0, registry.get("minikun.search.images.rejected")
                .tag("provider", "searxng").tag("domain", "mountains.example")
                .tag("reason", "unrelated").counter().count());
    }

    private static ImageSearchResult image(String url, String title, String sourceUrl, String description) {
        return new ImageSearchResult(url, title, sourceUrl, description, "", null, null, "searxng", "");
    }

    private record EvaluationCase(String query, String anchor, List<ImageSearchResult> candidates) {
    }
}
