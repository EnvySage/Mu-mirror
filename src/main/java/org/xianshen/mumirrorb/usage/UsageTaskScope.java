package org.xianshen.mumirrorb.usage;

import java.util.UUID;

/** Explicit thread-local grouping, established inside the async worker rather than the HTTP thread. */
public final class UsageTaskScope implements AutoCloseable {
    public record Binding(UUID user,UUID task,String scene) { }
    private static final ThreadLocal<Binding> CURRENT=new ThreadLocal<>();
    private final Binding previous;
    private UsageTaskScope(Binding binding) {previous=CURRENT.get();CURRENT.set(binding);}
    public static UsageTaskScope open(UUID user,UUID task,String scene) {return new UsageTaskScope(new Binding(user,task,scene));}
    public static Binding current(UUID user) {var scope=CURRENT.get();return scope!=null&&scope.user().equals(user)?scope:null;}
    @Override public void close() {if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
}
