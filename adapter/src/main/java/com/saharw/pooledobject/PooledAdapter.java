package com.saharw.pooledobject;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.SOURCE;

/**
 * Created by Sahar on 01/27/2017.
 */
@Target(FIELD)
@Retention(SOURCE)
@Documented
public @interface PooledAdapter {
    Class<? extends PooledTypeAdapter<?>> value();
}
