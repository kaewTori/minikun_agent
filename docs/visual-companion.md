# Visual Companion

Minikun can return a visual gallery for image-search requests while retrieving normal web evidence for the answer in parallel.

## Reverse-image search

The Cockpit action `หารูปคล้ายกัน` sends the already validated attached image through the chat pipeline. When
`MINIKUN_SEARCH_BY_IMAGE_ENABLED=true` and `MINIKUN_SEARCH_BY_IMAGE_URL` is configured, Minikun posts a bounded
JPEG/PNG/WebP multipart part named `image` plus `limit` to that endpoint. The provider response is intentionally small
and vendor-neutral: `{"results":[{"image_url":"https://…","title":"…","source_url":"https://…",
"description":"…","thumbnail_url":"https://…","provider":"…","license":"…"}]}`. Returned entries
reuse the existing image gallery, source links, and evidence selection. The feature is fail-open and disabled by
default; no provider means Minikun keeps the vision answer and reports that no matching image was retrieved.

## Image Studio

The Cockpit exposes a dedicated `สร้างภาพ` view that teaches Pony/SDXL tag construction while the user works. It starts each positive prompt with `score_9, score_8_up, score_7_up` without forcing a source tag, then assembles comma-separated English tags for character count, appearance, action, expression, scene, lighting, style, and camera. Extra exclusions are merged into the separate negative prompt, and per-face prompts remain available for ADetailer.

`POST /v1/images/studio/generations` accepts the assembled prompt plus optional TinyGrad controls (`negative_prompt`, `face_prompts`, `width`, `height`, `steps`, `guidance`, `scheduler`, `schedule`, and `seed`). The only image provider posts those controls to the local TinyGrad `sdxl_use.py --serve 8002` `/generate` endpoint and reads its raw PNG response. There is no OpenAI image-generation fallback. Minikun stores only the bounded generated image on its host. The endpoint is available only when `minikun.visual.generation.enabled=true` and uses the optional visual/personal management token. Studio results can be downloaded or saved directly to an inspiration board.

The same local function is registered as the native `image.generate` tool. Image Studio and automatic story illustrations call this tool's validated generation path, so prompt limits, dimensions, storage, and returned local URLs remain consistent. That shared path guarantees the Pony quality prefix even when a model submits a plain English prompt, but does not guess prompt semantics. The structured story compiler adds human-exclusion tags only when the validated scene contains explicit animal character identities and no people; direct callers must provide exclusions explicitly. The tool never performs web image search and requires no external image API.

The shared path assigns a concrete unsigned 32-bit seed before calling TinyGrad whenever the caller did not supply one. Its response attachment carries the exact formatted Pony prompt, negative prompt, seed, and generation ID. The Cockpit shows the seed and an expandable Pony prompt on generated-image cards and in the lightbox, with copy controls for reproduction. PostgreSQL stores the same values in `minikun_image_generation_history`, scoped by `owner_id` and `conversation_id`, together with the story mode/title, provider, requested dimensions/steps, local image URL, and timestamp. History persistence is fail-open so a temporary database problem does not discard an otherwise successful image.

TinyGrad connection failures return `503` with a start-service hint, generation timeouts return `504`, and upstream HTTP or invalid-image failures return `502`. The Cockpit translates these statuses into actionable Thai messages. The TinyGrad-specific timeout defaults to ten minutes to cover first-run JIT compilation.

Automatic story illustration uses a schema-bound local visual-director pipeline. The local task model returns JSON containing validated character profiles and scene fields for subject count, named characters, action, interaction, objects, setting, time, weather, emotion, atmosphere, lighting, palette, composition, camera, focus, required anchors, exclusions, and fine details. The user request is authoritative and the assistant story may only fill missing facts. Free-form model output never goes directly to SDXL: unambiguous scalar/list type drift is normalized and a wrong schema is repaired once. If the plan is still invalid or unavailable, the existing Pony prompt transformer prepares the fallback prompt; if that is also unavailable, a small deterministic prompt keeps the TinyGrad path fail-open. Storyboard fallback preserves the configured panel count. The schema boundary rejects unusable scenes, invented people in animal-only stories, dropped known cat/observatory/star anchors, `source_anime`, unknown placeholders, and prompt-contamination patterns. A deterministic compiler orders the accepted facts as Pony tags and preserves the separate negative prompt.

Character Visual Memory is local and scoped by owner plus conversation. Its stored profile locks a named character's identity, appearance, clothing, accessories, canonical Pony tags, and conflicting negative tags across later story turns. PostgreSQL installations persist the JSON profile in `minikun_character_visual_memory`; installations without JDBC use an in-process memory store. A later task-model response may fill a previously empty field but cannot silently replace an established appearance. Memory failures are fail-open: the story and image generation continue without cross-turn locking.

Mini-kun detects five illustration treatments from Thai or English wording: `cover`, `decisive_scene` (the default), `character_portrait`, `ending_scene`, and `storyboard`. A storyboard contains two to five chronological panels and defaults to three; panels are generated strictly one at a time so they cannot compete for VRAM. Every panel is returned as a separate attachment with its sequence number. `MINIKUN_VISUAL_STORYBOARD_MAX_SCENES` controls the default maximum. Main generation is 768×1280 at 40 steps. TinyGrad receives the complete positive and negative prompts with `long_prompt_mode=chunk`; Mini-kun does not pre-trim them to one CLIP chunk. The Pony quality prefix is `score_9, score_8_up, score_7_up` without forcing `source_anime`, and story-specific negative tags precede configured quality exclusions. A failed generation is reported immediately without a second low-memory retry. The story still completes and includes a visible Thai warning instead of silently omitting the illustration. The LaunchAgent wrapper watches TinyGrad stderr for NV allocation, device, and compiler-pipe faults; it exits non-zero on any of them so launchd reloads the model into a clean GPU process.

## Response attachment contract

Image attachments include a local `url` for display, the remote `original_url` for follow-up vision input, `source_url`, `description`, `origin`, and optional provider, license, and dimensions. Generated attachments additionally include `prompt`, `negative_prompt`, `seed`, and `generation_id`. `origin` is one of `web`, `generated`, or `user`; the Cockpit always shows that label instead of presenting searched images as generated work.

The gallery supports a lightbox, broken-image fallback, source links, “ask from this image”, “create from this reference”, and one-click saving to a persistent inspiration board. Follow-up actions attach the original remote image to the next OpenAI-compatible multimodal request.

## Safety and storage

`GET /v1/images/proxy?url=…` accepts HTTPS images only, rejects local/private targets, does not follow redirects, allows JPEG/PNG/WebP, enforces byte and timeout limits, and serves a bounded TTL cache with `nosniff` and an ETag. Inspiration boards store image/source URLs and user-provided labels, not image bytes.

Configuration uses the `minikun.visual.proxy.*` properties in `application.properties`. Board APIs live under `/v1/personal/inspiration-boards` and use the same optional personal-management token as other personal APIs.
