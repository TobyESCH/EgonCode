package dev.egoncode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class OpenAiProvider implements Provider {
    private final String baseUrl;
    private final String credential;
    private final boolean plan;
    OpenAiProvider(String baseUrl, String credential, boolean plan) {
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.credential = credential; this.plan = plan;
    }

    @Override public void addUser(Session session, String text) {
        session.history.add(Json.obj("role","user","content",text));
    }
    @Override public void addToolResult(Session session, Call call, String result) {
        session.history.add(Json.obj("type","function_call_output","call_id",call.id(),"output",result));
    }

    @Override public Turn respond(Session session, String instructions, List<Map<String,Object>> tools, Output output) throws Exception {
        Map<String,Object> body = Json.obj("model",session.model,"instructions",instructions,"input",session.history,
                "store",false,"stream",true);
        if (!tools.isEmpty()) body.put("tools",plan
                ? List.of(Json.obj("type","namespace","name","workspace",
                    "description","Tools for the current coding project.","tools",tools))
                : tools);
        final java.util.concurrent.atomic.AtomicReference<Map<String,Object>> completed = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.List<Map<String,Object>> streamedItems = new java.util.ArrayList<>();
        Http.stream(baseUrl + "/responses", credential, null, body, headers -> {
            if (!plan) {
                Map<String,Object> limits=RateLimits.capture(headers,"openai");
                if (!limits.isEmpty()) session.event("rate_limits",limits);
            }
        }, (name, event) -> {
            String type = Json.get(event,"type");
            if (type.equals("response.output_text.delta")) output.delta(Json.get(event,"delta"));
            if (type.equals("response.output_item.done")) streamedItems.add(Json.map(event.get("item")));
            if (type.equals("response.completed")) completed.set(Json.map(event.get("response")));
            if (type.equals("response.failed")) {
                String code=Json.get(Json.map(Json.map(event.get("response")).get("error")),"code");
                if (plan && code.equals("subscription_sharing_usage_limit_exceeded"))
                    throw new IllegalStateException("ChatGPT plan usage limit reached for this request. Review the applicable limit at https://chatgpt.com/settings/usage");
                throw new IllegalStateException("Model response failed: " + Json.write(event.get("response")));
            }
            if (type.equals("error")) throw new IllegalStateException("Model stream error: " + Json.write(event));
        });
        if (completed.get() == null) throw new IllegalStateException("Stream ended without response.completed");
        List<Object> outputItems=Json.list(completed.get().get("output"));
        if (outputItems.isEmpty()) outputItems=new ArrayList<>(streamedItems);
        if (outputItems.isEmpty())
            throw new IllegalStateException("Model completed with no output (status=" + Json.get(completed.get(),"status") + ")");
        List<Call> calls = new ArrayList<>(); StringBuilder text = new StringBuilder();
        for (Object raw : outputItems) {
            Map<String,Object> item = Json.map(raw);
            String type = Json.get(item,"type");
            if (type.equals("function_call")) {
                String args = Json.get(item,"arguments");
                calls.add(new Call(Json.get(item,"call_id"), Json.get(item,"name"), Json.map(Json.parse(args))));
            } else if (type.equals("message")) {
                for (Object content : Json.list(item.get("content"))) {
                    Map<String,Object> block = Json.map(content);
                    if (Json.get(block,"type").equals("output_text")) text.append(Json.get(block,"text"));
                }
            }
            session.history.add(new LinkedHashMap<>(item));
        }
        Map<String,Object> usage = Json.map(completed.get().get("usage"));
        return new Turn(text.toString(),calls,Json.number(usage.get("input_tokens"),0),Json.number(usage.get("output_tokens"),0));
    }
}
