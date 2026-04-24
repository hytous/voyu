package com.voyu.agent.service.agent;

import com.voyu.agent.model.agent.ConversationState;
import com.voyu.agent.model.api.TravelChatRequest;
import com.voyu.agent.model.history.ConversationEventRecord;
import com.voyu.agent.model.history.ConversationMessageRecord;
import com.voyu.agent.model.history.ConversationTurnRecord;
import com.voyu.agent.model.history.TravelConversationDocument;
import com.voyu.agent.repository.TravelConversationRepository;
import com.voyu.agent.service.llm.LlmFacade;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ClarificationAgent {

    private final LlmFacade llmFacade;
    private final TravelConversationRepository repository;

    public ClarificationAgent(LlmFacade llmFacade,
                              TravelConversationRepository repository) {
        this.llmFacade = llmFacade;
        this.repository = repository;
    }

    public ClarificationDecision inspect(ConversationState state) {
        TravelChatRequest request = state.getRequest();
        List<String> missing = new ArrayList<>();
        if (isBlank(request.getDestination())) {
            missing.add("destination");
        }
        if (isBlank(request.getTravelDays())) {
            missing.add("travelDays");
        }
        if (isBlank(request.getPreferences())) {
            missing.add("preferences");
        }
        if (isBlank(request.getBudget())) {
            missing.add("budget");
        }

        if (missing.isEmpty()) {
            return new ClarificationDecision(false, "", "关键约束已足够，可以进入规划执行。", List.of());
        }

        if (hasAskedClarification(state.getSessionId())) {
            return new ClarificationDecision(
                    false,
                    "",
                    "当前 session 已经进行过一次信息追问，本轮不再继续追问，直接基于已有信息进入规划执行。",
                    List.copyOf(missing));
        }

        String primaryMissing = missing.getFirst();
        String question = buildQuestion(state, missing);
        String thought = switch (primaryMissing) {
            case "destination" -> "当前对话还没有明确目的地，将在唯一一次追问里把缺失约束一起问完。";
            case "travelDays" -> "目的地已有，但还缺少天数等约束，将在唯一一次追问里一次性补齐。";
            case "preferences" -> "基础约束已有，但玩法偏好不清晰，将在唯一一次追问里一次性补齐。";
            case "budget" -> "基础路线可规划，但预算范围缺失，将在唯一一次追问里一次性补齐。";
            default -> "当前信息仍不完整，将发起本 session 唯一一次澄清提问。";
        };
        return new ClarificationDecision(true, question, thought, missing);
    }

    private String buildQuestion(ConversationState state, List<String> missing) {
        String llmQuestion = llmFacade.complete("""
                你是旅游规划助手。
                当前信息还不足以继续规划，请只输出一段简短中文回复，用来向用户提问补齐信息。
                要求：
                - 这是整个 session 唯一一次追问机会
                - 必须一次性把所有仍缺失的信息合并在这一条里问完，不能拆成多轮
                - 语气自然，像聊天产品中的助手追问
                - 优先级最高的信息放前面，但同一条消息里要覆盖全部缺口
                - 可以提示用户如果暂时没想好某项，直接说“未定”也可以
                - 不要输出标题、markdown、JSON
                """, """
                用户当前消息：%s
                当前已知目的地：%s
                当前已知出发地：%s
                当前已知天数：%s
                当前已知预算：%s
                当前已知偏好：%s
                缺失字段：%s
                """.formatted(
                blankAs(state.getRequest().getMessage(), "未提供"),
                blankAs(state.getRequest().getDestination(), "未知"),
                blankAs(state.getRequest().getDeparture(), "未知"),
                blankAs(state.getRequest().getTravelDays(), "未知"),
                blankAs(state.getRequest().getBudget(), "未知"),
                blankAs(state.getRequest().getPreferences(), "未知"),
                String.join(", ", missing)));

        if (!isBlank(llmQuestion)) {
            return llmQuestion.trim();
        }

        List<String> prompts = new ArrayList<>();
        if (missing.contains("destination")) {
            prompts.add("想去哪里");
        }
        if (missing.contains("travelDays")) {
            prompts.add("准备玩几天");
        }
        if (missing.contains("preferences")) {
            prompts.add("更偏好什么玩法");
        }
        if (missing.contains("budget")) {
            prompts.add("预算大概在什么范围");
        }
        return prompts.isEmpty()
                ? "我还需要再确认一点信息，才能继续往下规划。"
                : "为了继续帮你规划，我这边一次性确认下：%s？如果有暂时没定的项，也可以直接告诉我未定。"
                        .formatted(String.join("、", prompts));
    }

    private boolean hasAskedClarification(String sessionId) {
        if (isBlank(sessionId)) {
            return false;
        }
        return repository.findById(sessionId)
                .map(this::containsClarificationQuestion)
                .orElse(false);
    }

    private boolean containsClarificationQuestion(TravelConversationDocument document) {
        if (document == null) {
            return false;
        }

        if (document.getMessages() != null) {
            for (ConversationMessageRecord message : document.getMessages()) {
                if ("ASSISTANT".equals(message.getRole())
                        && "QUESTION".equals(message.getMessageType())
                        && !isBlank(message.getContent())) {
                    return true;
                }
            }
        }

        if (document.getTurns() != null) {
            for (ConversationTurnRecord turn : document.getTurns()) {
                if ("QUESTION".equals(turn.getAssistantMessageType())
                        && !isBlank(turn.getAssistantMessage())) {
                    return true;
                }
            }
        }

        if (document.getEvents() != null) {
            for (ConversationEventRecord event : document.getEvents()) {
                if (!"FINAL_ANSWER".equals(event.getEventType())) {
                    continue;
                }
                Map<String, Object> payload = event.getPayload();
                if (payload == null) {
                    continue;
                }
                Object responseKind = payload.get("responseKind");
                if ("QUESTION".equals(String.valueOf(responseKind))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String blankAs(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    public record ClarificationDecision(boolean shouldAsk,
                                        String question,
                                        String thought,
                                        List<String> missingFields) {
    }
}
