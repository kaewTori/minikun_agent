package com.minikun.memory.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.minikun.memory.MemoryPolicy;
import com.minikun.memory.model.CandidateMemory;
import com.minikun.memory.model.MemoryCategory;

final class MemoryValidator {
    ValidationResult validate(List<MemoryResponseParser.RawCandidate> rawCandidates, MemoryPolicy policy) {
        List<CandidateMemory> valid = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> fingerprints = new HashSet<>();
        for (int index = 0; index < rawCandidates.size(); index++) {
            var raw = rawCandidates.get(index);
            MemoryCategory category = parseCategory(raw.category(), index, errors);
            if (category == null) {
                continue;
            }
            if (raw.content() == null || raw.content().isBlank()) {
                errors.add(error(index, "content must not be blank"));
                continue;
            }
            if (raw.content().length() > policy.maximumContentLength()) {
                errors.add(error(index, "content exceeds maximum length"));
                continue;
            }
            if (!Double.isFinite(raw.confidence()) || raw.confidence() < 0.0 || raw.confidence() > 1.0) {
                errors.add(error(index, "confidence must be between 0 and 1"));
                continue;
            }
            if (raw.reason() == null || raw.reason().isBlank()) {
                errors.add(error(index, "reason must not be blank"));
                continue;
            }
            CandidateMemory candidate = new CandidateMemory(category, raw.content().trim(), raw.confidence(), raw.reason().trim());
            String fingerprint = category + "\u0000" + normalize(candidate.content());
            if (!fingerprints.add(fingerprint)) {
                errors.add(error(index, "duplicate candidate memory"));
                continue;
            }
            valid.add(candidate);
        }
        return new ValidationResult(List.copyOf(valid), List.copyOf(errors));
    }

    private MemoryCategory parseCategory(String value, int index, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(error(index, "category is required"));
            return null;
        }
        try {
            return MemoryCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            errors.add(error(index, "unsupported category: " + value));
            return null;
        }
    }

    private String normalize(String value) {
        return value.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String error(int index, String message) {
        return "candidate[" + index + "]: " + message;
    }

    record ValidationResult(List<CandidateMemory> valid, List<String> errors) {
    }
}
