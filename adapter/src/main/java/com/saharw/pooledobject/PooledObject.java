package com.saharw.pooledobject;

import java.util.Enumeration;
import java.util.Hashtable;

/**
 * Created by Sahar on 02/04/2017.
 */

public abstract class PooledObject<T> {

    private T o;

    public PooledObject(){
        expirationTime = 30000; // 30 seconds
        locked = new Hashtable<>();
        unlocked = new Hashtable<>();
    }
    public PooledObject(T o)
    {
        expirationTime = 30000; // 30 seconds
        locked = new Hashtable<>();
        unlocked = new Hashtable<>();
        this.o = o;
    }

    private long expirationTime;
    private Hashtable<T, Long> locked, unlocked;
    public abstract T create(T o);
    public abstract boolean validate(T o);
    public abstract void expire(T o);
    public synchronized T checkOut()
    {
        long now = System.currentTimeMillis();
        T o;
        if( unlocked.size() > 0 )
        {
            Enumeration<T> e = unlocked.keys();
            while(e.hasMoreElements())
            {
                o = e.nextElement();
                if((now - ((Long)unlocked.get(o)).longValue()) > expirationTime) {

                    // object has expired
                    unlocked.remove(o);
                    expire(o);
                    o = null;
                }
                else {
                    if(validate(o)) {
                        unlocked.remove(o);
                        locked.put(o, new Long(now));
                        return(o);
                    }
                    else {

                        // object failed validation
                        unlocked.remove(o);
                        expire(o);
                        o = null;
                    }
                }
            }
        }
        // no objects available, create a new one
        o = create(this.o);
        locked.put(o, new Long(now));
        return(o);
    }
    public synchronized void checkIn(T o) {
        locked.remove(o);
        unlocked.put(o, new Long(System.currentTimeMillis()));
    }
}
