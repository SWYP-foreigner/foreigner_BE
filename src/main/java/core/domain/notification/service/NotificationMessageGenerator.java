package core.domain.notification.service;

import core.domain.notification.dto.NotificationEvent;
import core.domain.user.entity.User;
import core.global.enums.NotificationType; // NotificationType enum 임포트
import org.springframework.stereotype.Component;

@Component
public class NotificationMessageGenerator {

    /**
     * NotificationEvent를 기반으로 사용자에게 표시될 메시지를 생성합니다.
     * @param event 알림 이벤트 데이터
     * @return 생성된 메시지 문자열
     */
    public String generateMessage(User actor , NotificationEvent event) {
        String actorName = actor.getFirstName();
        if (actorName == null || actorName.isBlank()) {
            actorName = "누군가";
        }

        return switch (event.notificationType()) {

            case chat -> {
                String snippet = event.contentSnippet();
                if (snippet != null && snippet.length() > 30) {
                    snippet = snippet.substring(0, 30) + "...";
                }
                yield actorName + "님으로부터 새로운 메시지: " + snippet;
            }

            case post -> actorName + "님이 회원님의 게시글에 댓글을 남겼습니다.";
            case comment -> actorName + "님이 회원님의 댓글에 답글을 남겼습니다.";

            case follow -> actorName + "님이 회원님의 팔로우 요청을 수락했습니다.";
            case receive -> actorName + "님이 회원님을 팔로우하기 시작했습니다.";
            case newuser -> actorName + "님이 새로 가입했습니다! 환영해주세요.";
            default -> "새로운 알림이 도착했습니다.";
        };
    }
}