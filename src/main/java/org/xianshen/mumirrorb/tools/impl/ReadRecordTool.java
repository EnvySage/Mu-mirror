package org.xianshen.mumirrorb.tools.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.xianshen.mumirrorb.mapper.ChatSearchMapper;
import org.xianshen.mumirrorb.tools.*;
import java.util.*;

/** Reads only the user's confirmed, authorized segments, including unindexed ones. */
@Component
@RequiredArgsConstructor
public class ReadRecordTool implements ToolExecutor {
    private final ChatSearchMapper mapper;
    @Override public String name() { return "read_record"; }
    @Override public ToolEffect effect() { return ToolEffect.READ; }
    @Override public ToolDefinition definition() {
        return new ToolDefinition(name(), "按顺序分页读取记录的已授权完整片段（含未索引内容）。按 next_offset 继续；full 仅指已授权范围读完。",
                "{\"record_id\": 123（必填正整数）, \"offset\": 0, \"limit\": 20（1-20）}");
    }
    @Override public ToolExecutionResult execute(UUID userId, Map<String,Object> args) {
        args=args==null?Map.of():args;
        long recordId=SearchRecordsTool.longOf(args.get("record_id"),-1);
        if(recordId<=0) return ToolExecutionResult.builder().success(false).summary("read_record:缺少有效的 record_id")
                .payload(Map.of("error","record_id 必须为正整数")).build();
        int offset=Math.max(0,SearchRecordsTool.intOf(args.get("offset"),0));
        int limit=Math.max(1,Math.min(20,SearchRecordsTool.intOf(args.get("limit"),20)));
        var rows=mapper.readAuthorizedRecord(userId,recordId,limit+1,offset);
        boolean more=rows.size()>limit;
        var selected=more?rows.subList(0,limit):rows;
        var snippets=new ArrayList<Map<String,Object>>();
        for(var row:selected) {
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("chunk_id",row.getChunkId()); item.put("record_id",row.getRecordId());
            item.put("date",row.getCreatedAt()); item.put("text",row.getContent()); snippets.add(item);
        }
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("record_id",recordId); payload.put("offset",offset); payload.put("snippets",snippets);
        payload.put("next_offset",more?(long)offset+selected.size():null);
        payload.put("complete",!more); payload.put("body_available",!selected.isEmpty());
        payload.put("content_scope",more?"partial":"full");
        return ToolExecutionResult.builder().success(true).summary("read_record:"+selected.size()+"段已授权正文")
                .payload(payload).build();
    }
}
