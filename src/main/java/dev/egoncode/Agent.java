package dev.egoncode;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

final class Agent {
    private final Store store;
    private final ChatGptAuth auth;
    private final Tools tools;
    private final java.io.PrintStream out;
    Agent(Store store, ChatGptAuth auth, Tools tools) { this(store,auth,tools,System.out); }
    Agent(Store store, ChatGptAuth auth, Tools tools, java.io.PrintStream out) {
        this.store = store; this.auth = auth; this.tools = tools; this.out = out;
    }

    void run(Session session, String prompt) throws Exception {
        Provider provider = provider(session.provider,auth);
        provider.addUser(session,prompt); session.event("prompt",prompt); session.save(store);
        for (int step=0; step<40; step++) {
            if (Json.write(session.history).length()>200_000) compact(session);
            StringBuilder streamed=new StringBuilder();
            Provider.Turn turn = provider.respond(session,instructions(),tools.definitions(),delta -> {
                if (streamed.isEmpty()) out.print("\n[assistant] ");
                streamed.append(delta); out.print(delta);
            });
            if (streamed.isEmpty() && !turn.text().isBlank()) out.print("\n[assistant] " + turn.text());
            if (!streamed.isEmpty() || !turn.text().isBlank()) out.println();
            session.event("model",Json.obj("provider",session.provider,"model",session.model,
                    "input_tokens",turn.inputTokens(),"output_tokens",turn.outputTokens()));
            session.save(store);
            if (turn.calls().isEmpty()) {
                if (turn.text().isBlank() && streamed.isEmpty())
                    throw new IllegalStateException("Model returned no assistant text or tool calls. Try again or choose another model.");
                return;
            }
            for (Provider.Call call : turn.calls()) {
                out.println("\n[tool] " + toolLabel(call));
                String result = tools.execute(call.name(),call.arguments(),session);
                boolean edit=call.name().endsWith("write_file") || call.name().endsWith("replace_in_file");
                out.println("[result] " + (edit?cut(result,12_000):brief(result)));
                provider.addToolResult(session,call,result);
                session.event("tool",Json.obj("name",call.name(),"result",result));
                session.save(store);
            }
        }
        out.println("Turn stopped after 40 model steps. You can continue with a new prompt.");
    }

    void compact(Session session) throws Exception {
        if (session.history.isEmpty()) return;
        Provider summarizer = provider(session.provider,auth);
        Session temporary = Session.create(tools.root(),session.provider,session.model);
        String history = Json.write(session.history);
        summarizer.addUser(temporary,"Summarize this coding session so work can continue. Include the user's goal, " +
                "decisions, changed files, tests, failures, and pending next steps. Do not invent results.\n\n" +
                history.substring(Math.max(0,history.length()-200_000)));
        Provider.Turn summary = summarizer.respond(temporary,"Return a concise but complete continuation summary.",List.of(),text -> {});
        if (summary.text().isBlank()) throw new IllegalStateException("Compaction produced no summary");
        session.history.clear();
        summarizer.addUser(session,"Previous session summary:\n" + summary.text());
        session.event("compaction",Json.obj("old_chars",history.length(),"summary_chars",summary.text().length()));
        session.save(store);
        out.println("[context compacted]");
    }

    static Provider provider(String name, ChatGptAuth auth) throws Exception {
        return switch (name) {
            case "chatgpt" -> new OpenAiProvider("https://api.openai.com/v1",Json.get(auth.active(),"access_token"),true);
            case "openai" -> new OpenAiProvider("https://api.openai.com/v1",required("OPENAI_API_KEY"),false);
            case "anthropic" -> new AnthropicProvider(required("ANTHROPIC_API_KEY"));
            case "local" -> new OpenAiProvider(required("EGON_LOCAL_URL"),System.getenv().getOrDefault("EGON_LOCAL_API_KEY",""),false);
            default -> throw new IllegalArgumentException("Unknown provider: " + name);
        };
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + key + " before using this provider");
        return value;
    }
    private String instructions() throws IOException {
        StringBuilder text = new StringBuilder("You are EgonCode, a coding agent. Work inside the project. " +
                "Use tools to inspect before changing files. Report what changed and how it was checked. " +
                "Never claim an edit or test succeeded unless a tool result confirms it. " +
                "Do not seek secrets or credentials. Current mode: " +
                (tools.planMode()?"plan, no writes or commands":tools.autoMode()?
                        "auto, project edits and common checks run without prompts":"edit, ask before writes and commands") +
                ". Project root: " + tools.root() + ".\n");
        String skillNames = new Skills(tools.root(),store).names();
        if (!skillNames.isBlank()) text.append("Available skills: ").append(skillNames).append(". Use load_skill when relevant.\n");
        for (String filename : List.of("AGENTS.md","EGON.md")) {
            Path file = tools.root().resolve(filename);
            if (Files.isRegularFile(file) && Files.size(file) <= 25_000)
                text.append("\nProject instructions from ").append(filename).append(":\n").append(Files.readString(file));
        }
        return text.toString();
    }
    private String brief(Object value) {
        String content = value instanceof String ? (String)value : Json.write(value);
        return cut(content,700);
    }
    private String cut(String content,int limit) {
        return content.length()<=limit?content:content.substring(0,limit)+"\n[output truncated]";
    }
    private String toolLabel(Provider.Call call) {
        String name=call.name();
        if (name.contains(".")) name=name.substring(name.lastIndexOf('.')+1);
        String argument=call.arguments().get("path") instanceof String path?path:
                call.arguments().get("command") instanceof String command?command:"";
        return name + (argument.isBlank()?"":"  " + cut(argument.replace('\n',' '),120));
    }
}
