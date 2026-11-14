package org.xianshen.mumirrorb.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Message;
import io.grpc.*;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.grpc.gen.CommonProto;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Observes trailers without consuming or changing business response messages. */
@Component
public class UsageClientInterceptor implements ClientInterceptor {
    public static final CallOptions.Key<UUID> OWNER=CallOptions.Key.create("mirror-usage-owner");
    public static final CallOptions.Key<UUID> TASK=CallOptions.Key.create("mirror-usage-task");
    public static final CallOptions.Key<String> SCENE=CallOptions.Key.create("mirror-usage-scene");
    public static final Metadata.Key<byte[]> REPORT=Metadata.Key.of("mirror-usage-bin",Metadata.BINARY_BYTE_MARSHALLER);
    private static final Metadata.Key<String> INVOCATION_HEADER=Metadata.Key.of("mirror-usage-invocation-id",Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> TASK_HEADER=Metadata.Key.of("mirror-usage-task-id",Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> SCENE_HEADER=Metadata.Key.of("mirror-usage-scene",Metadata.ASCII_STRING_MARSHALLER);
    private final UsageStore store;
    private final UsageReport.Parser parser;
    private final UsageSnapshots snapshots=new UsageSnapshots();
    public UsageClientInterceptor(UsageStore store,ObjectMapper json) {this.store=store;parser=new UsageReport.Parser(json);}
    public void remember(UUID user,CommonProto.LlmConfig config,UUID profile) {
        snapshots.remember(user,config,store.snapshot(user,config,profile));
    }
    public void finishTask(UUID user,UUID task) {store.finishTask(user,task);}
    @Override public <Q,A> ClientCall<Q,A> interceptCall(MethodDescriptor<Q,A> method,CallOptions options,Channel channel) {
        ClientCall<Q,A> delegate=channel.newCall(method,options);
        UUID user=options.getOption(OWNER);
        if(user==null||method.getFullMethodName().contains("EmbeddingService/"))return delegate;
        UUID invocation=UUID.randomUUID();
        var scope=UsageTaskScope.current(user);
        UUID task=scope==null?options.getOption(TASK):scope.task();if(task==null)task=UUID.randomUUID();
        String scene=scope==null?options.getOption(SCENE):scope.scene();
        if(scene==null||!scene.matches("[a-z_]{1,64}"))scene="unknown";
        UUID taskId=task;String sceneId=scene;
        OffsetDateTime started=OffsetDateTime.now();
        AtomicReference<UsageEnvelope> envelope=new AtomicReference<>();
        return new ForwardingClientCall.SimpleForwardingClientCall<>(delegate) {
            @Override public void start(Listener<A> listener,Metadata headers) {
                headers.put(INVOCATION_HEADER,invocation.toString());headers.put(TASK_HEADER,taskId.toString());headers.put(SCENE_HEADER,sceneId);
                super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(listener) {
                    @Override public void onClose(Status status,Metadata trailers) {
                        UsageEnvelope e=envelope.get();
                        if(e==null) {
                            e=new UsageEnvelope(invocation,taskId,user,sceneId,scope==null,
                                    store.snapshot(user,CommonProto.LlmConfig.getDefaultInstance()),started);
                        }
                        store.complete(e,parser.parse(trailers.get(REPORT),invocation),
                                status.isOk()?"success":status.getCode()==Status.Code.CANCELLED?"cancelled":"error");
                        super.onClose(status,trailers);
                    }
                },headers);
            }
            @Override public void sendMessage(Q message) {
                CommonProto.LlmConfig config=CommonProto.LlmConfig.getDefaultInstance();
                if(message instanceof Message proto) {
                    var field=proto.getDescriptorForType().findFieldByName("llm_config");
                    if(field!=null&&proto.getField(field) instanceof CommonProto.LlmConfig llm) config=llm;
                }
                var snapshot=snapshots.find(user,config);
                if(snapshot==null)snapshot=store.snapshot(user,config);
                UsageEnvelope e=new UsageEnvelope(invocation,taskId,user,sceneId,scope==null,snapshot,started);
                if(envelope.compareAndSet(null,e))store.begin(e);
                super.sendMessage(message);
            }
        };
    }
}
