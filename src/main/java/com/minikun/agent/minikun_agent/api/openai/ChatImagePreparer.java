package com.minikun.agent.minikun_agent.api.openai;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import com.minikun.pcs.model.ImageSource;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class ChatImagePreparer {
    private ChatImagePreparer() { }

    static Result prepare(ChatKnowledgeSelection knowledge) {
        try {
            List<ImageSource> selected = ImageAttachmentSelector.select(
                    knowledge.selection().knowledgeContext().images());
            List<ChatAttachment> attachments = ImageAttachmentMapper.map(selected);
            if (attachments.size() != selected.size()) {
                throw new IllegalStateException("image attachment count does not match selected image count");
            }
            return new Result(selected.isEmpty() ? null : new ImageAwareness(selected.size()), attachments);
        } catch (RuntimeException exception) {
            log.warn("Image attachment selection failed; continuing without attachments", exception);
            return Result.EMPTY;
        }
    }

    record Result(ImageAwareness awareness, List<ChatAttachment> attachments) {
        static final Result EMPTY = new Result(null, List.of());
        Result { attachments = attachments == null ? List.of() : List.copyOf(attachments); }
    }
}
