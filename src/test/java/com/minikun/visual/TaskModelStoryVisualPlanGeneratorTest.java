package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.task.TaskModelRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskModelStoryVisualPlanGeneratorTest {
    @Test
    void acceptsCompactPlanAndKeepsRequestedDetailsInImagePrompt() {
        List<TaskModelRequest> requests = new ArrayList<>();
        var generator = new TaskModelStoryVisualPlanGenerator(request -> {
            requests.add(request);
            return """
                    {"characters":[{"name":"มะลิ","identity":"1girl",
                    "appearance":["pink hair"],"clothing":["blue raincoat"]}],
                    "scenes":[{"title":"Observatory","subjectCount":1,"characterNames":["มะลิ"],
                    "action":"looking through a brass telescope","setting":"old observatory",
                    "mustInclude":["blue star","rain on the window"],"mustNotInclude":[]}]}
                    """;
        }, new ObjectMapper());

        StoryVisualPlan plan = generator.generate(
                "วาดมะลิผมสีชมพู เสื้อคลุมสีน้ำเงิน หอดูดาวเก่า ดาวสีฟ้า",
                "มะลิมองดาวผ่านกล้องทองเหลือง ฝนเกาะหน้าต่าง",
                StoryIllustrationMode.DECISIVE_SCENE, List.of(), 3);
        String prompt = new PonyStoryPromptCompiler().compile(
                plan.mode(), plan.scenes().getFirst(), plan.characters()).positive();

        assertEquals(1, requests.size());
        assertTrue(requests.getFirst().messages().getFirst().content().contains("compact JSON"));
        assertTrue(prompt.contains("pink hair"));
        assertTrue(prompt.contains("blue raincoat"));
        assertTrue(prompt.contains("brass telescope"));
        assertTrue(prompt.contains("blue star"));
    }

    @Test
    void parsesAValidatedStructuredPlanAndIncludesLockedMemory() {
        List<TaskModelRequest> requests = new ArrayList<>();
        var generator = new TaskModelStoryVisualPlanGenerator(request -> {
            requests.add(request);
            return """
                    {"globalMustInclude":["blue star","old observatory"],"globalMustNotInclude":[],
                    "characters":[{"name":"Mali","identity":"1girl","appearance":["long pink hair"],
                    "clothing":["navy coat"],"accessories":["star pendant"],
                    "canonicalTags":["dark red eyes"],"negativeTags":["blonde hair"]}],
                    "scenes":[{"title":"Blue discovery","subjectCount":1,"characterNames":["Mali"],
                    "action":"looking through a brass telescope","interaction":"",
                    "keyObjects":["brass telescope"],"setting":"old observatory","time":"midnight",
                    "weather":"rain","emotion":"hope after grief","atmosphere":"quiet wonder",
                    "lighting":"blue starlight and warm lamp light","palette":"navy and cyan",
                    "composition":"balanced cinematic composition","cameraAngle":"slight low angle",
                    "shotDistance":"medium shot","focus":"shallow depth of field",
                    "mustInclude":["vivid blue star"],"mustNotInclude":["extra person"],
                    "fineDetails":["wet glass","star charts"]}]}
                    """;
        }, new ObjectMapper());
        CharacterVisualProfile remembered = new CharacterVisualProfile(
                "Mali", "1girl", List.of("long pink hair"), List.of(), List.of(),
                List.of("dark red eyes"), List.of("blonde hair"));

        StoryVisualPlan plan = generator.generate(
                "เล่าตอนที่มะลิค้นพบดาวสีฟ้าในหอดูดาว",
                "มะลิค้นพบดาวสีฟ้าในหอดูดาวเก่า",
                StoryIllustrationMode.DECISIVE_SCENE, List.of(remembered), 3);

        assertEquals(1, plan.scenes().size());
        assertEquals("old observatory", plan.scenes().getFirst().setting());
        assertEquals("long pink hair", plan.characters().getFirst().appearance().getFirst());
        assertEquals(TaskModelRequest.ResponseFormat.JSON_OBJECT, requests.getFirst().responseFormat());
        assertTrue(requests.getFirst().messages().getLast().content().contains("LOCKED CHARACTER VISUAL MEMORY"));
        assertTrue(requests.getFirst().messages().getLast().content().contains("long pink hair"));
        PonyStoryPromptCompiler.CompiledPrompt prompt = new PonyStoryPromptCompiler().compile(
                plan.mode(), plan.scenes().getFirst(), plan.characters());
        assertEquals(1, prompt.facePrompts().size());
        assertFalse(prompt.facePrompts().getFirst().contains("Mali"));
        assertTrue(prompt.facePrompts().getFirst().contains("long pink hair"));
        assertTrue(prompt.facePrompts().getFirst().contains("dark red eyes"));
    }

    @Test
    void repairsLocalizedCharacterDetailsInsteadOfDroppingThem() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        var generator = new TaskModelStoryVisualPlanGenerator(request -> """
                {"characters":[{"name":"Mali","identity":"1girl","appearance":["%s"],
                  "clothing":["navy coat"],"accessories":["round glasses"],
                  "canonicalTags":[],"negativeTags":[]}],
                 "scenes":[{"title":"Discovery","subjectCount":1,"characterNames":["Mali"],
                  "action":"looking through a telescope","interaction":"","keyObjects":["telescope"],
                  "setting":"observatory","time":"night","weather":"","emotion":"wonder",
                  "atmosphere":"quiet","lighting":"starlight","palette":"navy",
                  "composition":"centered","cameraAngle":"eye level","shotDistance":"medium shot",
                  "focus":"sharp","mustInclude":[],"mustNotInclude":[],"fineDetails":[]}]}
                """.formatted(calls.getAndIncrement() == 0 ? "ผมสีชมพู" : "pink hair"),
                new ObjectMapper());

        StoryVisualPlan plan = generator.generate(
                "วาดภาพหญิงสาวมะลิผมสีชมพูในหอดูดาว", "มะลิมองดาวผ่านกล้องโทรทรรศน์",
                StoryIllustrationMode.DECISIVE_SCENE, List.of(), 3);
        PonyStoryPromptCompiler.CompiledPrompt prompt = new PonyStoryPromptCompiler().compile(
                plan.mode(), plan.scenes().getFirst(), plan.characters());

        assertEquals(2, calls.get());
        assertTrue(prompt.positive().contains("pink hair"));
        assertFalse(prompt.positive().contains("ผมสีชมพู"));
    }

    @Test
    void keepsUsablePlanWhenRepairStillHasThaiDetailsAndWrongSubjectCount() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        var generator = new TaskModelStoryVisualPlanGenerator(request -> {
            calls.incrementAndGet();
            return """
                    {"characters":[{"name":"มะลิ","identity":"1girl","appearance":["ผมสีชมพู"],
                    "clothing":["blue coat"]},{"name":"ริน","identity":"1girl",
                    "appearance":["black hair"]}],"scenes":[{"title":"Observatory",
                    "subjectCount":1,"characterNames":["มะลิ","ริน"],
                    "action":"looking through a telescope","setting":"old observatory",
                    "mustInclude":["blue star"]}]}
                    """;
        }, new ObjectMapper());

        StoryVisualPlan plan = generator.generate(
                "เล่าเรื่องผู้หญิงสองคน มะลิผมสีชมพูและรินผมดำ",
                "มะลิและรินมองดาวสีฟ้าในหอดูดาว",
                StoryIllustrationMode.DECISIVE_SCENE, List.of(), 3);
        String prompt = new PonyStoryPromptCompiler().compile(
                plan.mode(), plan.scenes().getFirst(), plan.characters()).positive();

        assertEquals(2, calls.get());
        assertEquals(2, plan.scenes().getFirst().subjectCount());
        assertTrue(prompt.contains("2girls"));
        assertTrue(prompt.contains("blue coat"));
        assertTrue(prompt.contains("blue star"));
        assertFalse(prompt.contains("ผมสีชมพู"));
    }

    @Test
    void rejectsInventedPeopleWithoutGeneratingFallbackDetails() {
        var generator = new TaskModelStoryVisualPlanGenerator(request -> """
                {"globalMustInclude":["black cat","old observatory"],"globalMustNotInclude":["human"],
                "characters":[{"name":"Alice","identity":"1girl","appearance":["blonde hair"],
                "clothing":[],"accessories":[],"canonicalTags":[],"negativeTags":[]}],
                "scenes":[{"title":"Wrong","subjectCount":1,"characterNames":["Alice"],
                "action":"reading","interaction":"","keyObjects":[],"setting":"library","time":"day",
                "weather":"","emotion":"happy","atmosphere":"calm","lighting":"sunlight","palette":"warm",
                "composition":"centered","cameraAngle":"eye level","shotDistance":"medium shot","focus":"sharp",
                "mustInclude":[],"mustNotInclude":[],"fineDetails":[]}]}
                """, new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> generator.generate(
                "แต่งเรื่องแมวดำในหอดูดาว", "แมวดำเฝ้ามองแสงดาวในหอดูดาว",
                StoryIllustrationMode.DECISIVE_SCENE, List.of(), 3));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(
                "วาดแมวดำ ห้ามมีมนุษย์", "A black cat sits alone.",
                StoryIllustrationMode.DECISIVE_SCENE, List.of(), 3));
    }

    @Test
    void repairsAnInventedBoyInAnAllFemaleStory() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        List<TaskModelRequest> requests = new ArrayList<>();
        var generator = new TaskModelStoryVisualPlanGenerator(request -> {
            requests.add(request);
            String identity = calls.getAndIncrement() == 0 ? "1boy" : "1girl";
            return """
                    {"characters":[{"name":"Mali","identity":"%s","appearance":["long black hair"],
                    "clothing":[],"accessories":[],"canonicalTags":[],"negativeTags":[]}],
                    "scenes":[{"title":"Discovery","subjectCount":1,"characterNames":["Mali"],
                    "action":"looking through a telescope","interaction":"","keyObjects":["telescope"],
                    "setting":"observatory","time":"night","weather":"","emotion":"wonder",
                    "atmosphere":"quiet","lighting":"starlight","palette":"navy","composition":"centered",
                    "cameraAngle":"eye level","shotDistance":"medium shot","focus":"sharp",
                    "mustInclude":[],"mustNotInclude":[],"fineDetails":[]}]}
                    """.formatted(identity);
        }, new ObjectMapper());

        StoryVisualPlan plan = generator.generate(
                "วาดหญิงสาวมะลิในหอดูดาว", "หญิงสาวมะลิมองดาวผ่านกล้องโทรทรรศน์",
                StoryIllustrationMode.DECISIVE_SCENE, List.of(), 3);
        String prompt = new PonyStoryPromptCompiler().compile(
                plan.mode(), plan.scenes().getFirst(), plan.characters()).positive();

        assertEquals(2, calls.get());
        assertTrue(requests.getLast().messages().getFirst().content().contains("invented a male character"));
        assertTrue(prompt.contains("1girl"));
        assertFalse(prompt.contains("1boy"));
    }

    @Test
    void doesNotTreatCathedralAsACatAnchor() {
        var generator = new TaskModelStoryVisualPlanGenerator(request -> """
                {"globalMustInclude":["gothic cathedral"],"globalMustNotInclude":[],
                "characters":[],"scenes":[{"title":"Bell chamber","subjectCount":0,
                "characterNames":[],"action":"sunlight crossing the stone floor","interaction":"",
                "keyObjects":["bronze bell"],"setting":"gothic church interior","time":"dawn",
                "weather":"","emotion":"solemn","atmosphere":"quiet","lighting":"golden sunlight",
                "palette":"gold and gray","composition":"symmetrical","cameraAngle":"eye level",
                "shotDistance":"wide shot","focus":"sharp","mustInclude":["stained glass"],
                "mustNotInclude":[],"fineDetails":["carved stone"]}]}
                """, new ObjectMapper());

        StoryVisualPlan plan = generator.generate(
                "Illustrate dawn inside a gothic cathedral", "Sunlight reaches the old bell chamber.",
                StoryIllustrationMode.DECISIVE_SCENE, List.of(), 3);

        assertEquals("Bell chamber", plan.scenes().getFirst().title());
        assertEquals("gothic church interior", plan.scenes().getFirst().setting());
        assertFalse(plan.scenes().getFirst().mustInclude().contains("cat"));
    }

    @Test
    void preservesGenericUserRequirementsWithoutDuplicatingQualifiedTags() {
        var generator = new TaskModelStoryVisualPlanGenerator(request -> """
                {"characters":[],"scenes":[{"title":"Quiet night","subjectCount":0,
                "characterNames":[],"action":"watching the night sky","interaction":"",
                "keyObjects":[],"setting":"old observatory","time":"night","weather":"",
                "emotion":"calm","atmosphere":"quiet","lighting":"starlight","palette":"navy",
                "composition":"centered","cameraAngle":"eye level","shotDistance":"wide shot",
                "focus":"sharp","mustInclude":["black cat","copper collar","old observatory","blue star"],
                "mustNotInclude":["human"],"fineDetails":[]}]}
                """, new ObjectMapper());

        StoryVisualPlan plan = generator.generate(
                "วาดแมวดำใส่ปลอกคอทองแดงในหอดูดาวใต้ดาวสีฟ้า", "A quiet night.",
                StoryIllustrationMode.DECISIVE_SCENE, List.of(), 3);
        PonyStoryPromptCompiler.CompiledPrompt compiled = new PonyStoryPromptCompiler().compile(
                plan.mode(), plan.scenes().getFirst(), plan.characters());
        String prompt = compiled.positive();

        assertEquals(List.of("black cat", "copper collar", "old observatory", "blue star"),
                plan.scenes().getFirst().mustInclude());
        assertTrue(prompt.contains("black cat"));
        assertTrue(prompt.contains("copper collar"));
        assertTrue(compiled.negative().contains("human"));
        assertEquals(1, prompt.split("observatory", -1).length - 1);
        assertEquals(1, prompt.split("starlight", -1).length - 1);
    }

    @Test
    void preservesGenericUserExclusions() {
        var generator = new TaskModelStoryVisualPlanGenerator(request -> """
                {"characters":[],"scenes":[{"title":"Seal","subjectCount":1,
                "characterNames":[],"action":"a seal rests beside a laptop","interaction":"",
                "keyObjects":["laptop"],"setting":"beach","time":"day","weather":"clear",
                "emotion":"calm","atmosphere":"quiet","lighting":"sunlight","palette":"blue",
                "composition":"centered","cameraAngle":"eye level","shotDistance":"wide shot",
                "focus":"sharp","mustInclude":[],"mustNotInclude":["cat","star"],"fineDetails":[]}]}
                """, new ObjectMapper());

        StoryVisualPlan plan = generator.generate(
                "วาดแมวน้ำกำลังดาวน์โหลดไฟล์ ไม่มีแมว ไม่มีดาว", "A seal beside a laptop.",
                StoryIllustrationMode.DECISIVE_SCENE, List.of(), 3);

        assertTrue(plan.scenes().getFirst().mustInclude().isEmpty());
        assertEquals(List.of("cat", "star"), plan.scenes().getFirst().mustNotInclude());
    }

    @Test
    void compilerDropsProseAndNormalizesPlannerTags() {
        StorySceneSpec scene = new StorySceneSpec(
                "Night", 1, List.of(), "The cat watches the sky.",
                "The cat taps a switch on the old control panel.", List.of("Telescope"),
                "Old Observatory", "Night", "Clear (implied by stargazing)", "Calm", "Quiet",
                "Blue Starlight", "Navy", "Centered", "Eye Level", "Medium Shot", "Sharp",
                List.of("Blue Star"), List.of(), List.of());

        String prompt = new PonyStoryPromptCompiler().compile(
                StoryIllustrationMode.DECISIVE_SCENE, scene, List.of()).positive();

        assertFalse(prompt.contains("the cat watches"));
        assertFalse(prompt.contains("implied by stargazing"));
        assertTrue(prompt.contains("old observatory"));
        assertTrue(prompt.contains("blue star"));
        assertTrue(prompt.contains("telescope"));
    }

    @Test
    void repairsWrongRootAndAcceptsUnambiguousScalarListsAndThreeCharacterScenes() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        List<TaskModelRequest> requests = new ArrayList<>();
        var generator = new TaskModelStoryVisualPlanGenerator(request -> {
            requests.add(request);
            if (calls.getAndIncrement() == 0) return "{}";
            return """
                    {"globalMustInclude":["three adult women"],"globalMustNotInclude":[],"characters":[
                      {"name":"Itsuki","identity":"1girl","appearance":"long pink hair, red eyes",
                       "clothing":"white shirt","accessories":"round glasses","canonicalTags":[],"negativeTags":[]},
                      {"name":"Rena","identity":"1girl","appearance":"short black hair, sapphire eyes",
                       "clothing":[],"accessories":"round glasses","canonicalTags":[],"negativeTags":[]},
                      {"name":"Rin","identity":"1girl","appearance":"brown-green hair, cyan eyes",
                       "clothing":[],"accessories":"round glasses","canonicalTags":[],"negativeTags":[]}],
                     "scenes":[{"title":"Standoff","subjectCount":"3",
                       "characterNames":["Itsuki","Rena","Rin"],"action":"Itsuki faces Rena",
                       "interaction":"Rin watches from the window","keyObjects":[],"setting":"","time":"night",
                       "weather":"","emotion":"tense","atmosphere":"quiet","lighting":"city lights",
                       "palette":"navy","composition":"three-person composition","cameraAngle":"eye level",
                       "shotDistance":"medium shot","focus":"sharp","mustInclude":[],"mustNotInclude":[],
                       "fineDetails":[]}]}
                    """;
        }, new ObjectMapper());

        StoryVisualPlan plan = generator.generate(
                "Illustrate three adult women: Itsuki, Rena, and Rin",
                "Itsuki faces Rena while Rin watches", StoryIllustrationMode.DECISIVE_SCENE, List.of(), 3);
        PonyStoryPromptCompiler.CompiledPrompt prompt = new PonyStoryPromptCompiler().compile(
                plan.mode(), plan.scenes().getFirst(), plan.characters());

        assertEquals(2, calls.get());
        assertTrue(requests.getLast().messages().getFirst().content().contains("CORRECTION"));
        assertEquals(3, plan.characters().size());
        assertEquals(3, plan.scenes().getFirst().subjectCount());
        assertTrue(prompt.positive().contains("3girls"));
        assertTrue(prompt.positive().contains("long pink hair"));
        assertTrue(prompt.positive().contains("short black hair"));
        assertTrue(prompt.positive().contains("brown-green hair"));
    }

    @Test
    void repairsNonEnglishPromptValuesBeforeTheyReachTinyGrad() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        var generator = new TaskModelStoryVisualPlanGenerator(request -> {
            boolean first = calls.getAndIncrement() == 0;
            return """
                    {"globalMustInclude":["blue star","old observatory"],"globalMustNotInclude":[],
                    "characters":[],"scenes":[{"title":"Discovery","subjectCount":0,
                    "characterNames":[],"action":"%s","interaction":"","keyObjects":["telescope"],
                    "setting":"old observatory","time":"night","weather":"","emotion":"wonder",
                    "atmosphere":"quiet","lighting":"blue starlight","palette":"navy",
                    "composition":"wide composition","cameraAngle":"eye level","shotDistance":"wide shot",
                    "focus":"sharp","mustInclude":["blue star"],"mustNotInclude":["%s"],"fineDetails":[]}]}
                    """.formatted(first ? "ชี้ไปที่ดาว" : "pointing at the blue star",
                            first ? "คนเกิน" : "extra person");
        }, new ObjectMapper());

        StoryVisualPlan plan = generator.generate(
                "วาดฉากค้นพบดาวในหอดูดาว", "The telescope points at a blue star.",
                StoryIllustrationMode.DECISIVE_SCENE, List.of(), 3);

        assertEquals(2, calls.get());
        assertEquals("pointing at the blue star", plan.scenes().getFirst().action());
        assertTrue(plan.scenes().getFirst().mustNotInclude().contains("extra person"));
    }

    @Test
    void removesForbiddenSourceTagsAtTheSchemaBoundary() {
        CharacterVisualProfile profile = new CharacterVisualProfile(
                "Mali", "1girl", List.of("source_anime", "pink hair"), List.of(), List.of(),
                List.of("source_anime", "pink hair, score_4"), List.of());
        StorySceneSpec scene = new StorySceneSpec(
                "Portrait", 1, List.of("Mali"), "standing", "", List.of(), "observatory", "night", "",
                "calm", "quiet", "moonlight", "blue", "centered", "eye level", "portrait", "sharp",
                List.of("source_anime"), List.of(), List.of());

        String prompt = new PonyStoryPromptCompiler().compile(
                StoryIllustrationMode.CHARACTER_PORTRAIT, scene, List.of(profile)).positive();

        assertFalse(prompt.contains("source_anime"));
        assertFalse(prompt.contains("score_4"));
        assertTrue(prompt.contains("pink hair"));
    }

    @Test
    void compilerKeepsEveryNamedCharacterAndOmitsNames() {
        List<CharacterVisualProfile> characters = List.of(
                new CharacterVisualProfile("Itsuki Neko", "1girl", List.of("pink hair", "red eyes"),
                        List.of("white shirt"), List.of("round glasses"), List.of(), List.of()),
                new CharacterVisualProfile("Rena Raziel", "1girl", List.of("short black hair"),
                        List.of("black jacket"), List.of("round glasses"), List.of(), List.of()),
                new CharacterVisualProfile("Natawada Rin", "1girl", List.of("brown-green hair", "cyan eyes"),
                        List.of("blue shirt"), List.of("round glasses"), List.of(), List.of()));
        StorySceneSpec scene = new StorySceneSpec(
                "Promise", 3, List.of("Itsuki Neko", "Rena Raziel", "Natawada Rin"),
                "Itsuki Neko holds Rin's hand", "Itsuki looks at Rin", List.of(), "living room", "night", "",
                "tender", "quiet", "warm lamp", "", "two-shot", "eye level", "medium shot", "sharp",
                List.of(), List.of(), List.of());

        PonyStoryPromptCompiler.CompiledPrompt prompt = new PonyStoryPromptCompiler().compile(
                StoryIllustrationMode.DECISIVE_SCENE, scene, characters);

        assertTrue(prompt.positive().contains("3girls"));
        assertTrue(prompt.positive().contains("pink hair"));
        assertTrue(prompt.positive().contains("brown-green hair"));
        assertTrue(prompt.positive().contains("short black hair"));
        assertFalse(prompt.positive().contains("Itsuki"));
        assertFalse(prompt.positive().contains("Rin"));
        assertTrue(prompt.positive().contains("left girl holds right girl's hand"));
        assertEquals(3, prompt.facePrompts().size());
    }

    @Test
    void compilerAddsHumanExclusionsOnlyForStructuredAnimalOnlyScenes() {
        CharacterVisualProfile cat = new CharacterVisualProfile(
                "Mochi", "black cat", List.of("white paws"), List.of(), List.of(), List.of(), List.of());
        CharacterVisualProfile dog = new CharacterVisualProfile(
                "Biscuit", "brown dog", List.of("floppy ears"), List.of(), List.of(), List.of(), List.of());
        CharacterVisualProfile girl = new CharacterVisualProfile(
                "Mali", "1girl", List.of("black hair"), List.of(), List.of(), List.of(), List.of());
        StorySceneSpec animalScene = new StorySceneSpec(
                "Window", 2, List.of("Mochi", "Biscuit"), "Mochi watches rain beside Biscuit", "", List.of(), "living room",
                "night", "rain", "calm", "quiet", "window light", "blue", "centered", "eye level",
                "medium shot", "sharp", List.of(), List.of(), List.of());
        StorySceneSpec mixedScene = new StorySceneSpec(
                "Friends", 2, List.of("Mali", "Mochi"), "Mali pets Mochi", "", List.of(), "living room",
                "night", "", "happy", "warm", "lamp light", "", "two-shot", "eye level",
                "medium shot", "sharp", List.of(), List.of(), List.of());
        PonyStoryPromptCompiler compiler = new PonyStoryPromptCompiler();

        PonyStoryPromptCompiler.CompiledPrompt animal = compiler.compile(
                StoryIllustrationMode.DECISIVE_SCENE, animalScene, List.of(cat, dog));
        PonyStoryPromptCompiler.CompiledPrompt mixed = compiler.compile(
                StoryIllustrationMode.DECISIVE_SCENE, mixedScene, List.of(girl, cat));

        assertTrue(animal.positive().contains("animal focus, no humans"));
        assertTrue(animal.positive().contains("left animal"));
        assertTrue(animal.positive().contains("right animal"));
        assertFalse(animal.positive().contains("person"));
        assertTrue(animal.negative().contains("1girl"));
        assertTrue(animal.facePrompts().isEmpty());
        assertFalse(mixed.positive().contains("no humans"));
        assertFalse(mixed.negative().contains("1girl"));
    }

    @Test
    void rejectsSchemaExampleValuesWithoutGeneratingFallbackDetails() {
        var generator = new TaskModelStoryVisualPlanGenerator(request -> """
                {"globalMustInclude":[],"globalMustNotInclude":[],
                "characters":[],"scenes":[{"title":"short panel title","subjectCount":1,
                "characterNames":["exact character name"],"action":"one visible action",
                "interaction":"visible relationship action","keyObjects":["indispensable object"],
                "setting":"location","time":"time","weather":"weather","emotion":"emotion",
                "atmosphere":"mood","lighting":"lighting","palette":"palette",
                "composition":"composition","cameraAngle":"camera angle",
                "shotDistance":"shot distance","focus":"focus treatment",
                "mustInclude":[],"mustNotInclude":[],"fineDetails":[]}]}
                """, new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> generator.generate(
                "แต่งเรื่องของ Itsuki ในโรงแรม", "Itsuki เจรจากับคู่แข่งในโรงแรม",
                StoryIllustrationMode.DECISIVE_SCENE, List.of(), 3));
    }

    @Test
    void retriesWhenProviderReturnsNonJson() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        var generator = new TaskModelStoryVisualPlanGenerator(request -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("task model did not return a valid JSON object");
            }
            return """
                    {"characters":[],"scenes":[{"title":"Cathedral","subjectCount":0,
                    "characterNames":[],"action":"sunlight crosses the stone floor","interaction":"",
                    "keyObjects":["bronze bell"],"setting":"gothic cathedral","time":"dawn",
                    "weather":"","emotion":"solemn","atmosphere":"quiet","lighting":"golden sunlight",
                    "palette":"gold and gray","composition":"symmetrical","cameraAngle":"eye level",
                    "shotDistance":"wide shot","focus":"sharp","mustInclude":["stained glass"],
                    "mustNotInclude":[],"fineDetails":[]}]}
                    """;
        }, new ObjectMapper());

        StoryVisualPlan plan = generator.generate(
                "Illustrate dawn inside a gothic cathedral", "Sunlight reaches the old bell chamber.",
                StoryIllustrationMode.DECISIVE_SCENE, List.of(), 3);

        assertEquals(2, calls.get());
        assertEquals("gothic cathedral", plan.scenes().getFirst().setting());
    }

    @Test
    void keepsTheMiddleOfALongStoryWhenTheUserRequestIsAlsoLong() {
        List<TaskModelRequest> requests = new ArrayList<>();
        var generator = new TaskModelStoryVisualPlanGenerator(request -> {
            requests.add(request);
            return """
                    {"characters":[],"scenes":[{"title":"Observatory","subjectCount":0,
                    "characterNames":[],"action":"starlight fills the room","interaction":"",
                    "keyObjects":[],"setting":"observatory","time":"night","weather":"",
                    "emotion":"wonder","atmosphere":"quiet","lighting":"starlight","palette":"blue",
                    "composition":"wide","cameraAngle":"eye level","shotDistance":"wide shot",
                    "focus":"sharp","mustInclude":[],"mustNotInclude":[],"fineDetails":[]}]}
                    """;
        }, new ObjectMapper());
        String user = "u".repeat(3000) + " USER_MIDDLE " + "u".repeat(3000);
        String story = "s".repeat(3000) + " STORY_MIDDLE " + "s".repeat(3000);

        generator.generate(user, story, StoryIllustrationMode.DECISIVE_SCENE, List.of(), 3);

        String input = requests.getFirst().messages().getLast().content();
        assertTrue(input.contains("USER_MIDDLE"));
        assertTrue(input.contains("STORY_MIDDLE"));
        assertTrue(input.contains("ASSISTANT STORY"));
    }

    @Test
    void rejectsMissingCharacterProfilesInsteadOfSilentlyDroppingTheCharacter() {
        var generator = new TaskModelStoryVisualPlanGenerator(request -> """
                {"characters":[],"scenes":[{"title":"Meeting","subjectCount":1,
                "characterNames":["Mali"],"action":"Mali opens a door","interaction":"",
                "keyObjects":[],"setting":"observatory","time":"night","weather":"",
                "emotion":"wonder","atmosphere":"quiet","lighting":"starlight","palette":"blue",
                "composition":"wide","cameraAngle":"eye level","shotDistance":"wide shot",
                "focus":"sharp","mustInclude":[],"mustNotInclude":[],"fineDetails":[]}]}
                """, new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> generator.generate(
                "วาดภาพมะลิในหอดูดาว", "มะลิเปิดประตูหอดูดาว",
                StoryIllustrationMode.DECISIVE_SCENE, List.of(), 3));
    }
}
