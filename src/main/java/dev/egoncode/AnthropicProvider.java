package dev.egoncode;

import java.util.*;

final class AnthropicProvider implements Provider {
    private final String key;
    AnthropicProvider(String key) { this.key = key; }
    @Override public void addUser(Session session, String text) { session.history.add(Json.obj("role","user","content",text)); }
    @Override public void addToolResult(Session session, Call call, String result) {
        Map<String,Object> block = Json.obj("type","tool_result","tool_use_id",call.id(),"content",result);
        if (!session.history.isEmpty()) {
            Map<String,Object> last = session.history.getLast();
            if (Json.get(last,"role").equals("user") && last.get("content") instanceof List<?>) {
                Json.list(last.get("content")).add(block); return;
            }
        }
        List<Object> blocks = new ArrayList<>(); blocks.add(block);
        session.history.add(Json.obj("role","user","content",blocks));
    }
    @Override public Turn respond(Session session, String instructions, List<Map<String,Object>> tools, Output output) throws Exception {
        List<Object> defs = new ArrayList<>();
        for (Map<String,Object> tool : tools) defs.add(Json.obj("name",tool.get("name"),"description",tool.get("description"),"input_schema",tool.get("parameters")));
        Map<String,Object> body = Json.obj("model",session.model,"system",instructions,"messages",session.history,
                "tools",defs,"max_tokens",8192,"stream",true);
        if (defs.isEmpty()) body.remove("tools");
        Map<Integer,Map<String,Object>> blocks = new TreeMap<>();
        final long[] usage = new long[2]; final boolean[] done = {false};
        Http.stream("https://api.anthropic.com/v1/messages",key,"2023-06-01",body,headers -> {
            Map<String,Object> limits=RateLimits.capture(headers,"anthropic");
            if (!limits.isEmpty()) session.event("rate_limits",limits);
        },(name,event) -> {
            String type = Json.get(event,"type");
            if (type.equals("message_start")) usage[0] = Json.number(Json.map(Json.map(event.get("message")).get("usage")).get("input_tokens"),0);
            if (type.equals("content_block_start")) {
                int index = (int)Json.number(event.get("index"),0);
                blocks.put(index,new LinkedHashMap<>(Json.map(event.get("content_block"))));
            }
            if (type.equals("content_block_delta")) {
                int index = (int)Json.number(event.get("index"),0);
                Map<String,Object> block = blocks.get(index); Map<String,Object> delta = Json.map(event.get("delta"));
                if (block == null) return;
                if (Json.get(delta,"type").equals("text_delta")) {
                    String text = Json.get(delta,"text"); block.put("text",Json.get(block,"text") + text); output.delta(text);
                } else if (Json.get(delta,"type").equals("input_json_delta"))
                    block.put("_partial",Json.get(block,"_partial") + Json.get(delta,"partial_json"));
            }
            if (type.equals("message_delta")) usage[1] = Json.number(Json.map(event.get("usage")).get("output_tokens"),usage[1]);
            if (type.equals("message_stop")) done[0] = true;
            if (type.equals("error")) throw new IllegalStateException("Anthropic stream error: " + Json.write(event));
        });
        if (!done[0]) throw new IllegalStateException("Anthropic stream ended without message_stop");
        List<Object> content = new ArrayList<>(); List<Call> calls = new ArrayList<>(); StringBuilder text = new StringBuilder();
        for (Map<String,Object> block : blocks.values()) {
            if (block.containsKey("_partial")) block.put("input",Json.parse(Json.get(block,"_partial")));
            block.remove("_partial"); content.add(block);
            if (Json.get(block,"type").equals("text")) text.append(Json.get(block,"text"));
            if (Json.get(block,"type").equals("tool_use")) calls.add(new Call(Json.get(block,"id"),Json.get(block,"name"),Json.map(block.get("input"))));
        }
        session.history.add(Json.obj("role","assistant","content",content));
        return new Turn(text.toString(),calls,usage[0],usage[1]);
    }
}
