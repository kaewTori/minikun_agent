package com.minikun.search;

import java.util.List;

public interface SynonymDictionary {
    List<String> synonymsOf(String canonicalQuery);
}