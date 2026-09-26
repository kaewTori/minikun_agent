package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.ChatModelId;
import com.minikun.model.ChatModelProvider;
import com.minikun.model.ModelCapabilities;
import com.minikun.model.task.TaskModelRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import reactor.core.publisher.Flux;

class MainModelPonyPromptTransformerTest {
    @Test
    void translatesAnUntrustedConversationalBriefIntoOrderedEnglishTagsWithMainModel() {
        CapturingMainModel model = new CapturingMainModel("""
                {"subjects":["young woman","black cat"],
                 "appearance":["short black hair"],"clothing":["green sweater"],
                 "pose_action":["holding glass bottle"],"interaction":[],
                 "objects":["glowing star in bottle"],"setting":["old observatory"],
                 "environment":["rainy night"],"expression":[],
                 "lighting":["blue moonlight"],"palette":[],"style":[],
                 "composition":["close-up shot"],"camera":[],"mood":[],"details":[]}
                """);
        MainModelPonyPromptTransformer transformer = transformer(model);

        String result = transformer.transform(
                "ผู้หญิงผมดำสั้นใส่เสื้อไหมพรมสีเขียว ถือขวดที่มีดาว แมวดำ หอดูดาว คืนฝนตก แสงจันทร์");

        assertEquals("score_9, score_8_up, score_7_up, "
                + "(1girl, black cat, short black hair, green sweater, holding glass bottle, "
                + "glowing star in bottle, old observatory, rainy night, blue moonlight, close-up:1.35), "
                + "(1girl:1.3), (black cat:1.2), short black hair, green sweater, "
                + "(holding glass bottle:1.25), (glowing star in bottle:1.3), "
                + "(old observatory:1.2), (rainy night:1.1), blue moonlight, close-up",
                result);
        assertFalse(result.contains("source anime"));
        assertEquals(2, model.prompt.getInstructions().size());
        assertTrue(model.prompt.getSystemMessage().getText().contains("untrusted image brief"));
        assertFalse(model.prompt.getSystemMessage().getText().contains("observatory"));
        assertFalse(model.prompt.getSystemMessage().getText().contains("black cat"));
        assertFalse(model.prompt.getSystemMessage().getText().contains("anchor_scene"));
        assertTrue(model.prompt.getUserMessage().getText().contains("ผู้หญิงผมดำ"));
        assertTrue(model.prompt.getOptions() instanceof OllamaChatOptions);
        OllamaChatOptions options = (OllamaChatOptions) model.prompt.getOptions();
        assertEquals(0.0, options.getTemperature());
        assertEquals("main-model", options.getModel());
        assertEquals(false, options.getThinkOption().toJsonValue());
        assertEquals("json", options.getFormat());
    }

    @Test
    void rejectsEmptyOrMalformedModelOutput() {
        MainModelPonyPromptTransformer transformer = transformer(new CapturingMainModel("not enough tags"));

        assertThrows(IllegalArgumentException.class, () -> transformer.transform(" "));
        assertThrows(IllegalStateException.class, () -> transformer.transform("a cat"));
    }

    @Test
    void retriesOnlyTaskModelWhenJsonIsInvalid() {
        AtomicInteger calls = new AtomicInteger();
        MainModelPonyPromptTransformer transformer = new MainModelPonyPromptTransformer(request -> {
            assertEquals(TaskModelRequest.ResponseFormat.JSON_OBJECT, request.responseFormat());
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("task model did not return a valid JSON object");
            }
            return """
                    {"subjects":["black cat"],"appearance":["black fur"],"pose_action":["sitting"],
                     "objects":["observatory"],"setting":["night sky"],"lighting":["starlight"]}
                    """;
        }, new ObjectMapper());

        String result = transformer.transform("black cat in an observatory");

