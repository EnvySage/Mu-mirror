package org.xianshen.mumirrorb.grpc;
import io.grpc.ManagedChannel;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.grpc.gen.TarotProto;
import org.xianshen.mumirrorb.grpc.gen.TarotReadingGrpc;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
@Component
public class TarotGrpcClient {
    private final TarotReadingGrpc.TarotReadingBlockingStub stub;
    private final AiGrpcClient configs;
    public TarotGrpcClient(ManagedChannel channel,AiGrpcClient configs){this.stub=TarotReadingGrpc.newBlockingStub(channel);this.configs=configs;}
    public void checkConfigured(UUID user){configs.buildLlmConfigFor(user);}
    public TarotProto.TarotAnalyzeResponse analyze(UUID user,TarotProto.TarotAnalyzeRequest request){
        return stub.withDeadlineAfter(60,TimeUnit.SECONDS).analyze(request.toBuilder().setLlmConfig(configs.buildLlmConfigFor(user)).build());
    }
}
