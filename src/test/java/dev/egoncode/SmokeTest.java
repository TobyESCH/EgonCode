package dev.egoncode;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class SmokeTest {
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Map<String,Object> sample = Json.obj("text","hello\n\"world\"","items",Arrays.asList(1,true,null));
        check(Json.map(Json.parse(Json.write(sample))).containsKey("items"),"JSON round trip");
        Map<String,Object> claims=Json.obj("iss","https://auth.openai.com","aud",List.of("oaiapp_test"),
                "nonce","nonce-test","exp",2_000_000_000L,"sub","user-test");
        ChatGptAuth.validateClaims(claims,"oaiapp_test","nonce-test",1_900_000_000L);
        claims.put("aud","oaiapp_test");
        ChatGptAuth.validateClaims(claims,"oaiapp_test","nonce-test",1_900_000_000L);
        claims.put("aud",List.of("other-client"));
        try {
            ChatGptAuth.validateClaims(claims,"oaiapp_test","nonce-test",1_900_000_000L);
            throw new AssertionError("wrong audience accepted");
        } catch (SecurityException expected) { check(expected.getMessage().contains("audience"),"audience rejection"); }
        ModelCatalog catalog=new ModelCatalog(new ChatGptAuth(new Store(Files.createTempDirectory("egon-catalog-"))));
        List<ModelCatalog.Model> offered=List.of(new ModelCatalog.Model("gpt-6-luna","Luna"),
                new ModelCatalog.Model("gpt-6.1-sol","Sol"),new ModelCatalog.Model("gpt-6-astra","Astra"));
        check(catalog.recommended("chatgpt",offered).id().equals("gpt-6.1-sol"),"recommended model");
        check(Bunny.frame("running",0).size()==14 && Bunny.frame("sleeping",0).size()==14,"pet frames");
        check(!Bunny.frame("running",0).equals(Bunny.frame("running",1)),"animated pet frames");
        check(TerminalUi.activityState(false,89_999).equals("idle") &&
                TerminalUi.activityState(false,90_000).equals("sleeping") &&
                TerminalUi.activityState(true,120_000).equals("running"),"pet activity timing");
        check(Main.nextMode(Tools.Mode.EDIT)==Tools.Mode.AUTO &&
                Main.nextMode(Tools.Mode.AUTO)==Tools.Mode.PLAN &&
                Main.nextMode(Tools.Mode.PLAN)==Tools.Mode.EDIT,"mode shortcut cycle");

        Path root = Files.createTempDirectory("egon-smoke-");
        Store store = new Store(root.resolve("store"));
        Files.writeString(root.resolve("hello.txt"),"old value\n");
        Tools tools = new Tools(root,description -> true,store);
        Session session = Session.create(root,"local","fake-model");
        check(tools.execute("read_file",Json.obj("path","hello.txt"),session).contains("old value"),"read tool");
        check(tools.execute("read_file",Json.obj("path","../missing"),session).contains("outside project"),"path boundary");
        tools.planMode(true);
        check(tools.execute("write_file",Json.obj("path","hello.txt","content","bad"),session).startsWith("Denied"),"plan mode");
        tools.planMode(false);
        check(tools.execute("replace_in_file",Json.obj("path","hello.txt","old_text","old","new_text","new"),session).startsWith("Updated"),"edit tool");
        check(Files.readString(root.resolve("hello.txt")).startsWith("new"),"edit content");
        session.save(store);
        check(Session.load(store,session.id,root).events.size()==1,"session persistence");
        check(Diff.preview("old\n","new\n").contains("-old\n+new\n"),"colored diff source lines");
        int[] approvals={0};
        Store autoStore=new Store(root.resolve("auto-store"));
        Tools automatic=new Tools(root,description -> { approvals[0]++; return false; },autoStore);
        automatic.mode(Tools.Mode.AUTO);
        Session autoSession=Session.create(root,"local","fake-model");
        check(automatic.execute("write_file",Json.obj("path","auto.txt","content","created\n"),autoSession).contains("[diff] auto.txt"),
                "auto mode writes and reports diff");
        check(Files.readString(root.resolve("auto.txt")).equals("created\n") && approvals[0]==0,"auto edit skips prompt");
        check(automatic.execute("run_command",Json.obj("command","pwd"),autoSession).startsWith("exit=0") &&
                approvals[0]==0,"auto check skips prompt");
        check(automatic.execute("run_command",Json.obj("command","echo no > unsafe.txt"),autoSession).equals("Denied by user") &&
                approvals[0]==1 && !Files.exists(root.resolve("unsafe.txt")),"unsafe command asks");
        check(!Tools.autoCommand("npm run build && echo unsafe"),"auto command cannot chain commands");
        check(automatic.execute("write_file",Json.obj("path",".git/config","content","bad"),autoSession).contains("Protected"),
                "project metadata protected");
        autoStore.write("permissions.json",Json.obj("write","deny"),false);
        check(automatic.execute("write_file",Json.obj("path","denied.txt","content","bad"),autoSession).equals("Denied by user"),
                "explicit deny overrides auto");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final int[] calls = {0};
        server.createContext("/responses", exchange -> {
            Map<String,Object> body = Json.map(Json.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            check(Boolean.FALSE.equals(body.get("store")),"store false");
            check(Boolean.TRUE.equals(body.get("stream")),"stream true");
            check(Json.get(Json.map(Json.list(body.get("tools")).getFirst()),"type").equals("function"),"local tools use flat schema");
            calls[0]++;
            Map<String,Object> output;
            if (calls[0]==1) output = Json.obj("type","function_call","call_id","call-1","name","workspace.read_file","arguments","{\"path\":\"hello.txt\"}");
            else {
                check(Json.list(body.get("input")).stream().anyMatch(item -> Json.get(Json.map(item),"type").equals("function_call_output")),"tool output sent back");
                output = Json.obj("type","message","role","assistant","content",List.of(Json.obj("type","output_text","text","done")));
            }
            String event = "data: " + Json.write(Json.obj("type","response.output_item.done","item",output)) + "\n\n" +
                    "data: " + Json.write(Json.obj("type","response.completed","response",Json.obj("status","completed",
                            "output",List.of(),"usage",Json.obj("input_tokens",5,"output_tokens",2)))) + "\n\n";
            byte[] bytes = event.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");
            exchange.getResponseHeaders().set("x-ratelimit-remaining-requests","24");
            exchange.getResponseHeaders().set("x-ratelimit-reset-requests","12s");
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            OpenAiProvider provider = new OpenAiProvider("http://127.0.0.1:" + server.getAddress().getPort(),"test",false);
            provider.addUser(session,"Read hello.txt");
            Provider.Turn first = provider.respond(session,"test",tools.definitions(),text -> {});
            check(first.calls().size()==1,"tool call parsed");
            String result = tools.execute(first.calls().getFirst().name(),first.calls().getFirst().arguments(),session);
            provider.addToolResult(session,first.calls().getFirst(),result);
            Provider.Turn second = provider.respond(session,"test",tools.definitions(),text -> {});
            check(second.text().equals("done"),"assistant message parsed");
            check(calls[0]==2,"two model steps");
            check(RateLimits.lines(RateLimits.latest(session)).getFirst().contains("24") &&
                    RateLimits.lines(RateLimits.latest(session)).getFirst().contains("12s"),"OpenAI API rate headers");
        } finally { server.stop(0); }
        HttpHeaders anthropicHeaders=HttpHeaders.of(Map.of(
                "anthropic-ratelimit-input-tokens-remaining",List.of("8000"),
                "anthropic-ratelimit-input-tokens-reset",List.of("2026-11-03T22:00:00Z")), (key,value) -> true);
        check(RateLimits.lines(RateLimits.capture(anthropicHeaders,"anthropic")).getFirst().contains("2026-11-03"),
                "Anthropic API reset header");
        CodexUsage.Snapshot codexQuota=CodexUsage.parse(Json.obj("rateLimits",Json.obj("limitId","codex",
                "primary",Json.obj("usedPercent",25,"windowDurationMins",300,"resetsAt",1_800_000_000L))));
        check(codexQuota.available() && codexQuota.windows().getFirst().contains("5h: 25% used") &&
                codexQuota.windows().getFirst().contains("resets"),"Codex quota window");
        System.out.println("Smoke tests passed");
    }
}
