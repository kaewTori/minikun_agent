package com.minikun.runtime;

import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

@Service
public final class VersionService {
    private final ObjectProvider<BuildProperties> buildProperties;
    private final String packageVersion;

    public VersionService(
            ObjectProvider<BuildProperties> buildProperties,
            @Value("${application.version:}") String packageVersion) {
        this.buildProperties = buildProperties;
        this.packageVersion = packageVersion;
    }

    public VersionInfo snapshot() {
        BuildProperties build = buildProperties.getIfAvailable();
        RuntimeValue buildVersion = build == null
                ? valueFrom(packageVersion)
                : valueFrom(build.getVersion());
        RuntimeValue revision = build == null
                ? RuntimeValue.notConfigured()
                : valueFrom(Optional.ofNullable(build.get("git.commit.id"))
                        .orElseGet(() -> build.get("build.revision")));
        RuntimeValue applicationVersion = build == null
                ? valueFrom(packageVersion)
                : valueFrom(build.get("application.version"));
        if (applicationVersion.state() == RuntimeValueState.NOT_CONFIGURED) {
            applicationVersion = buildVersion;
        }
        return new VersionInfo(
                applicationVersion,
                buildVersion,
                revision,
                RuntimeValue.configured(Runtime.version().toString()),
                valueFrom(SpringBootVersion.getVersion()));
    }

    private RuntimeValue valueFrom(String value) {
        return value == null || value.isBlank()
                ? RuntimeValue.notConfigured()
                : RuntimeValue.configured(value);
    }
}
