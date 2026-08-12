package com.minikun.tokenbudget.integration;

import com.minikun.model.GenerationOptions;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationOptionsResolverTest {
    private final GenerationOptionsResolver resolver = new DefaultGenerationOptionsResolver();

    @Test
    void replacesMaxTokensAndPreservesExistingSettings() {
        GenerationOptions existing = new GenerationOptions(0.7, 100, List.of("END"));
        TokenBudgetAllocation allocation = new TokenBudgetAllocation(1000, 2048, false);

        GenerationOptions resolved = resolver.resolve(existing, allocation);

        assertEquals(2048, resolved.maxTokens());
        assertEquals(0.7, resolved.temperature());
        assertEquals(List.of("END"), resolved.stop());
    }

    @Test
    void createsOptionsWhenExistingOptionsAreNull() {
        TokenBudgetAllocation allocation = new TokenBudgetAllocation(1000, 512, false);

        GenerationOptions resolved = resolver.resolve(null, allocation);

        assertEquals(512, resolved.maxTokens());
        assertEquals(null, resolved.temperature());
        assertEquals(List.of(), resolved.stop());
    }

    @Test
    void mapsZeroAvailableOutputTokens() {
        GenerationOptions resolved = resolver.resolve(
                null, new TokenBudgetAllocation(4096, 0, true));

        assertEquals(0, resolved.maxTokens());
    }

    @Test
    void rejectsNullAllocation() {
        assertThrows(NullPointerException.class, () -> resolver.resolve(null, null));
    }

    @Test
    void doesNotMutateExistingOptions() {
        GenerationOptions existing = new GenerationOptions(0.7, 100, List.of("END"));
        TokenBudgetAllocation allocation = new TokenBudgetAllocation(1000, 2048, false);

        resolver.resolve(existing, allocation);

        assertEquals(100, existing.maxTokens());
        assertEquals(0.7, existing.temperature());
        assertEquals(List.of("END"), existing.stop());
    }

    @Test
    void rejectsOutputTokensBeyondIntegerMaximum() {
        TokenBudgetAllocation allocation = new TokenBudgetAllocation(0, (long) Integer.MAX_VALUE + 1, false);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(null, allocation));
    }
}
