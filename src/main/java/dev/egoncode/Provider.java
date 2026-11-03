package dev.egoncode;

import java.util.*;

interface Provider {
    record Call(String id, String name, Map<String,Object> arguments) {}
    record Turn(String text, List<Call> calls, long inputTokens, long outputTokens) {}
    interface Output { void delta(String text); }
    Turn respond(Session session, String instructions, List<Map<String,Object>> tools, Output output) throws Exception;
    void addUser(Session session, String text);
    void addToolResult(Session session, Call call, String result);
}
