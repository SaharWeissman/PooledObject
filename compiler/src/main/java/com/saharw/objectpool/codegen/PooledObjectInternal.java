package com.saharw.objectpool.codegen;

/**
 * Created by Sahar on 02/04/2017.
 */

public abstract class PooledObjectInternal<T> {
    abstract public T get(T obj);
    abstract public boolean returnObject(T obj);
}
