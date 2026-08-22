package com.minikun.agent.minikun_agent.api.cockpit;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Provides a stable, human-friendly entry point for the local Minikun Cockpit. */
@Controller
public final class CockpitController {
    @GetMapping({"/cockpit", "/cockpit/"})
    public String cockpit() { return "forward:/cockpit/index.html"; }
}
