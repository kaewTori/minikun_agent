package com.minikun.visual;

/** Generates one image from a privacy-bounded story scene prompt. */
public interface StoryIllustrationProvider {
    GeneratedImage generate(String prompt);
}
