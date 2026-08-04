package com.minikun.character;

import com.minikun.character.model.CharacterSpecification;
import com.minikun.character.model.LoadingPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterLoaderTest {
    private static final Path MCS_ROOT = Path.of("../../config/minikun-agent/mcs");

    @Test
    void loadsCurrentMinikunSpecification() {
        CharacterSpecification specification = new CharacterLoader(MCS_ROOT).load();

        assertEquals("Minikun", specification.name());
        assertEquals("2.1.0", specification.version());
        assertEquals("th", specification.primaryLanguage());
        assertTrue(specification.personality().statements().contains("Warm"));
        assertTrue(specification.identity().statements().contains("Personal AI Companion"));
    }

    @Test
    void returnedSectionsAreImmutable() {
        CharacterSpecification specification = new CharacterLoader(MCS_ROOT).load();

        assertThrows(UnsupportedOperationException.class,
                () -> specification.values().statements().add("new value"));
    }

        @Test
        void loadsImmutableSelectionMetadataSeparatelyFromInterestStatements() {
        CharacterSpecification specification = new CharacterLoader(MCS_ROOT).load();

        assertEquals(List.of("Programming", "Artificial Intelligence", "Linux"),
            specification.selectionMetadata().get("interests").literalTerms().subList(0, 3));
        assertEquals(List.of("hello", "plan", "explain"),
            specification.selectionMetadata().get("catchphrases").literalTerms());
        assertThrows(UnsupportedOperationException.class,
            () -> specification.selectionMetadata().get("interests").literalTerms().add("later"));
        assertThrows(UnsupportedOperationException.class,
            () -> specification.selectionMetadata().get("catchphrases").literalTerms().add("later"));
        }

    @Test
    void exposesLoadingPoliciesFromManifest() {
        CharacterSpecification specification = new CharacterLoader(MCS_ROOT).load();

        assertEquals(LoadingPolicy.ALWAYS, specification.loadingPolicies().get("identity"));
        assertEquals(LoadingPolicy.DYNAMIC, specification.loadingPolicies().get("catchphrases"));
        assertEquals(9, specification.loadingPolicies().size());
    }

    @Test
    void parsesDynamicPolicy() throws Exception {
        Path directory = Files.createTempDirectory("mcs-dynamic-");
        try {
            Files.walk(MCS_ROOT).filter(Files::isRegularFile).forEach(source -> {
                try {
                    Path target = directory.resolve(MCS_ROOT.relativize(source).toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            String manifest = Files.readString(directory.resolve("manifest.yaml"))
                    .replace("identity:\n    loadingPolicy: ALWAYS", "identity:\n    loadingPolicy: DYNAMIC");
            Files.writeString(directory.resolve("manifest.yaml"), manifest);

            assertEquals(LoadingPolicy.DYNAMIC,
                    new CharacterLoader(directory).load().loadingPolicies().get("identity"));
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void legacyManifestDefaultsEverySectionToAlways() throws Exception {
        Path directory = Files.createTempDirectory("mcs-legacy-");
        try {
            Files.walk(MCS_ROOT).filter(Files::isRegularFile).forEach(source -> {
                try {
                    Path target = directory.resolve(MCS_ROOT.relativize(source).toString());
                    Files.createDirectories(target.getParent());
                    if (!source.getFileName().toString().equals("manifest.yaml")) {
                        Files.copy(source, target);
                    }
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            String legacyManifest = Files.readString(MCS_ROOT.resolve("manifest.yaml"))
                    .substring(0, Files.readString(MCS_ROOT.resolve("manifest.yaml")).indexOf("modules:"));
            Files.writeString(directory.resolve("manifest.yaml"), legacyManifest);

            Map<String, LoadingPolicy> policies = new CharacterLoader(directory).load().loadingPolicies();
            assertEquals(9, policies.size());
            assertTrue(policies.values().stream().allMatch(policy -> policy == LoadingPolicy.ALWAYS));
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }
}
