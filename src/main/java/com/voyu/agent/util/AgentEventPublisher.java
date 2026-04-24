package com.voyu.agent.util;

import com.voyu.agent.model.agent.AgentEventType;
import com.voyu.agent.model.agent.AgentSseEvent;
import com.voyu.agent.model.agent.ConversationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class AgentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AgentEventPublisher.class);

    private final SseEmitter emitter;
    private final Supplier<Instant> clock;
    private final Consumer<AgentSseEvent> eventConsumer;

    public AgentEventPublisher(SseEmitter emitter, Supplier<Instant> clock) {
        this(emitter, clock, event -> {
        });
    }

    public AgentEventPublisher(SseEmitter emitter, Supplier<Instant> clock, Consumer<AgentSseEvent> eventConsumer) {
        this.emitter = emitter;
        this.clock = clock;
        this.eventConsumer = eventConsumer;
    }

    public AgentSseEvent publish(ConversationState state, AgentEventType type, Map<String, Object> payload) throws IOException {
        AgentSseEvent event = new AgentSseEvent(type, state.getSessionId(), state.getCurrentRound(), clock.get(), payload);
        emitter.send(SseEmitter.event().data(event));
        try {
            eventConsumer.accept(event);
        } catch (Exception ex) {
            log.warn("Failed to consume agent event {}", type, ex);
        }
        return event;
    }
}
