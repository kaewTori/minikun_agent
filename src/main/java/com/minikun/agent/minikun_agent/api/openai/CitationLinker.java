package com.minikun.agent.minikun_agent.api.openai;

import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSelection;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Replaces model-visible evidence IDs with user-facing Markdown links. */
final class CitationLinker {
    private static final Pattern INTERNAL_REFERENCE = Pattern.compile(
            "(?i)(?:[a-z0-9]+-)*(?:search|browser|personal|memory|acquired)(?:-[a-z0-9]+)+");
    // ponytail: bounded 8KiB citation buffer; use incremental Markdown tokens if larger streamed URLs become common.
    private static final int MAX_PENDING_BRACKET = 8192;

    private CitationLinker() { }

    static Context from(KnowledgeSelection selection) {
        if (selection == null || selection.selectedCandidates().isEmpty()) return Context.EMPTY;
        Map<String, Link> links = new LinkedHashMap<>();
        for (KnowledgeCandidate candidate : selection.selectedCandidates()) {
            link(candidate).ifPresent(link -> links.put(candidate.candidateId(), link));
        }
        return links.isEmpty() ? Context.EMPTY : new Context(links);
    }

    static String normalize(String content, Context context) {
        String value = Objects.requireNonNullElse(content, "");
        Context citations = context == null ? Context.EMPTY : context;
        StringBuilder result = new StringBuilder(value.length() + 64);
        int cursor = 0;
        while (cursor < value.length()) {
            int open = value.indexOf('[', cursor);
            if (open < 0) {
                result.append(value, cursor, value.length());
                break;
            }
            int close = value.indexOf(']', open + 1);
            if (close < 0) {
                result.append(value, cursor, value.length());
                break;
            }
            result.append(value, cursor, open);
            String body = value.substring(open + 1, close).strip();
            boolean existingMarkdownLink = close + 1 < value.length() && value.charAt(close + 1) == '(';
            if (existingMarkdownLink) {
                int end = markdownTargetEnd(value, close + 2);
                String target = value.substring(close + 2, end);
                Link source = canonicalLink(target, citations);
                if (source != null) {
                    String title = body.matches("(?i)^<?https?://.*") ? source.title() : body;
                    result.append(new Link(title, source.url()).markdown());
                    cursor = end < value.length() && value.charAt(end) == ')' ? end + 1 : end;
                    continue;
                }
                result.append(value, open, close + 1);
            } else {
                String replacement = replacement(body, citations);
                result.append(replacement == null ? value.substring(open, close + 1) : replacement);
            }
            cursor = close + 1;
        }
        return result.toString().replaceAll("[ \\t]+([,.;:!?])", "$1");
    }

    static Stream stream(Context context) {
        return new Stream(context == null ? Context.EMPTY : context);
    }

    private static int markdownTargetEnd(String value, int start) {
        int depth = 1;
        boolean angle = start < value.length() && value.charAt(start) == '<';
        for (int index = start; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character)) return index;
            if (angle) { if (character == '>') angle = false; continue; }
            if (character == '(') depth++;
            if (character == ')' && --depth == 0) return index;
        }
        return value.length();
    }

    private static Link canonicalLink(String target, Context context) {
        String value = target.startsWith("<") && target.endsWith(">")
                ? target.substring(1, target.length() - 1) : target;
        int dots = value.indexOf("...");
        int ellipsis = value.indexOf('…');
        int cut = dots < 0 ? ellipsis : ellipsis < 0 ? dots : Math.min(dots, ellipsis);
        var malformedPercent = Pattern.compile("%(?![0-9a-fA-F]{2})").matcher(value);
        if (malformedPercent.find()) cut = cut < 0 ? malformedPercent.start() : Math.min(cut, malformedPercent.start());
        boolean truncated = cut >= 0;
        String prefix = truncated ? value.substring(0, cut) : value;
        if (!prefix.matches("(?i)^https?://.*")) return null;
        URI exact;
        try { exact = URI.create(URI.create(prefix).toASCIIString()); }
        catch (IllegalArgumentException exception) { return null; }
        Map<String, Link> matches = new LinkedHashMap<>();
        for (Link link : context.links().values()) {
            if (URI.create(link.url()).equals(exact) || truncated && link.url().startsWith(prefix)) matches.put(link.url(), link);
        }
        return matches.size() == 1 ? matches.values().iterator().next() : null;
    }

    private static String replacement(String body, Context context) {
        if (body.isBlank()) return null;
        String[] ids = body.split("\\s*[,;]\\s*");
        List<Link> links = new ArrayList<>();
        boolean allInternal = true;
        for (String rawId : ids) {
            String id = rawId.strip();
            Link link = context.links().get(id);
            if (link != null && links.stream().noneMatch(existing -> existing.url().equals(link.url()))) {
                links.add(link);
            }
            allInternal &= context.links().containsKey(id) || INTERNAL_REFERENCE.matcher(id).matches();
        }
        if (!links.isEmpty()) {
            return links.stream().map(Link::markdown).reduce((left, right) -> left + ", " + right).orElse("");
        }
        return allInternal ? "" : null;
    }

    private static java.util.Optional<Link> link(KnowledgeCandidate candidate) {
        String url = candidate.provenance().strip();
        if (url.contains("...") || url.contains("…")) return java.util.Optional.empty();
        try {
            URI uri = URI.create(url);
            String scheme = Objects.requireNonNullElse(uri.getScheme(), "").toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null) {
                return java.util.Optional.empty();
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            String title = title(candidate.content(), url, host);
            return java.util.Optional.of(new Link(title, uri.toASCIIString()));
        } catch (RuntimeException exception) {
            return java.util.Optional.empty();
        }
    }

    private static String title(String content, String url, String fallback) {
        String value = Objects.requireNonNullElse(content, "").replaceAll("\\s+", " ").strip();
        int urlMarker = value.indexOf(" (" + url + ")");
        String title = urlMarker > 0 ? value.substring(0, urlMarker).strip() : fallback;
        if (title.isBlank() || title.length() > 90 || title.startsWith("[")) title = fallback;
        return title.replace('[', '(').replace(']', ')');
    }

    record Context(Map<String, Link> links) {
        static final Context EMPTY = new Context(Map.of());

        Context {
            links = links == null ? Map.of() : Map.copyOf(links);
        }
    }

    record Link(String title, String url) {
        Link {
            title = Objects.requireNonNullElse(title, "source").strip();
            url = Objects.requireNonNullElse(url, "").strip();
        }

        String markdown() {
            String target = url.contains("(") || url.contains(")") ? "<" + url + ">" : url;
            return "[" + title + "](" + target + ")";
        }
    }

    static final class Stream {
        private final Context context;
        private String pending = "";

        private Stream(Context context) { this.context = context; }

        String accept(String chunk) {
            String combined = pending + Objects.requireNonNullElse(chunk, "");
            pending = "";
            int lastOpen = combined.lastIndexOf('[');
            int close = lastOpen < 0 ? -1 : combined.indexOf(']', lastOpen);
            boolean incomplete = lastOpen >= 0 && (close < 0 || close == combined.length() - 1
                    || combined.charAt(close + 1) == '(' && markdownTargetEnd(combined, close + 2) == combined.length());
            if (incomplete && combined.length() - lastOpen <= MAX_PENDING_BRACKET) {
                pending = combined.substring(lastOpen);
                combined = combined.substring(0, lastOpen);
            }
            return normalize(combined, context);
        }

        String finish() {
            String value = normalize(pending, context);
            pending = "";
            return value;
        }
    }
}
