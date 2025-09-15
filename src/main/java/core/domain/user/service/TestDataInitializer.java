package core.domain.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.Sex;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.IntStream;

//@Component
public class TestDataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatMessageRepository chatMessageRepository;
    private static final int NUM_USERS = 1000;
    private static final int NUM_ONE_ON_ONE_ROOMS = 500;
    private static final int MESSAGES_PER_ONE_ON_ONE_ROOM = 1000;
    private static final int NUM_GROUP_ROOMS = 1000;
    private static final int GROUP_PARTICIPANTS_PER_ROOM = 10;
    private static final int MESSAGES_PER_GROUP_ROOM = 1000;
    private final Random random = new Random();

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
        clearAllData();

        List<User> users = new ArrayList<>();
        for (int i = 1; i <= NUM_USERS; i++) {
            User user = new User(
                    "User"+i,
                    "User" + i,
                    i % 2 == 0 ? "MALE" : "FEMALE", // Sex 대신 Gender 사용
                    "30/07/2025",
                    "TestNation",
                    "Test user " + i,
                    "Testing",
                    "English", // String 대신 List<String> 사용
                    "None", // String 대신 List<String> 사용
                    "test",
                    "test" + i,
                    "user" + i + "@example.com",
                    "ew"
            );
            users.add(user);
        }

        userRepository.saveAll(users);
        System.out.println(NUM_USERS + "명의 테스트 유저 생성 완료!");
        List<ChatRoom> oneOnOneRooms = new ArrayList<>();
        List<ChatParticipant> oneOnOneParticipants = new ArrayList<>();
        List<ChatMessage> oneOnOneMessages = new ArrayList<>();

        for (int i = 0; i < NUM_ONE_ON_ONE_ROOMS; i++) {
            ChatRoom room = new ChatRoom(false, Instant.now());
            oneOnOneRooms.add(room);

            User user1 = users.get(i * 2);
            User user2 = users.get(i * 2 + 1);

            ChatParticipant participant1 = new ChatParticipant(room, user1);
            ChatParticipant participant2 = new ChatParticipant(room, user2);
            oneOnOneParticipants.add(participant1);
            oneOnOneParticipants.add(participant2);

            for (int j = 0; j < MESSAGES_PER_ONE_ON_ONE_ROOM; j++) {
                User sender = (j % 2 == 0) ? user1 : user2;

                ChatMessage message = new ChatMessage(
                        room,
                        sender,
                        "1대1 메시지 #" + (j + 1) + " from " + sender.getLastName()
                );
                oneOnOneMessages.add(message);
            }
        }
        chatRoomRepository.saveAll(oneOnOneRooms);
        chatParticipantRepository.saveAll(oneOnOneParticipants);
        chatMessageRepository.saveAll(oneOnOneMessages);
        System.out.println(NUM_ONE_ON_ONE_ROOMS + "개의 1대1 채팅방과 " + (NUM_ONE_ON_ONE_ROOMS * MESSAGES_PER_ONE_ON_ONE_ROOM) + "개의 메시지 생성 완료!");


        List<ChatRoom> groupRooms = new ArrayList<>();
        List<ChatParticipant> groupParticipants = new ArrayList<>();
        List<ChatMessage> groupMessages = new ArrayList<>();

        for (int i = 0; i < NUM_GROUP_ROOMS; i++) {
            ChatRoom groupRoom = new ChatRoom(true, Instant.now());
            groupRooms.add(groupRoom);

            List<User> shuffledUsers = new ArrayList<>(users);
            Collections.shuffle(shuffledUsers);

            List<User> participantsInRoom = shuffledUsers.subList(0, GROUP_PARTICIPANTS_PER_ROOM);
            for (User user : participantsInRoom) {
                ChatParticipant participant = new ChatParticipant(groupRoom, user);
                groupParticipants.add(participant);
            }

            for (int j = 0; j < MESSAGES_PER_GROUP_ROOM; j++) {
                User sender = participantsInRoom.get(random.nextInt(participantsInRoom.size()));
                ChatMessage message = new ChatMessage(
                        groupRoom,
                        sender,
                        "그룹 메시지 #" + (j + 1) + " from " + sender.getLastName()
                );
                groupMessages.add(message);
            }
        }
        chatRoomRepository.saveAll(groupRooms);
        chatParticipantRepository.saveAll(groupParticipants);
        chatMessageRepository.saveAll(groupMessages);
        System.out.println(NUM_GROUP_ROOMS + "개의 그룹 채팅방과 " + (NUM_GROUP_ROOMS * GROUP_PARTICIPANTS_PER_ROOM) + "명의 참가자 생성 완료!");
        System.out.println((NUM_GROUP_ROOMS * MESSAGES_PER_GROUP_ROOM) + "개의 그룹 메시지 생성 완료!");

        generateRoomIdsJsonFile(oneOnOneRooms, groupRooms);
        generateUserIdsJsonFile(users);
        generateValidCombinationsJsonFile(oneOnOneParticipants, groupParticipants);
    }

    private void clearAllData() {
        chatMessageRepository.deleteAllInBatch();
        chatParticipantRepository.deleteAllInBatch();
        chatRoomRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        System.out.println("기존 테스트 데이터 삭제 완료.");
    }

    private void generateRoomIdsJsonFile(List<ChatRoom> oneOnOneRooms, List<ChatRoom> groupRooms) {
        List<String> roomIds = new ArrayList<>();
        roomIds.addAll(oneOnOneRooms.stream().map(r -> String.valueOf(r.getId())).toList());
        roomIds.addAll(groupRooms.stream().map(r -> String.valueOf(r.getId())).toList());

        try {
            ObjectMapper mapper = new ObjectMapper();
            String projectRoot = System.getProperty("user.dir");
            String filePath = Paths.get(projectRoot, "src", "main", "test", "roomIds.json").toString();
            File file = new File(filePath);

            mapper.writerWithDefaultPrettyPrinter().writeValue(file, roomIds);
            System.out.println("K6 테스트를 위한 roomIds.json 파일 생성 완료!");
        } catch (Exception e) {
            System.err.println("roomIds.json 파일 생성 중 오류 발생: " + e.getMessage());
        }
    }

    private void generateUserIdsJsonFile(List<User> users) {
        List<String> userIds = users.stream()
                .map(User::getId)
                .map(String::valueOf)
                .toList();

        try {
            ObjectMapper mapper = new ObjectMapper();
            String projectRoot = System.getProperty("user.dir");
            String filePath = Paths.get(projectRoot, "src", "main", "test", "userIds.json").toString();
            File file = new File(filePath);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, userIds);
            System.out.println("K6 테스트를 위한 userIds.json 파일 생성 완료!");
        } catch (Exception e) {
            System.err.println("userIds.json 파일 생성 중 오류 발생: " + e.getMessage());
        }
    }

    private void generateValidCombinationsJsonFile(List<ChatParticipant> oneOnOneParticipants, List<ChatParticipant> groupParticipants) {
        List<Object> combinations = new ArrayList<>();
        oneOnOneParticipants.forEach(p -> combinations.add(new Object() {
            public final String userId = String.valueOf(p.getUser().getId());
            public final String roomId = String.valueOf(p.getChatRoom().getId());
        }));
        groupParticipants.forEach(p -> combinations.add(new Object() {
            public final String userId = String.valueOf(p.getUser().getId());
            public final String roomId = String.valueOf(p.getChatRoom().getId());
        }));

        try {
            ObjectMapper mapper = new ObjectMapper();
            String projectRoot = System.getProperty("user.dir");
            String filePath = Paths.get(projectRoot, "src", "main", "test", "valid_combinations.json").toString();
            File file = new File(filePath);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, combinations);
            System.out.println("K6 테스트를 위한 valid_combinations.json 파일 생성 완료!");
        } catch (Exception e) {
            System.err.println("valid_combinations.json 파일 생성 중 오류 발생: " + e.getMessage());
        }
    }
}