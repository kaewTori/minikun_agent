package com.minikun.personality.management;

import com.minikun.personality.model.Preference;
import com.minikun.personality.model.UserProfile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/persona")
public final class PersonaManagementController {
    private final PersonaManagementService service;

    public PersonaManagementController(PersonaManagementService service) { this.service = service; }

    @GetMapping("/profile")
    public UserProfile profile(@RequestParam(defaultValue = "default") String ownerId) {
        return service.profile(ownerId);
    }

    @PutMapping("/profile")
    public UserProfile saveProfile(@RequestParam(defaultValue = "default") String ownerId,
            @RequestBody ProfileRequest request) {
        UserProfile profile = new UserProfile(ownerId, request.displayName(), request.preferredLanguage(),
                request.responseStyle(), request.timezone());
        service.saveProfile(profile);
        return profile;
    }

    @GetMapping("/preferences")
    public List<Preference> preferences(@RequestParam(defaultValue = "default") String ownerId) {
        return service.preferences(ownerId);
    }

    @PutMapping("/preferences/{key}")
    public Preference savePreference(@RequestParam(defaultValue = "default") String ownerId,
            @PathVariable String key, @RequestBody PreferenceRequest request) {
        Preference preference = new Preference(ownerId, key, request.value(), request.confidence(), Instant.now());
        service.savePreference(preference);
        return preference;
    }

    @DeleteMapping("/preferences/{key}")
    public ResponseEntity<Void> deletePreference(@RequestParam(defaultValue = "default") String ownerId,
            @PathVariable String key) {
        return service.deletePreference(ownerId, key)
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/preferences")
    public Map<String, Object> forgetPreferences(@RequestParam(defaultValue = "default") String ownerId) {
        return Map.of("deleted", service.forget(ownerId));
    }

    public record ProfileRequest(String displayName, String preferredLanguage, String responseStyle, String timezone) {}
    public record PreferenceRequest(String value, double confidence) {}
}
