package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionContextArchitectureTest {
    private static final Path MAIN_SOURCE = Path.of("src/main/java/com/minikun/pcs");

    @Test
    void factoryHasNoMutableOrStaticState() {
        for (var field : SelectionContextFactory.class.getDeclaredFields()) {
            assertTrue(Modifier.isFinal(field.getModifiers()),
                    "factory field must be final: " + field.getName());
            assertFalse(Modifier.isStatic(field.getModifiers()),
                    "factory must not have static state: " + field.getName());
        }
    }

    @Test
    void factoryHasNoForbiddenDependenciesOrEnvironmentBranches() throws Exception {
        String source = Files.readString(MAIN_SOURCE.resolve("SelectionContextFactory.java"));
        List<String> forbiddenReferences = List.of(
                "org.springframework", "com.minikun.memory", "com.minikun.search",
                "com.minikun.tools", "infrastructure", "McsSelector",
                "System.getProperty", "System.getenv", "Locale.getDefault");

        for (String forbidden : forbiddenReferences) {
            assertFalse(source.contains(forbidden), "forbidden factory reference: " + forbidden);
        }
    }

    @Test
    void productionConstructionPathExistsOnlyInFactory() throws Exception {
        try (var paths = Files.walk(MAIN_SOURCE)) {
            List<String> directConstructionSites = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains("new McsSelectionContext");
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .map(path -> path.getFileName().toString())
                    .toList();

            assertTrue(directConstructionSites.equals(List.of("SelectionContextFactory.java")),
                    "unexpected production construction sites: " + directConstructionSites);
        }
    }

    @Test
    void onlyProducerImplementationsConstructInterestSelectionSignals() throws Exception {
        try (var paths = Files.walk(MAIN_SOURCE)) {
            List<String> directConstructionSites = paths
                    .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.getFileName().toString().equals("InterestSelectionSignals.java"))
                        .filter(path -> !path.getFileName().toString()
                            .equals("SearchInterestSelectionSignalProducer.java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains("new InterestSelectionSignals");
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .map(path -> path.getFileName().toString())
                    .toList();

            assertTrue(directConstructionSites.isEmpty(),
                    "unexpected signal construction sites: " + directConstructionSites);
        }
    }

    @Test
    void factoryDependsOnTheProducerContractOnly() throws Exception {
        assertEquals(InterestSelectionSignalProducer.class,
                SelectionContextFactory.class.getDeclaredField("signalProducer").getType());
    }

    @Test
    void selectionContextDoesNotDependOnFactory() throws Exception {
        String source = Files.readString(MAIN_SOURCE.resolve("McsSelectionContext.java"));

        assertFalse(source.contains("SelectionContextFactory"));
    }
}
