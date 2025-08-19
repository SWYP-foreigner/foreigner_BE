import http from "k6/http";
import ws from "k6/ws";
import { check, sleep } from "k6";
import { SharedArray } from "k6/data";

// 미리 준비된 JSON (유저-방 조합)
const validCombinations = new SharedArray("valid_combinations", function () {
    return JSON.parse(open("./valid_combinations.json"));
});

export const options = {
    vus: 10,          // 동시 100명
    duration: "1m",    // 5분간 부하
};

// 서버 주소 (환경에 맞게 수정)
const BASE_URL = "http://localhost:8080/api/v1/chat";
const WS_URL = "ws://localhost:8080/plain-ws/chat";

export default function () {
    const combo = validCombinations[Math.floor(Math.random() * validCombinations.length)];
    const roomId = combo.roomId;
    const userId = combo.userId;

    // ✅ 1. WebSocket 연결 + 메시지 송수신
    ws.connect(WS_URL, {}, function (socket) {
        socket.on("open", function () {
            console.log(`🔗 WS Connected: user=${userId}, room=${roomId}`);

            // 메시지 전송 (테스트용)
            const payload = JSON.stringify({
                roomId: roomId,
                senderId: userId,
                content: `테스트 메시지 from ${userId}`,
            });
            socket.send(payload);
        });

        socket.on("message", function (msg) {
            check(msg, {
                "메시지 수신됨": (m) => m.length > 0,
            });
        });

        socket.on("close", () => console.log(`❌ WS Closed: user=${userId}`));

        sleep(1); // 1초 유지 후 종료
        socket.close();
    });

    // ✅ 2. REST API 호출들

    // (1) 채팅방 리스트 조회
    const roomsRes = http.get(`${BASE_URL}/rooms?userId=${userId}`);
    check(roomsRes, { "방 리스트 조회 200": (r) => r.status === 200 });

    // (2) 메시지 무한 스크롤 조회 (최근 메시지 가져오기)
    const msgsRes = http.get(`${BASE_URL}/rooms/${roomId}/messages?userId=${userId}`);
    check(msgsRes, { "메시지 조회 200": (r) => r.status === 200 });

    // (3) 메시지 읽음 처리
    if (msgsRes.status === 200) {
        const messages = msgsRes.json().data;
        if (messages && messages.length > 0) {
            const lastMessageId = messages[messages.length - 1].id;
            const readRes = http.post(
                `${BASE_URL}/rooms/read?roomId=${roomId}&userId=${userId}&messageId=${lastMessageId}`
            );
            check(readRes, { "읽음 처리 200": (r) => r.status === 200 });
        }
    }

    // (4) 메시지 검색 (한글 키워드 → 인코딩 처리)
    const keyword = encodeURIComponent("test");
    const searchRes = http.get(
        `${BASE_URL}/search?roomId=${roomId}&userId=${userId}&search=${keyword}`
    );
    check(searchRes, { "검색 200": (r) => r.status === 200 });

    sleep(1); // API 호출 사이 간격
}
