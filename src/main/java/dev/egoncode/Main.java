package dev.egoncode;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Main {
    private final Store store = new Store();
    private final ChatGptAuth auth = new ChatGptAuth(store);
    private final ModelCatalog catalog = new ModelCatalog(auth);
    private final Scanner input = new Scanner(System.in);
    private final Path root;
    private final Tools tools;
    private final Agent agent;
    private final Team team;
    private final Skills skills;
    private final TerminalUi ui;
    private final CodexUsage codexUsage=new CodexUsage();
    private volatile String chatgptEmail="";
    private volatile Session session;
    private final List<Session> tabs=new CopyOnWriteArrayList<>();
    private volatile int activeTab;
    private boolean modelChecked;

    private Main(Path root, TerminalUi ui) throws Exception {
        this.ui=ui;
        this.root = root.toRealPath();
        Tools.Approval approval = description -> {
            if (ui!=null) return ui.approve(description);
            synchronized(input) {
                System.out.print("[approval] " + description + " [y/N] ");
                return input.hasNextLine() && input.nextLine().trim().equalsIgnoreCase("y");
            }
        };
        tools = new Tools(this.root, approval,store);
        agent = new Agent(store,auth,tools);
        team = new Team(store,auth,this.root,approval);
        skills = new Skills(this.root,store);
        Map<String,Object> config = store.read("config.json");
        session = Session.create(this.root,Json.get(config,"provider").isBlank()?"chatgpt":Json.get(config,"provider"),Json.get(config,"model"));
        refreshChatgptEmail();
        tabs.add(session);
        if (ui!=null) ui.activate(session.id);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("."); String prompt = null; boolean plain=false;
        for (int i=0; i<args.length; i++) {
            if (args[i].equals("--project") && i+1<args.length) root = Path.of(args[++i]);
            else if (args[i].equals("--prompt") && i+1<args.length) prompt = args[++i];
            else if (args[i].equals("--plain")) plain=true;
            else if (args[i].equals("--help")) { help(); return; }
            else throw new IllegalArgumentException("Unknown argument: " + args[i]);
        }
        TerminalUi ui=prompt==null && !plain?TerminalUi.start():null;
        if (ui!=null) {
            try (ui) {
                Main app=new Main(root,ui);
                Thread worker=new Thread(app::loop,"egoncode-agent-ui");
                ui.runUntil(worker,app::status);
            }
            return;
        }
        Main app = new Main(root,null);
        if (prompt != null) { app.run(prompt); return; }
        app.loop();
    }
    private void loop() {
        if (ui==null) {
            System.out.println("EgonCode | " + root + " | " + session.provider + "/" + session.model);
            help();
        }
        while (true) {
            if (ui==null) System.out.print("\negon> ");
            if (!input.hasNextLine()) return;
            String line = input.nextLine().trim();
            if (line.isBlank()) continue;
            if (line.equals("/quit") || line.equals("/exit")) return;
            try { if (line.startsWith("/")) command(line); else run(line); }
            catch (Exception e) { System.err.println("Error: " + e.getMessage()); }
        }
    }
    private void run(String prompt) throws Exception {
        if (ui!=null) System.out.println("\n[you] " + prompt);
        if (session.provider.equals("chatgpt") && !modelChecked) {
            try { ensureSelectableModel(); modelChecked=true; }
            catch (Exception e) { throw new IllegalStateException("Cannot verify ChatGPT models. Use /login or /model. " + e.getMessage(),e); }
        } else if (session.model.isBlank()) {
            try { chooseRecommended(); }
            catch (Exception e) { throw new IllegalStateException("No model selected. Use /model to choose one. " + e.getMessage(),e); }
        }
        if (ui!=null) ui.working(true);
        try { agent.run(session,prompt); }
        finally { if (ui!=null) ui.working(false); }
    }
    private void command(String line) throws Exception {
        String[] parts = line.split("\\s+",2);
        String command = parts[0]; String value = parts.length>1?parts[1].trim():"";
        switch (command) {
            case "/help" -> help();
            case "/login" -> {
                if (ui!=null) ui.working(true);
                try {
                    auth.login(value.equals("new"));
                    refreshChatgptEmail();
                    if (session.provider.equals("chatgpt")) { ensureSelectableModel(); modelChecked=true; }
                } finally { if (ui!=null) ui.working(false); }
            }
            case "/accounts" -> auth.accounts();
            case "/account" -> { auth.select(value); refreshChatgptEmail(); modelChecked=false; }
            case "/logout" -> { auth.logout(); refreshChatgptEmail(); }
            case "/models" -> showModels();
            case "/open" -> {
                if (value.isBlank()) {
                    if (ui==null) throw new IllegalArgumentException("Usage: /open <https-url>");
                    ui.openLatestLink();
                } else if (Browser.open(value)) System.out.println("Opening link in browser: " + value);
                else System.err.println("Could not open link: " + value);
            }
            case "/provider" -> {
                if (!List.of("chatgpt","openai","anthropic","local").contains(value)) throw new IllegalArgumentException("Use chatgpt, openai, anthropic, or local");
                replaceSession(Session.create(root,value,"")); saveConfig(); System.out.println("Provider: " + value);
                modelChecked=false;
                try { chooseRecommended(); } catch (Exception e) { System.out.println("Use /model to select a model: " + e.getMessage()); }
            }
            case "/model" -> {
                List<ModelCatalog.Model> models;
                String listError="";
                try { models=catalog.list(session.provider); }
                catch (Exception e) { models=List.of(); listError=e.getMessage(); }
                if (value.isBlank()) {
                    if (ui!=null) {
                        ModelCatalog.Model recommended=catalog.recommended(session.provider,models);
                        value=ui.pickModel(models,session.model,recommended==null?"":recommended.id(),listError,
                                session.provider.equals("chatgpt"));
                        if (value==null) break;
                        if (value.equals("/login")) { command("/login"); command("/model"); break; }
                    } else {
                        if (models.isEmpty()) { System.out.println("No models available: " + listError); break; }
                        showModels(models);
                        System.out.print("Choose a number or press Enter for the recommended model: ");
                        value=input.hasNextLine()?input.nextLine().trim():"";
                        if (value.isBlank()) value=catalog.recommended(session.provider,models).id();
                    }
                }
                if (value.matches("[0-9]+") && !models.isEmpty()) {
                    int index=Integer.parseInt(value)-1;
                    if (index<0 || index>=models.size()) throw new IllegalArgumentException("Model number out of range");
                    value=models.get(index).id();
                }
                replaceSession(Session.create(root,session.provider,value)); saveConfig(); System.out.println("New session " + session.id + " using " + value);
                modelChecked=true;
            }
            case "/mode" -> {
                if (value.isBlank()) { System.out.println("Mode: " + tools.mode().name().toLowerCase(Locale.ROOT) +
                        "  |  /mode plan, /mode edit, /mode auto"); break; }
                try { tools.mode(Tools.Mode.valueOf(value.toUpperCase(Locale.ROOT))); }
                catch (IllegalArgumentException e) { throw new IllegalArgumentException("Use /mode plan, /mode edit, or /mode auto"); }
                System.out.println("Mode: " + value + (tools.autoMode()?" (project edits and common checks run automatically)":""));
            }
            case "/mode-next" -> command("/mode " + nextMode(tools.mode()).name().toLowerCase(Locale.ROOT));
            case "/diff" -> System.out.println("[git] Working tree diff\n" + tools.gitDiff());
            case "/status" -> System.out.println("Session " + session.id + " | " + session.provider + "/" + session.model + " | " +
                    tools.mode().name().toLowerCase(Locale.ROOT) + " | tab " + (activeTab+1) + "/" + tabs.size());
            case "/usage" -> {
                long in=0,out=0;
                for (Map<String,Object> event : session.events) if (Json.get(event,"type").equals("model")) {
                    Map<String,Object> detail=Json.map(event.get("detail"));
                    in+=Json.number(detail.get("input_tokens"),0); out+=Json.number(detail.get("output_tokens"),0);
                }
                System.out.println("Session tokens: " + in + " input, " + out + " output");
                if (session.provider.equals("chatgpt")) {
                    CodexUsage.Snapshot quota=codexUsage.refreshNow(chatgptEmail);
                    if (quota.available()) {
                        System.out.println("Codex quota for a sign-in with the same email:");
                        for (String window:quota.windows()) System.out.println("  " + window);
                        System.out.println("This is the Codex quota bucket; EgonCode's app or workspace limit may differ.");
                    } else System.out.println(quota.message());
                    System.out.println("Full ChatGPT plan and app usage: https://chatgpt.com/settings/usage");
                } else {
                    List<String> limits=RateLimits.lines(RateLimits.latest(session));
                    if (limits.isEmpty()) System.out.println("API rate limits: no response headers recorded yet. Send a message first.");
                    else {
                        System.out.println("Latest API rate limits:");
                        for (String limit:limits) System.out.println("  " + limit);
                    }
                    if (session.provider.equals("anthropic"))
                        System.out.println("Claude subscription usage: open Claude Settings > Usage. EgonCode's Anthropic connection uses an API key.");
                    else if (session.provider.equals("openai"))
                        System.out.println("These API rate limits are separate from ChatGPT plan usage.");
                }
            }
            case "/timeline" -> {
                for (Map<String,Object> event : session.events) {
                    String detail=Json.write(event.get("detail"));
                    System.out.println(Json.get(event,"at") + " " + Json.get(event,"type") + " " +
                            (detail.length()>200?detail.substring(0,200)+"…":detail));
                }
            }
            case "/sessions" -> sessions();
            case "/resume" -> {
                Session loaded=Session.load(store,value,root);
                int existing=-1;
                for (int i=0;i<tabs.size();i++) if (tabs.get(i).id.equals(loaded.id)) { existing=i; break; }
                if (existing>=0) switchTab(existing); else addTab(loaded);
                System.out.println("Resumed " + session.id);
            }
            case "/new" -> addTab(Session.create(root,session.provider,session.model));
            case "/tab-next" -> switchTab((activeTab+1)%tabs.size());
            case "/tab-prev" -> switchTab((activeTab-1+tabs.size())%tabs.size());
            case "/tab-close" -> closeTab();
            case "/compact" -> agent.compact(session);
            case "/skills" -> System.out.println(skills.names());
            case "/team" -> {
                if (value.isBlank()) throw new IllegalArgumentException("Usage: /team <goal>");
                if (ui!=null) ui.working(true);
                try { team.run(value,session.provider,session.model); }
                finally { if (ui!=null) ui.working(false); }
            }
            case "/team-status" -> team.status(value);
            case "/team-apply" -> {
                String[] args=value.split("\\s+");
                if (args.length!=2) throw new IllegalArgumentException("Usage: /team-apply <team-id> <1|2>");
                team.apply(args[0],Integer.parseInt(args[1]));
            }
            default -> {
                String name=command.substring(1);
                String custom=skills.command(name,value);
                if (custom==null) throw new IllegalArgumentException("Unknown command. Use /help.");
                run(custom);
            }
        }
    }
    private void saveConfig() throws IOException { store.write("config.json",Json.obj("provider",session.provider,"model",session.model),false); }
    private void refreshChatgptEmail() {
        try {
            Map<String,Object> all=store.read("auth.json");
            chatgptEmail=Json.get(Json.map(Json.map(all.get("accounts")).get(Json.get(all,"active"))),"email");
        } catch (IOException e) { chatgptEmail=""; }
    }
    static Tools.Mode nextMode(Tools.Mode current) {
        return switch (current) {
            case EDIT -> Tools.Mode.AUTO;
            case AUTO -> Tools.Mode.PLAN;
            case PLAN -> Tools.Mode.EDIT;
        };
    }
    private void replaceSession(Session next) {
        tabs.set(activeTab,next);
        session=next;
        if (ui!=null) ui.activate(next.id);
    }
    private void addTab(Session next) {
        tabs.add(next); activeTab=tabs.size()-1; session=next; modelChecked=false;
        if (ui!=null) ui.activate(next.id);
    }
    private void switchTab(int index) {
        activeTab=index; session=tabs.get(index); modelChecked=false;
        if (ui!=null) ui.activate(session.id);
    }
    private void closeTab() {
        Session closed=tabs.remove(activeTab);
        if (tabs.isEmpty()) tabs.add(Session.create(root,closed.provider,closed.model));
        activeTab=Math.min(activeTab,tabs.size()-1);
        session=tabs.get(activeTab); modelChecked=false;
        if (ui!=null) { ui.drop(closed.id); ui.activate(session.id); }
    }
    private TerminalUi.Status status() {
        if (session.provider.equals("chatgpt")) codexUsage.refreshAsync(chatgptEmail);
        long in=0,out=0;
        for (Map<String,Object> event : session.events) if (Json.get(event,"type").equals("model")) {
            Map<String,Object> detail=Json.map(event.get("detail"));
            in+=Json.number(detail.get("input_tokens"),0); out+=Json.number(detail.get("output_tokens"),0);
        }
        List<TerminalUi.Tab> labels=new ArrayList<>();
        for (Session tab:tabs) {
            String title="New chat";
            for (Map<String,Object> event:tab.events) if (Json.get(event,"type").equals("prompt")) {
                title=String.valueOf(event.get("detail")).replace('\n',' ').trim(); break;
            }
            labels.add(new TerminalUi.Tab(tab.id,title));
        }
        return new TerminalUi.Status(root.toString(),session.provider,session.model,tools.mode().name().toLowerCase(Locale.ROOT),
                session.id,in,out,RateLimits.lines(RateLimits.latest(session)),codexUsage.snapshot().windows(),
                labels,activeTab,session.events.isEmpty());
    }
    private void chooseRecommended() throws Exception {
        List<ModelCatalog.Model> models=catalog.list(session.provider);
        ModelCatalog.Model best=catalog.recommended(session.provider,models);
        if (best==null) throw new IllegalStateException("Provider returned no selectable models");
        replaceSession(Session.create(root,session.provider,best.id())); saveConfig();
        System.out.println("Recommended model selected: " + best.name() + " (" + best.id() + "). Use /model to change it.");
    }
    private void ensureSelectableModel() throws Exception {
        List<ModelCatalog.Model> models=catalog.list(session.provider);
        if (models.stream().anyMatch(model -> model.id().equals(session.model))) return;
        ModelCatalog.Model best=catalog.recommended(session.provider,models);
        if (best==null) throw new IllegalStateException("Provider returned no selectable models");
        replaceSession(Session.create(root,session.provider,best.id())); saveConfig();
        System.out.println("Recommended model selected: " + best.name() + " (" + best.id() + "). Use /model to change it.");
    }
    private void showModels() throws Exception { showModels(catalog.list(session.provider)); }
    private void showModels(List<ModelCatalog.Model> models) {
        ModelCatalog.Model recommended=catalog.recommended(session.provider,models);
        System.out.println("Models for " + session.provider + ":");
        for (int i=0;i<models.size();i++) {
            ModelCatalog.Model model=models.get(i);
            System.out.println(String.format("%2d. %-30s %s%s%s",i+1,model.id(),model.name(),
                    model.id().equals(session.model)?"  [selected]":"",
                    recommended!=null && model.id().equals(recommended.id())?"  [recommended]":""));
        }
    }
    private void sessions() throws IOException {
        Path dir = store.home().resolve("sessions"); if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir,"*.json")) {
            for (Path file : entries) {
                Map<String,Object> data = Json.map(Json.parse(Files.readString(file)));
                if (root.toString().equals(Json.get(data,"project"))) System.out.println(Json.get(data,"id") + " " + Json.get(data,"provider") + "/" + Json.get(data,"model"));
            }
        }
    }
    private static void help() {
        System.out.println("Commands: /login [new], /logout, /accounts, /account <id>, /models, /open [url], /provider <chatgpt|openai|anthropic|local>, /model <id>, " +
                "/mode <plan|edit|auto>, /diff, /status, /usage, /timeline, /sessions, /resume <id>, /new, /tab-next, /tab-prev, /tab-close, /compact, /team <goal>, " +
                "/team-status <id>, /team-apply <id> <1|2>, /skills, /quit");
        System.out.println("Type a coding request to start. API keys come from OPENAI_API_KEY or ANTHROPIC_API_KEY; " +
                "local endpoints use EGON_LOCAL_URL and optional EGON_LOCAL_API_KEY.");
        System.out.println("ChatGPT sign-in documentation: https://developers.openai.com/siwc/token-sharing-open-source");
    }
}
