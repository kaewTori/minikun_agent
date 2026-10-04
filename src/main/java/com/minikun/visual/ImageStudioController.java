package com.minikun.visual;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** A focused, authenticated image-generation endpoint for the Cockpit image studio. */
@RestController
@RequestMapping("/v1/images/studio")
public final class ImageStudioController {
    private static final Logger LOG = LoggerFactory.getLogger(ImageStudioController.class);

    private final ImageGenerationTool imageGenerationTool;
    private final TinyGradRuntimeStatusReader runtimeStatus;
    private final PonyPromptTransformer promptTransformer;
    private final ImageGenerationHistoryStore historyStore;
    private final String token;
    private final int maximumPromptCharacters;

    public ImageStudioController(
            ImageGenerationTool imageGenerationTool,
            String token,
            int maximumPromptCharacters) {
        this(imageGenerationTool, () -> new TinyGradRuntimeStatusReader.RuntimeStatus(
                false, "OFFLINE", "", 0,
                new TinyGradRuntimeStatusReader.RuntimeMemory(-1, -1, -1), -1, -1,
                -1, -1, false, null, null, null, 0, 0, Instant.EPOCH),
                brief -> { throw new IllegalStateException("prompt transformer is unavailable"); },
                ImageGenerationHistoryStore.noop(), token, maximumPromptCharacters);
    }

    public ImageStudioController(
            ImageGenerationTool imageGenerationTool,
            TinyGradRuntimeStatusReader runtimeStatus,
            String token,
            int maximumPromptCharacters) {
        this(imageGenerationTool, runtimeStatus,
                brief -> { throw new IllegalStateException("prompt transformer is unavailable"); },
                ImageGenerationHistoryStore.noop(), token, maximumPromptCharacters);
    }

    public ImageStudioController(
            ImageGenerationTool imageGenerationTool,
            TinyGradRuntimeStatusReader runtimeStatus,
            PonyPromptTransformer promptTransformer,
            String token,
            int maximumPromptCharacters) {
        this(imageGenerationTool, runtimeStatus, promptTransformer,
                ImageGenerationHistoryStore.noop(), token, maximumPromptCharacters);
    }

    public ImageStudioController(
            ImageGenerationTool imageGenerationTool,
            TinyGradRuntimeStatusReader runtimeStatus,
            PonyPromptTransformer promptTransformer,
            ImageGenerationHistoryStore historyStore,
            String token,
            int maximumPromptCharacters) {
        this.imageGenerationTool = imageGenerationTool;
        this.runtimeStatus = java.util.Objects.requireNonNull(runtimeStatus, "runtime status must not be null");
        this.promptTransformer = java.util.Objects.requireNonNull(
                promptTransformer, "prompt transformer must not be null");
        this.historyStore = java.util.Objects.requireNonNull(historyStore, "history store must not be null");
        this.token = token == null ? "" : token.strip();
        this.maximumPromptCharacters = maximumPromptCharacters;
    }

