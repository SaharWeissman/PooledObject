package com.saharw.pooledobject;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Created by Sahar on 01/27/2017.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
public @interface Pooled {
    int version() default 0;
}
