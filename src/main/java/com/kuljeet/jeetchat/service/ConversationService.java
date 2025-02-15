package com.kuljeet.jeetchat.service;

import static com.kuljeet.jeetchat.DbBoiii.getConvId;

import com.kuljeet.jeetchat.config.UserDetailsImpl;
import com.kuljeet.jeetchat.entity.ConversationEntity;
import com.kuljeet.jeetchat.entity.UserEntity;
import com.kuljeet.jeetchat.exception.EntityException;
import com.kuljeet.jeetchat.mapper.ChatMessageMapper;
import com.kuljeet.jeetchat.model.ChatMessage;
import com.kuljeet.jeetchat.model.MessageDeliveryStatusEnum;
import com.kuljeet.jeetchat.model.MessageType;
import com.kuljeet.jeetchat.model.UnseenMessageCountResponse;
import com.kuljeet.jeetchat.model.UserConnection;
import com.kuljeet.jeetchat.repository.ConversationRepository;
import com.kuljeet.jeetchat.repository.UserRepository;
import com.kuljeet.jeetchat.utils.SecurityUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

@Service
@Slf4j
public class ConversationService {
  private final UserRepository userRepository;
  private final SecurityUtils securityUtils;
  private final ChatMessageMapper chatMessageMapper;
  private final ConversationRepository conversationRepository;
  private final OnlineOfflineService onlineOfflineService;
  private final SimpMessageSendingOperations simpMessageSendingOperations;

  public ConversationService(
      UserRepository userRepository,
      SecurityUtils securityUtils,
      ChatMessageMapper chatMessageMapper,
      ConversationRepository conversationRepository,
      OnlineOfflineService onlineOfflineService,
      SimpMessageSendingOperations simpMessageSendingOperations) {
    this.userRepository = userRepository;
    this.securityUtils = securityUtils;
    this.chatMessageMapper = chatMessageMapper;
    this.conversationRepository = conversationRepository;
    this.onlineOfflineService = onlineOfflineService;
    this.simpMessageSendingOperations = simpMessageSendingOperations;
  }

  public List<UserConnection> getUserFriends() {
    UserDetailsImpl userDetails = securityUtils.getUser();
    String username = userDetails.getUsername();
    List<UserEntity> users = userRepository.findAll();
    UserEntity thisUser =
        users.stream()
            .filter(user -> user.getUsername().equals(username))
            .findFirst()
            .orElseThrow(EntityException::new);

    return users.stream()
        .filter(user -> !user.getUsername().equals(username))
        .map(
            user ->
                UserConnection.builder()
                    .connectionId(user.getId())
                    .connectionUsername(user.getUsername())
                    .convId(getConvId(user, thisUser))
                    .unSeen(0)
                    .isOnline(onlineOfflineService.isUserOnline(user.getId()))
                    .build())
        .toList();
  }

  public List<UnseenMessageCountResponse> getUnseenMessageCount() {
    List<UnseenMessageCountResponse> result = new ArrayList<>();
    UserDetailsImpl userDetails = securityUtils.getUser();
    List<ConversationEntity> unseenMessages =
        conversationRepository.findUnseenMessagesCount(userDetails.getId());

    if (!CollectionUtils.isEmpty(unseenMessages)) {
      Map<UUID, List<ConversationEntity>> unseenMessageCountByUser = new HashMap<>();
      for (ConversationEntity entity : unseenMessages) {
        List<ConversationEntity> values =
            unseenMessageCountByUser.getOrDefault(entity.getFromUser(), new ArrayList<>());
        values.add(entity);
        unseenMessageCountByUser.put(entity.getFromUser(), values);
      }
      log.info("there are some unseen messages for {}", userDetails.getUsername());
      unseenMessageCountByUser.forEach(
          (user, entities) -> {
            result.add(
                UnseenMessageCountResponse.builder()
                    .count((long) entities.size())
                    .fromUser(user)
                    .build());
            updateMessageDelivery(user, entities, MessageDeliveryStatusEnum.DELIVERED);
          });
    }
    return result;
  }

  public List<ChatMessage> getUnseenMessages(UUID fromUserId) {
    List<ChatMessage> result = new ArrayList<>();
    UserDetailsImpl userDetails = securityUtils.getUser();
    List<ConversationEntity> unseenMessages =
        conversationRepository.findUnseenMessages(userDetails.getId(), fromUserId);

    if (!CollectionUtils.isEmpty(unseenMessages)) {
      log.info(
          "there are some unseen messages for {} from {}", userDetails.getUsername(), fromUserId);
      updateMessageDelivery(fromUserId, unseenMessages, MessageDeliveryStatusEnum.SEEN);
      result = chatMessageMapper.toChatMessages(unseenMessages, userDetails, MessageType.UNSEEN);
    }
    return result;
  }

  private void updateMessageDelivery(
      UUID user,
      List<ConversationEntity> entities,
      MessageDeliveryStatusEnum messageDeliveryStatusEnum) {
    entities.forEach(e -> e.setDeliveryStatus(messageDeliveryStatusEnum.toString()));
    onlineOfflineService.notifySender(user, entities, messageDeliveryStatusEnum);
    conversationRepository.saveAll(entities);
  }

  public List<ChatMessage> setReadMessages(List<ChatMessage> chatMessages) {
    List<UUID> inTransitMessageIds = chatMessages.stream().map(ChatMessage::getId).toList();
    List<ConversationEntity> conversationEntities =
        conversationRepository.findAllById(inTransitMessageIds);
    conversationEntities.forEach(
        message -> message.setDeliveryStatus(MessageDeliveryStatusEnum.SEEN.toString()));
    List<ConversationEntity> saved = conversationRepository.saveAll(conversationEntities);

    return chatMessageMapper.toChatMessages(saved, securityUtils.getUser(), MessageType.CHAT);
  }
}