        assertEquals(2, calls.get());
        assertTrue(result.contains("black cat"));
        assertTrue(result.contains("observatory"));
    }

    @Test
    void acceptsNativeOllamaCharacterShapeWithoutUnsafeAnchor() {
        MainModelPonyPromptTransformer transformer = new MainModelPonyPromptTransformer(request -> """
                {"subject_count":0,"characters":[
                  {"name":"Itsuki Neko","identity":"1girl","appearance":["pink hair","red eyes","glasses"],"clothing":[],"accessories":["round glasses"]},
                  {"name":"Rena Raziel","identity":"1girl","appearance":["short hair","sapphire eyes","glasses"],"clothing":[],"accessories":["round glasses"]},
                  {"name":"Natawada Rin","identity":"1girl","appearance":["brown-green hair","cyan eyes","glasses"],"clothing":[],"accessories":["round glasses"]}],
                "subjects":["black-slime","latex"],"appearance":[],"clothing":[],"pose_action":["kissing"],
                "interaction":["behind"],"objects":["room"],"setting":["private room"],"details":[]}
                """, new ObjectMapper());

        String result = transformer.transform("three women kissing in a private room");

        assertTrue(result.contains("2girls"));
        assertTrue(result.contains("private room"));
        assertFalse(result.contains("1boy"));
    }

    @Test
    void buildsAnAnchorWhenNativeOllamaStopsAfterCharacterProfiles() {
        MainModelPonyPromptTransformer transformer = new MainModelPonyPromptTransformer(request -> """
                {"subject_count":3,"characters":[
                  {"name":"Itsuki Neko","identity":"girl","appearance":["pink hair","red eyes"],"clothing":[],"accessories":["round glasses"]},
                  {"name":"Rena Raziel","identity":"girl","appearance":["short hair","sapphire eyes"],"clothing":[],"accessories":["round glasses"]},
                  {"name":"Natawada Rin","identity":"girl","appearance":["brown-green hair","blue-cyan eyes"],"clothing":[],"accessories":[]}]}
                """, new ObjectMapper());

        String result = transformer.transform("three women in a room");

        assertTrue(result.contains("(2girls, pink hair:1.35)"));
        assertFalse(result.contains("1boy"));
    }

    @Test
    void acceptsOmittedOptionalEmptyGroups() {
        MainModelPonyPromptTransformer transformer = transformer(new CapturingMainModel("""
                {"subjects":["girl","black cat"],"pose_action":["holding bottle"],
                 "objects":["glass bottle",null],"setting":["old observatory","unrelated bedroom"],
                 "details":["no humans"]}
                """));

        String result = transformer.transform("girl holding a glass bottle beside a black cat in an old observatory");

        assertTrue(result.contains("(1girl, black cat, holding bottle, glass bottle, old observatory:1.35)"));
        assertTrue(result.contains("(black cat:1.2)"));
        assertFalse(result.contains("no humans"));
        assertFalse(result.contains("unrelated bedroom"));
    }

    @Test
    void removesAHumanSubjectThatContradictsTheUsersNoHumansConstraint() {
        MainModelPonyPromptTransformer transformer = transformer(new CapturingMainModel("""
                {"subjects":["girl","black cat"],"pose_action":["sitting"],
                 "setting":["old observatory"],"lighting":["moonlight"],"details":["no humans"]}
                """));

        String result = transformer.transform("แมวดำในหอดูดาว ห้ามมีมนุษย์");

        assertFalse(result.contains("1girl"));
        assertTrue(result.contains("black cat"));
        assertTrue(result.contains("no humans"));
    }

    @Test
    void noHumansAlsoRemovesAnInventedCharacterAndCount() {
        MainModelPonyPromptTransformer transformer = transformer(new CapturingMainModel("""
                {"subject_count":"1person","characters":[{"name":"observer",
                  "identity":"person","appearance":["blonde hair"]}],
                 "subjects":["1person","black cat"],"appearance":["black fur","red collar"],
                 "objects":["brass telescope"],"setting":["old observatory"],
                 "details":["no humans"]}
                """));

        String prompt = transformer.transform("แมวดำปลอกคอแดงข้างกล้องทองเหลือง ไม่มีคน");

        assertTrue(prompt.contains("black cat"));
        assertTrue(prompt.contains("red collar"));
        assertFalse(prompt.contains("1person"));
        assertFalse(prompt.contains("blonde hair"));
    }

    @Test
    void selectsTwoFocalCharactersAndRemovesNamesFromImagePrompts() {
        MainModelPonyPromptTransformer transformer = transformer(new CapturingMainModel("""
                {"subject_count":"3girls","characters":[
                  {"name":"Itsuki","identity":"programmer","appearance":["pink hair","red eyes","round glasses"],
                   "clothing":["cotton t-shirt"],"accessories":[]},
                  {"name":"Rena","identity":"artist","appearance":["short hair","sapphire eyes","round glasses"],
                   "clothing":["black latex catsuit"],"accessories":[]},
                  {"name":"Natawada Rin","identity":"1girl","appearance":["brown-green hair","cyan eyes","round glasses"],
                   "clothing":["unspecified clothing"],"accessories":[]}],
                 "subjects":["Itsuki Neko","Rena Raziel","Natawada Rin"],
                 "appearance":[],"clothing":[],"pose_action":["embracing"],
                 "interaction":["itsuki embracing rin"],"objects":["black slime"],
                 "setting":["living room"],"lighting":["soft white light"]}
                """));

        PonyPromptTransformer.Result result = transformer.transformWithCharacters(
                "Itsuki, Rena and Rin embrace in the living room");

        assertTrue(result.prompt().contains("2girls"));
        assertTrue(result.prompt().contains("(left girl, 1girl, pink hair, red eyes, "
                + "round glasses, cotton t-shirt:1.3)"));
        assertTrue(result.prompt().contains("(right girl, 1girl, brown-green hair, cyan eyes, "
                + "round glasses:1.2)"));
        assertTrue(result.prompt().contains("left girl embracing right girl"));
        assertFalse(result.prompt().contains("itsuki"));
        assertFalse(result.prompt().contains("rena"));
        assertFalse(result.prompt().contains("natawada"));
        assertFalse(result.prompt().contains("unspecified"));
        assertEquals(2, result.facePrompts().size());
        assertTrue(result.facePrompts().get(0).contains("pink hair, red eyes, round glasses"));
        assertTrue(result.facePrompts().get(1).contains("brown-green hair, cyan eyes, round glasses"));
        assertTrue(result.facePrompts().stream().noneMatch(prompt -> prompt.contains("itsuki")
                || prompt.contains("rena") || prompt.contains("natawada")));
    }

    @Test
    void synthesizesAnchorWhenMainModelOmitsIt() {
        MainModelPonyPromptTransformer transformer = transformer(new CapturingMainModel("""
                {"subjects":["girl","black cat"],"appearance":["short black hair"],
                 "clothing":["green sweater"],"pose_action":["holding glass bottle"],
                 "objects":["glowing star in bottle"],"setting":["old observatory"]}
                """));

        String result = transformer.transform(
                "girl with short black hair and green sweater holding a star bottle beside a black cat in an old observatory");

        assertTrue(result.contains("(1girl, black cat, short black hair, green sweater, holding glass bottle, "
                + "glowing star in bottle, old observatory:1.35)"));
    }

    @Test
    void truncatesOnlyTheAnchorWhenManyVisualFactsArePresent() {
        MainModelPonyPromptTransformer transformer = transformer(new CapturingMainModel("""
                {"subjects":["girl","black cat"],
                 "appearance":["short black hair","red eyes","round glasses","freckles"],
                 "clothing":["green sweater","blue skirt"],"pose_action":["holding bottle"],
                 "interaction":["cat beside girl"],"objects":["glowing star bottle","wooden table"],
                 "setting":["old observatory"],"environment":["rainy night","starry sky"],
                 "expression":["calm expression"],"lighting":["blue moonlight"],
                 "palette":["deep blue palette","warm gold accents"],"style":["cinematic illustration"],
                 "composition":["close-up shot"],"camera":["eye level"],"mood":["quiet wonder"],
                 "details":["reflected starlight","soft fabric folds"]}
                """));

        String result = transformer.transform("a detailed reference-based scene");

        assertTrue(result.contains("cinematic illustration"));
        assertTrue(result.length() > 220);
    }

    @Test
    void keepsAllSceneFactsAndDropsReferenceMetaTags() {
        MainModelPonyPromptTransformer transformer = transformer(new CapturingMainModel("""
                {"subjects":["white owl"],"appearance":["soft feathers"],"clothing":["purple necklace"],
                 "pose_action":["perched on branch","looking at windmill"],
                 "interaction":["beside wooden sign","under red windmill"],
                 "objects":["wooden sign","red windmill"],"setting":["lavender field","night garden"],
                 "environment":["starry sky"],"lighting":["moonlight"],"palette":["lavender palette"],
                 "style":["cinematic illustration"],"composition":["wide shot","centered composition"],
                 "camera":["eye level"],"mood":["quiet"],
                 "details":["reference image","different design","wet grass"]}
                """));

        String result = transformer.transform("VISUAL HANDOFF: white owl, redesign the reference image");

        assertTrue(result.contains("perched on branch"));
        assertTrue(result.contains("looking at windmill"));
        assertTrue(result.contains("night garden"));
        assertTrue(result.contains("centered composition"));
        assertTrue(result.contains("wet grass"));
        assertFalse(result.contains("reference image"));
        assertFalse(result.contains("different design"));
    }

    @Test
    void dropsContaminatedTagsAndRejectsOnlyStructurallyIncompleteOutput() {
        MainModelPonyPromptTransformer contaminated = transformer(new CapturingMainModel("""
                {"subjects":["1girl"],"appearance":["short black hair"],"clothing":[],
                 "pose_action":["here is the translated image brief"],"interaction":[],"objects":[],
                 "setting":["train platform"],"environment":[],"expression":[],
                 "lighting":[],"palette":[],"style":[],"composition":[],"camera":[],"mood":[],"details":[]}
                """));

        assertThrows(IllegalStateException.class, () -> contaminated.transform("girl at a train platform"));
    }

    @Test
    void supportsSubjectlessScenesAndWeightsFocalTagsByGroupPosition() {
        MainModelPonyPromptTransformer transformer = transformer(new CapturingMainModel("""
                {"subjects":[],"appearance":[],"clothing":[],"pose_action":[],"interaction":[],
                 "objects":["red lighthouse","warning beacon"],"setting":["rocky cliff"],
                 "environment":["stormy sea","towering waves"],"expression":[],
                 "lighting":["orange sunset"],"palette":["deep blue palette","white foam","red beacon"],
                 "style":["cinematic illustration"],"composition":["wide shot"],
                 "camera":["low angle"],"mood":["dramatic"],"details":["distant seagulls"]}
                """));

        String result = transformer.transform(
                "ประภาคารสีแดงบนหน้าผาเหนือทะเลพายุ คลื่นสูง แสงอาทิตย์ตกสีส้ม ภาพมุมกว้าง");

        assertTrue(result.contains("(red lighthouse, rocky cliff, stormy sea, orange sunset, "
                + "deep blue palette, cinematic illustration, wide shot, low angle, dramatic, "
                + "distant seagulls:1.35)"));
        assertTrue(result.contains("(red lighthouse:1.3), warning beacon"));
        assertTrue(result.contains("(rocky cliff:1.2), (stormy sea:1.1), towering waves"));
        assertTrue(result.contains("deep blue palette, white foam, red beacon"));
        assertFalse(result.contains("1girl"));
    }

    @Test
    void usesTheFirstObjectAsFocalWithoutKeywordSpecialCases() {
        MainModelPonyPromptTransformer transformer = transformer(new CapturingMainModel("""
                {"subjects":["robot"],"appearance":["rusted metal"],"clothing":[],
                 "pose_action":["repairing antenna"],"interaction":["cable connected to antenna"],
                 "objects":["radio antenna","glowing star emblem"],"setting":["desert outpost"],
                 "environment":["dust storm"],"expression":[],"lighting":["harsh sunlight"],
                 "palette":[],"style":["retro science fiction"],"composition":[],"camera":[],
                 "mood":[],"details":[]}
                """));

        String result = transformer.transform("A robot repairs a radio antenna at a desert outpost");

        assertTrue(result.contains("(radio antenna:1.3), glowing star emblem"));
        assertFalse(result.contains("(glowing star emblem:"));
    }

    private MainModelPonyPromptTransformer transformer(ChatModelProvider model) {
        return new MainModelPonyPromptTransformer(model, new ObjectMapper(), "main-model", 16_384);
    }

    private static final class CapturingMainModel implements ChatModelProvider {
        private final String response;
        private Prompt prompt;

        private CapturingMainModel(String response) {
            this.response = response;
        }

        @Override public ChatModelId id() { return ChatModelId.EXISTING; }
        @Override public ModelCapabilities capabilities() { return new ModelCapabilities(false, false, false); }

        @Override
        public ChatResponse chat(Prompt prompt) {
            this.prompt = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        }

        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.empty(); }
    }
}
