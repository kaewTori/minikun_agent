package com.minikun.search;

import java.util.List;

@FunctionalInterface
public interface ExpansionRule {
    List<String> expand(String rewrittenQuery);
}