    @PostMapping("/prompts/pony")
    public PonyPromptResponse transformPrompt(
            @RequestBody PonyPromptRequest request,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String suppliedToken) {
        authorize(suppliedToken);
        String brief = request == null ? "" : optional(request.brief());
        if (brief.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image brief is required");
        }
        if (brief.length() > maximumPromptCharacters) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "image brief exceeds " + maximumPromptCharacters + " characters");
        }
        try {
            PonyPromptTransformer.Grouped grouped = promptTransformer.transformGrouped(brief);
            String prompt = grouped.prompt();
            if (prompt.isBlank() || prompt.length() > maximumPromptCharacters) {
                throw new IllegalStateException("prompt transformer returned an invalid prompt");
            }
            return new PonyPromptResponse(prompt, grouped.groups());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            LOG.warn("process=image_studio event=prompt_transform_failed reason={}",
                    exception.getMessage(), exception);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Mini-kun could not transform this prompt right now", exception);
        }
    }

    @GetMapping("/status")
    public TinyGradRuntimeStatusReader.RuntimeStatus status(
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String suppliedToken) {
        authorize(suppliedToken);
        return runtimeStatus.read();
    }

    @PostMapping("/generations")
    public GenerationResponse generate(
            @RequestBody GenerationRequest request,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String suppliedToken) {
        authorize(suppliedToken);
        String owner = owner(ownerId);
        String prompt = request == null || request.prompt() == null ? "" : request.prompt().strip();
        if (prompt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image prompt is required");
        }
        if (prompt.length() > maximumPromptCharacters) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "image prompt exceeds " + maximumPromptCharacters + " characters");
        }
        String negativePrompt = optional(request.negative_prompt());
        if (negativePrompt.length() > maximumPromptCharacters) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "negative prompt is too long");
        }
        ImageGenerationRequest generationRequest = new ImageGenerationRequest(
                prompt,
                negativePrompt,
                cleanFacePrompts(request.face_prompts()),
                dimension(request.width(), "width"),
                dimension(request.height(), "height"),
                steps(request.steps()),
                guidance(request.guidance()),
                choice(request.scheduler(), Set.of("dpmpp2m", "euler"), "scheduler"),
                choice(request.schedule(), Set.of("legacy", "karras"), "schedule"),
                request.seed());
        validatePixelLimit(generationRequest.width(), generationRequest.height());
        String mode = optional(request.mode());
        if (mode.isBlank()) mode = "manual";
        if (!Set.of("manual", "story").contains(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported studio mode");
        }
        String brief = optional(request.brief());
        if (brief.length() > maximumPromptCharacters) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image brief is too long");
        }
        ImageGenerationTool.Generation image = imageGenerationTool.generate(generationRequest,
                new ImageGenerationScope(owner, "studio", "studio", mode,
                        brief.substring(0, Math.min(brief.length(), 240))));
        return new GenerationResponse(
                image.url(), image.prompt(), image.negativePrompt(), image.seed(),
                image.historyId().toString(), image.provider(), image.createdAt());
    }

    public GenerationResponse generate(GenerationRequest request, String suppliedToken) {
        return generate(request, "default", suppliedToken);
    }

    @GetMapping("/generations")
    public List<StudioHistoryItem> generations(
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String suppliedToken) {
        authorize(suppliedToken);
        String owner = owner(ownerId);
        int count = Math.max(1, Math.min(limit, 100));
        List<ImageGenerationHistory> current = historyStore.find(owner, "studio", count);
        List<ImageGenerationHistory> legacy = owner.equals("default")
                ? historyStore.find(owner, "standalone", count) : List.of();
        return java.util.stream.Stream.concat(current.stream(), legacy.stream())
                .sorted(Comparator.comparing(ImageGenerationHistory::createdAt).reversed())
                .limit(count).map(this::historyItem).toList();
    }

    private StudioHistoryItem historyItem(ImageGenerationHistory image) {
        String title = image.sceneTitle().isBlank() ? image.prompt() : image.sceneTitle();
        if (title.length() > 75) title = title.substring(0, 72) + "…";
        String mode = image.illustrationMode().equals("story") ? "story" : "manual";
        return new StudioHistoryItem(image.id().toString(), title, mode, image.prompt(),
                image.negativePrompt(), image.imageUrl(), image.createdAt(), review(image.prompt()));
    }

    // ponytail: This scores prompt structure only; add model review if semantic critique is needed.
    private PromptReview review(String prompt) {
        String value = prompt.toLowerCase(Locale.ROOT);
        int tags = (int) prompt.chars().filter(character -> character == ',').count() + 1;
        boolean quality = value.matches("(?s).*score_[0-9].*");
        boolean scene = value.matches("(?s).*(?:room|street|forest|city|garden|beach|sky|night|day|indoor|outdoor|background|observatory).*" );
        boolean light = value.matches("(?s).*(?:light|sun|moon|shadow|glow|golden hour|dusk).*" );
        boolean camera = value.matches("(?s).*(?:shot|close-up|view|angle|composition|depth of field|portrait|wide).*" );
        boolean style = value.matches("(?s).*(?:anime|illustration|painting|lineart|cel shading|realistic).*" );
        int score = Math.min(100, 45 + (tags >= 5 ? 15 : 0) + (tags >= 10 ? 5 : 0)
                + (quality ? 10 : 0) + (scene ? 10 : 0) + (light ? 10 : 0)
                + (camera ? 10 : 0) + (style ? 5 : 0));
        String good = scene && light ? "บอกฉากและแสงชัด ช่วยกำหนดบรรยากาศภาพ"
                : tags >= 5 ? "มีรายละเอียดหลายส่วนให้โมเดลใช้จัดภาพ"
                : "มีจุดเริ่มต้นของภาพที่นำไปต่อยอดได้";
        String tip = tags < 5 ? "เพิ่ม tags ที่มองเห็นได้ เช่น ตัวแบบ ฉาก สี หรือการกระทำ"
                : !scene ? "เพิ่มสถานที่หรือช่วงเวลา เพื่อให้ฉากชัดขึ้น"
                : !light ? "ระบุแหล่งแสงหรือบรรยากาศแสงที่ต้องการ"
                : !camera ? "เพิ่มระยะภาพหรือมุมกล้อง เช่น medium shot หรือ close-up"
                : !style ? "ระบุสไตล์ภาพให้ชัดขึ้น หากต้องการควบคุมลายเส้น"
                : "รายละเอียดครบแล้ว ลองปรับน้ำหนักเฉพาะจุดสำคัญของภาพ";
        return new PromptReview(score, good, tip);
    }

    private String owner(String value) {
        String owner = optional(value);
        if (owner.isBlank() || owner.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid owner id");
        }
        return owner;
    }

    private void authorize(String suppliedToken) {
        if (!token.isBlank() && !token.equals(suppliedToken)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "personal token is invalid");
        }
    }

    private String optional(String value) {
        return value == null ? "" : value.strip();
    }

    private List<String> cleanFacePrompts(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> result = values.stream()
                .map(this::optional)
                .filter(value -> !value.isBlank())
                .toList();
        if (result.stream().anyMatch(value -> value.length() > maximumPromptCharacters)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "face prompt is too long");
        }
        return result;
    }

    private Integer dimension(Integer value, String name) {
        if (value == null) {
            return null;
        }
        if (value < 64 || value > 4096 || value % 64 != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    name + " must be a multiple of 64 between 64 and 4096");
        }
        return value;
    }

    private Integer steps(Integer value) {
        if (value != null && (value < 1 || value > 200)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "steps must be between 1 and 200");
        }
        return value;
    }

    private Double guidance(Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0.0 || value > 30.0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "guidance must be between 0 and 30");
        }
        return value;
    }

    private String choice(String value, Set<String> allowed, String name) {
        String result = optional(value);
        if (!result.isBlank() && !allowed.contains(result)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported " + name);
        }
        return result;
    }

    private void validatePixelLimit(Integer width, Integer height) {
        if (width != null && height != null && (long) width * height > 4_194_304L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requested image exceeds the pixel limit");
        }
    }

    public record GenerationRequest(
            String prompt,
            String negative_prompt,
            List<String> face_prompts,
            Integer width,
            Integer height,
            Integer steps,
            Double guidance,
            String scheduler,
            String schedule,
            Long seed,
            String mode,
            String brief) {
        public GenerationRequest(String prompt) {
            this(prompt, null, List.of(), null, null, null, null, null, null, null, null, null);
        }

        public GenerationRequest(String prompt, String negative_prompt, List<String> face_prompts,
                Integer width, Integer height, Integer steps, Double guidance,
                String scheduler, String schedule, Long seed) {
            this(prompt, negative_prompt, face_prompts, width, height, steps, guidance,
                    scheduler, schedule, seed, null, null);
        }
    }

    public record GenerationResponse(
            String url,
            String prompt,
            String negative_prompt,
            long seed,
            String generation_id,
            String provider,
            Instant created_at) { }

    public record PonyPromptRequest(String brief) { }

    public record PonyPromptResponse(String prompt, java.util.Map<String, String> groups) { }

    public record PromptReview(int score, String good, String tip) { }

    public record StudioHistoryItem(String id, String title, String mode, String prompt,
            String negative_prompt, String image_url, Instant created_at, PromptReview review) { }
}
