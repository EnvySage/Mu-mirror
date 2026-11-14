package org.xianshen.mumirrorb.usage;

import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import java.lang.ref.*;
import java.util.*;

/** Weak identity keys: equal proto values may originate from distinct historical model profiles. */
final class UsageSnapshots {
    private record Binding(UUID owner,UsageEnvelope.Snapshot snapshot) { }
    private final ReferenceQueue<CommonProto.LlmConfig> collected=new ReferenceQueue<>();
    private final Map<IdentityReference,Binding> bindings=new HashMap<>();
    synchronized void remember(UUID owner,CommonProto.LlmConfig config,UsageEnvelope.Snapshot snapshot) {
        drain();bindings.put(new IdentityReference(config,collected),new Binding(owner,snapshot));
    }
    synchronized UsageEnvelope.Snapshot find(UUID owner,CommonProto.LlmConfig config) {
        drain();Binding binding=bindings.get(new IdentityReference(config,null));
        return binding!=null&&binding.owner().equals(owner)?binding.snapshot():null;
    }
    private void drain() {Reference<?> key;while((key=collected.poll())!=null)bindings.remove(key);}
    private static final class IdentityReference extends WeakReference<CommonProto.LlmConfig> {
        private final int hash;
        IdentityReference(CommonProto.LlmConfig config,ReferenceQueue<CommonProto.LlmConfig> queue) {
            super(config,queue);hash=System.identityHashCode(config);
        }
        @Override public int hashCode() {return hash;}
        @Override public boolean equals(Object value) {
            return value==this||value instanceof IdentityReference other&&get()!=null&&get()==other.get();
        }
    }
}
