package com.minikun.knowledge.acquisition;

import static com.minikun.knowledge.acquisition.KnowledgeAcquisitionModels.*;

import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative deterministic promotion gate; model confidence alone can never publish a claim. */
final class KnowledgeClaimVerifier {
    private final double minimumConfidence;

    KnowledgeClaimVerifier(double minimumConfidence) {
        if (!Double.isFinite(minimumConfidence) || minimumConfidence < .5 || minimumConfidence > 1) {
            throw new IllegalArgumentException("minimum verification confidence must be between 0.5 and 1");
        }
        this.minimumConfidence = minimumConfidence;
    }

    Verification verify(Topic topic, ClaimDraft draft, List<KnowledgeCandidate> evidence) {
        if (looksLikePromptInjection(draft.text())) {
            return new Verification(ClaimStatus.RETRACTED, 0, "claim contains instruction-like untrusted content");
        }
        if (draft.extractionMethod() == ExtractionMethod.FALLBACK) {
            return new Verification(ClaimStatus.CANDIDATE, draft.confidence(),
                    "task-model extraction failed; excerpt requires review");
        }
        if (tokens(draft.text()).size() < 5) {
            return new Verification(ClaimStatus.CANDIDATE, Math.min(.6, draft.confidence()),
                    "claim is too terse to be useful as durable knowledge");
        }
        if (isSecurityAdvisoryTopic(topic) && !SECURITY_ADVISORY_SIGNAL.matcher(draft.text()).find()) {
            return new Verification(ClaimStatus.CANDIDATE, Math.min(.6, draft.confidence()),
                    "security topic requires an advisory, vulnerability, CVE, affected-version, or fix signal");
        }

        Set<String> hosts = new LinkedHashSet<>();
        Set<String> groundedRenderedHosts = new LinkedHashSet<>();
        boolean hasRenderedSource = false;
        boolean hasExtractiveRenderedSource = false;
        boolean trusted = false;
        for (int index : draft.evidenceIndexes()) {
            if (index < 0 || index >= evidence.size()) continue;
            KnowledgeCandidate candidate = evidence.get(index);
            String host = host(candidate.provenance());
            if (!host.isBlank()) hosts.add(host);
            hasRenderedSource |= candidate.source() == KnowledgeSource.BROWSER;
            if (candidate.source() == KnowledgeSource.BROWSER
                    && extractivelyGrounded(draft.text(), candidate.content())) {
                hasExtractiveRenderedSource = true;
                if (relevantToTopic(topic, candidate)) {
                    if (!host.isBlank()) groundedRenderedHosts.add(host);
                    trusted |= topic.trustedDomains().stream().anyMatch(domain -> matches(host, domain));
                }
            }
        }
        if (!hasRenderedSource) {
            return new Verification(ClaimStatus.CANDIDATE, Math.min(.55, draft.confidence()),
                    "only search snippets support the claim; the original source must be read");
        }
        if (draft.confidence() < minimumConfidence) {
            return new Verification(ClaimStatus.CANDIDATE, draft.confidence(),
                    "extraction confidence is below the publication threshold");
        }
        if (!hasExtractiveRenderedSource) {
            return new Verification(ClaimStatus.CANDIDATE, Math.min(.65, draft.confidence()),
                    "claim is not an extractive span of its cited rendered evidence");
        }
        if (groundedRenderedHosts.isEmpty()) {
            return new Verification(ClaimStatus.CANDIDATE, Math.min(.65, draft.confidence()),
                    "cited rendered evidence is not relevant to the topic objective");
        }
        if (topic.sourcePolicy() == SourcePolicy.OFFICIAL_ONLY && !trusted) {
            return new Verification(ClaimStatus.CANDIDATE, draft.confidence(),
                    "topic accepts only configured trusted domains");
        }
        if (trusted) {
            return new Verification(ClaimStatus.PUBLISHED, Math.max(.85, draft.confidence()),
                    "supported by a rendered source on a configured trusted domain");
        }
        if (groundedRenderedHosts.size() >= 2 && topic.sourcePolicy() != SourcePolicy.OFFICIAL_ONLY) {
            return new Verification(ClaimStatus.PUBLISHED, draft.confidence(),
                    "corroborated by rendered evidence from independent domains");
        }
        return new Verification(ClaimStatus.CANDIDATE, draft.confidence(),
                "single non-trusted source requires review or corroboration");
    }

    private boolean looksLikePromptInjection(String value) {
        String text = value.toLowerCase(Locale.ROOT);
        return text.contains("ignore previous instructions")
                || text.contains("disregard the system prompt")
                || text.contains("reveal the system prompt")
                || text.contains("follow these instructions instead")
                || text.contains("you are now an ai assistant");
    }

    private String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private boolean matches(String host, String domain) {
        String normalized = domain.toLowerCase(Locale.ROOT);
        return host.equals(normalized) || host.endsWith("." + normalized);
    }

    private boolean extractivelyGrounded(String claim, String evidence) {
        String normalizedClaim = normalize(claim);
        String normalizedEvidence = normalize(evidence);
        return !normalizedClaim.isBlank() && normalizedEvidence.contains(normalizedClaim);
    }

    private boolean relevantToTopic(Topic topic, KnowledgeCandidate candidate) {
        Set<String> coreTerms = topicTerms(topic.objective());
        if (coreTerms.isEmpty()) return true;
        Set<String> sourceTerms = tokens(candidate.provenance() + " " + candidate.content());
        long matches = coreTerms.stream().filter(sourceTerms::contains).count();
        return matches >= Math.min(2, coreTerms.size());
    }

    private Set<String> topicTerms(String value) {
        Set<String> values = tokens(value);
        values.removeAll(GENERIC_TOPIC_TERMS);
        return values;
    }

    private Set<String> tokens(String value) {
        Set<String> values = new LinkedHashSet<>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2) values.add(token);
        }
        return values;
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private boolean isSecurityAdvisoryTopic(Topic topic) {
        String value = (topic.name() + " " + topic.objective()).toLowerCase(Locale.ROOT);
        return value.contains("security advis") || value.contains("vulnerabil") || value.contains(" cve");
    }

    private static final Pattern SECURITY_ADVISORY_SIGNAL = Pattern.compile(
            "(?iu)(cve-\\d{4}-\\d+|vulnerab|security\\s+(?:advis|update|fix|patch)|cvss|"
                    + "affected\\s+versions?|patched|denial\\s+of\\s+service|remote\\s+code\\s+execution|"
                    + "privilege\\s+escalation)");

    private static final Set<String> GENERIC_TOPIC_TERMS = Set.of(
            "identify", "track", "find", "learn", "monitor", "current", "latest", "stable",
            "capability", "capabilities", "feature", "features", "documented", "documentation",
            "reference", "official", "change", "changes", "update", "updates", "information",
            "about", "from", "with", "that", "this", "the", "and", "for");
}
