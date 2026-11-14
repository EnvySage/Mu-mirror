package org.xianshen.mumirrorb.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class UsageReportTest {
    final UUID invocation=UUID.randomUUID();
    final UsageReport.Parser parser=new UsageReport.Parser(new ObjectMapper());
    String attempt(UUID id,String counts) {
        return "{\"id\":\""+id+"\",\"status\":\"success\",\"provider\":\"deepseek\",\"model\":\"cn:deepseek-v4.1-flash\",\"usage_state\":\"complete\","+counts+"}";
    }
    UsageReport parse(String attempts,boolean complete) {
        return parser.parse(("{\"version\":1,\"invocation_id\":\""+invocation+"\",\"complete\":"+complete+",\"attempts\":["+attempts+"]}").getBytes(StandardCharsets.UTF_8),invocation);
    }
    @Test void exactCountsAndDecimalCreditsArePreservedWithoutAmbiguousQuota() {
        var report=parse(attempt(UUID.randomUUID(),"\"input_tokens\":34,\"output_tokens\":16,\"total_tokens\":50,\"credits\":\"0.100000000001\",\"quota\":{\"percent\":17}"),true);
        assertTrue(report.complete());
        assertEquals(50L,report.attempts().get(0).totalTokens());
        assertEquals("0.100000000001",report.attempts().get(0).credits().toPlainString());
        assertEquals("积分",report.attempts().get(0).creditUnit());
    }
    @Test void missingMalformedMismatchedAndOversizedReportsAreIncompleteWithoutPhysicalAttempts() {
        assertFalse(parser.parse(null,invocation).complete());
        assertTrue(parser.parse(null,invocation).attempts().isEmpty());
        assertFalse(parser.parse("{}".getBytes(StandardCharsets.UTF_8),invocation).complete());
        assertFalse(parser.parse(new byte[49153],invocation).complete());
        assertFalse(parser.parse(("{\"version\":1,\"invocation_id\":\""+UUID.randomUUID()+"\",\"complete\":true,\"attempts\":[]}").getBytes(StandardCharsets.UTF_8),invocation).complete());
        assertTrue(parse("",true).complete());
    }
    @Test void invalidSuffixAndDuplicateIdsCannotDoubleCountKnownPrefix() {
        UUID id=UUID.randomUUID();
        String valid=attempt(id,"\"total_tokens\":50");
        var duplicate=parse(valid+","+valid,true);
        assertFalse(duplicate.complete());
        assertEquals(1,duplicate.attempts().size());
        var invalid=parse(valid+","+attempt(UUID.randomUUID(),"\"total_tokens\":-1"),true);
        assertFalse(invalid.complete());
        assertEquals(1,invalid.attempts().size());
    }
    @Test void partialCountsRemainUnknownAndFloatsOrNumericCreditFieldsAreRejected() {
        var report=parse(attempt(UUID.randomUUID(),"\"input_tokens\":34,\"output_tokens\":null,\"total_tokens\":null").replace("\"complete\"","\"partial\""),false);
        assertFalse(report.complete());
        assertNull(report.attempts().get(0).outputTokens());
        assertFalse(parse(attempt(UUID.randomUUID(),"\"input_tokens\":1.5"),true).complete());
        assertFalse(parse(attempt(UUID.randomUUID(),"\"credits\":0.1"),true).complete());
        assertFalse(parse(attempt(UUID.randomUUID(),"\"credits\":\"NaN\""),true).complete());
        assertFalse(parse(attempt(UUID.randomUUID(),"\"total_tokens\":9223372036854775808"),true).complete());
    }
    @Test void falseCompletenessAndWrappedVersionsCannotHideUnknownUsage() {
        var unknown=parse(attempt(UUID.randomUUID(),"\"total_tokens\":null"),true);
        assertEquals("unknown",unknown.attempts().get(0).usageState());
        String root="{\"version\":4294967297,\"invocation_id\":\""+invocation+"\",\"complete\":true,\"attempts\":[]}";
        assertFalse(parser.parse(root.getBytes(StandardCharsets.UTF_8),invocation).complete());
        root=root.replace("4294967297","1")+" {}";
        assertFalse(parser.parse(root.getBytes(StandardCharsets.UTF_8),invocation).complete());
    }
    @Test void sharedAiFixtureAcceptsBracketedAndUnknownNamesWithExactKnownTotals() throws Exception {
        UUID id=UUID.fromString("0b59b70e-9bc2-4d21-97b2-ef062d8c5e8d");
        byte[] bytes=new org.springframework.core.io.ClassPathResource("usage/ai-usage-name-contract.json").getContentAsByteArray();
        var report=parser.parse(bytes,id);
        assertTrue(report.complete());assertEquals(2,report.attempts().size());
        assertEquals(100L,report.attempts().stream().mapToLong(UsageReport.Attempt::totalTokens).sum());
        assertEquals("[体验]deepseek-v4.1-flash",report.attempts().get(0).model());
        assertEquals("unknown",report.attempts().get(1).provider());
        assertEquals("[体验]custom/model?mode=fast",report.attempts().get(1).model());
    }
}
