package com.minikun.search;

import java.util.List;

public interface AcronymDictionary {
    List<String> expansionsOf(String canonicalQuery);
}
