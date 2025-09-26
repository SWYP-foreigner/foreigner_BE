package core.global.metrics.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TrackEvent {
    String value(); // 이벤트명: 예) "post.like", "payment.checkout"
}