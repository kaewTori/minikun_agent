package com.minikun.search;

import java.util.List;

public interface AliasDictionary {
    List<String> aliasesOf(String canonicalQuery);
}
