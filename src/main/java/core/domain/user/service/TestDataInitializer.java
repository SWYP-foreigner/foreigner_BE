package core.domain.user.service;

import com.fasterxml.jackson.databind.ObjectMapper; // ObjectMapper 임포트 추가
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.Sex;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatRoomRepository;


import java.io.File;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class TestDataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatMessageRepository chatMessageRepository;

    public TestDataInitializer(
            UserRepository userRepository,
            ChatRoomRepository chatRoomRepository,
            ChatParticipantRepository chatParticipantRepository,
            ChatMessageRepository chatMessageRepository
    ) {
        this.userRepository = userRepository;
        this.chatRoomRepository = chatRoomRepository;
        this.chatParticipantRepository = chatParticipantRepository;
        this.chatMessageRepository = chatMessageRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        // 이미 유저가 존재하면 데이터 생성 건너뛰기
        if (userRepository.count() >= 1000) {
            System.out.println("테스트 데이터가 이미 존재함, 생성 생략");
            return;
        }

        // 1. 1000명의 테스트 유저 생성
        List<User> users = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            User user = new User(
                    "User" + i,
                    i % 2 == 0 ? Sex.MALE : Sex.FEMALE,
                    20 + (i % 30),
                    "TestNation",
                    "Test user " + i,
                    "Testing",
                    "English",
                    "None",
                    "test",
                    "test" + i,
                    "user" + i + "@example.com"
            );
            users.add(user);
        }
        userRepository.saveAll(users);
        System.out.println("1000명의 테스트 유저 생성 완료!");

        // 2. 500개의 채팅방과 각 채팅방에 2명의 참가자 생성
        List<ChatRoom> rooms = new ArrayList<>();
        List<ChatParticipant> participants = new ArrayList<>();
        final int NUM_ROOMS = 500;

        for (int i = 0; i < NUM_ROOMS; i++) {
            // 1:1 채팅방 생성 (is_group=false)
            ChatRoom room = new ChatRoom(false, Instant.now());
            rooms.add(room);

            // 두 명의 참가자 생성 및 연결
            User user1 = users.get(i * 2);
            User user2 = users.get(i * 2 + 1);

            ChatParticipant participant1 = new ChatParticipant(room, user1);
            ChatParticipant participant2 = new ChatParticipant(room, user2);
            participants.add(participant1);
            participants.add(participant2);
        }

        chatRoomRepository.saveAll(rooms);
        chatParticipantRepository.saveAll(participants);
        System.out.println(NUM_ROOMS + "개의 테스트 채팅방과 " + (NUM_ROOMS * 2) + "명의 참가자 생성 완료!");
        generateRoomIdsJsonFile(rooms);
        // 3. 500,000개의 메시지 생성
        List<ChatMessage> messages = new ArrayList<>();
        final int MESSAGES_PER_ROOM = 1000;

        for (int i = 0; i < NUM_ROOMS; i++) {
            ChatRoom room = rooms.get(i);
            User user1 = users.get(i * 2);
            User user2 = users.get(i * 2 + 1);

            for (int j = 0; j < MESSAGES_PER_ROOM; j++) {
                // 두 유저가 번갈아가며 메시지 전송
                User sender = (j % 2 == 0) ? user1 : user2;

                ChatMessage message = new ChatMessage(
                        room,
                        sender,
                        "메시지 #" + (j + 1) + " from " + sender.getName()
                );
                messages.add(message);
            }
        }

        chatMessageRepository.saveAll(messages);
        System.out.println((NUM_ROOMS * MESSAGES_PER_ROOM) + "개의 메시지 생성 완료!");
    }
    private void generateRoomIdsJsonFile(List<ChatRoom> rooms) {
        List<String> roomIds = rooms.stream()
                .map(ChatRoom::getId)
                .map(String::valueOf) // UUID를 String으로 변환
                .toList();

        try {
            ObjectMapper mapper = new ObjectMapper();
            String projectRoot = System.getProperty("user.dir");
            String filePath = Paths.get(projectRoot, "src", "main", "test", "roomIds.json").toString();

            File file = new File(filePath);

// 파일 쓰기
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, roomIds);
            System.out.println("K6 테스트를 위한 roomIds.json 파일 생성 완료!");
        } catch (Exception e) {
            System.err.println("roomIds.json 파일 생성 중 오류 발생: " + e.getMessage());
        }
    }
}
