package core.global.docs;

import core.global.enums.errorcode.CommunityErrorCode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CommunityErrorDocs {
    CommunityErrorCode[] value();
}
