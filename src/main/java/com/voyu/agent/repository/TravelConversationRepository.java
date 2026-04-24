package com.voyu.agent.repository;

import com.voyu.agent.model.history.TravelConversationDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TravelConversationRepository extends MongoRepository<TravelConversationDocument, String> {

    List<TravelConversationDocument> findTop5ByUserIdAndSessionIdNotOrderByUpdatedAtDesc(String userId, String sessionId);
}
