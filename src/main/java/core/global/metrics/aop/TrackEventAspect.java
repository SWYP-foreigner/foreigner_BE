package core.global.metrics.aop;

import core.global.metrics.annotation.TrackEvent;
import core.global.metrics.event.EventPublisher;
import core.global.metrics.event.UsageEvent;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.publisher.Mono;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Aspect
@Component
@RequiredArgsConstructor
public class TrackEventAspect {

    private final EventPublisher publisher;

    @Around("@annotation(trackEvent)")
    public Object around(ProceedingJoinPoint pjp, TrackEvent trackEvent) throws Throwable {
        Object ret = pjp.proceed();

        String userId = Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .map(auth -> auth.getName()).orElse("anonymous");

        String device = "web";
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            device = Optional.ofNullable(req.getHeader("X-Device")).orElse("web");
        }

        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Map<String, Object> meta = new HashMap<>();
        meta.put("class", pjp.getTarget().getClass().getSimpleName());
        meta.put("method", sig.getMethod().getName());

        UsageEvent ev = UsageEvent.builder()
                .timestamp(Instant.now())
                .user_id(userId)
                .event(trackEvent.value())
                .device(device)
                .meta(meta)
                .build();

        // 비동기 전송, 본요청 성능 영향 최소화
        Mono.fromRunnable(() -> publisher.publish(ev).subscribe()).subscribe();

        return ret;
    }
}