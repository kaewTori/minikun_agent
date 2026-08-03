package com.minikun.pcs;

import com.minikun.character.model.CharacterSpecification;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class MinikunPersonaProvider {
    private final PersonaFragment fragment;

    public MinikunPersonaProvider(CharacterSpecification characterSpecification) {
        this.fragment = new PersonaFragment(CorePromptFragments.persona(
                Objects.requireNonNull(characterSpecification, "characterSpecification must not be null")));
    }

    public PersonaFragment fragment() {
        return fragment;
    }

    public record PersonaFragment(String content) {
        public PersonaFragment {
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("persona fragment must not be blank");
            }
        }
    }
}