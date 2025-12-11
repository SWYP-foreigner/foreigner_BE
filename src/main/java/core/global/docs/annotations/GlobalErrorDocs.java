package core.global.docs.annotations;

import core.global.enums.errorcode.GlobalErrorCode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface GlobalErrorDocs {
    GlobalErrorCode[] value();
}
