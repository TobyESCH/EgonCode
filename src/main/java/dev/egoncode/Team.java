package dev.egoncode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Two isolated coding teammates with explicit, reviewable integration. */
final class Team {
    private final Store store;
    private final ChatGptAuth auth;
    private final Path root;
    private final Tools.Approval approval;
    Team(Store store, ChatGptAuth auth, Path root, Tools.Approval approval) {
        this.store=store; this.auth=auth; this.root=root; this.approval=approval;
    }

    void run(String goal, String providerName, String model) throws Exception {
        if (model.isBlank()) throw new IllegalStateException("Choose /model first");
        Provider provider = Agent.provider(providerName,auth);
        Session planner = Session.create(root,providerName,model);
        provider.addUser(planner,"Break this coding goal into exactly two independent, bounded tasks for separate agents. " +
                "Return only JSON: {\"tasks\":[\"task one\",\"task two\"]}. Goal: " + goal);
        Provider.Turn plan = provider.respond(planner,"You are a software task coordinator. Keep tasks independent and concrete.",List.of(),text -> {});
        String raw = plan.text().trim();
        if (raw.startsWith("```")) raw = raw.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        List<Object> tasks = Json.list(Json.map(Json.parse(raw)).get("tasks"));
        if (tasks.size()!=2 || Json.str(tasks.get(0)).isBlank() || Json.str(tasks.get(1)).isBlank())
            throw new IllegalStateException("Coordinator did not return two tasks");
        String id = UUID.randomUUID().toString().substring(0,8);
        boolean git = gitAvailable();
        List<Map<String,Object>> members = new ArrayList<>();
        for (int i=0;i<2;i++) {
            Path worktree = git ? store.home().resolve("worktrees").resolve(id + "-" + (i+1)) : root;
            String branch = "egon/" + id + "-" + (i+1);
            if (git) {
                Files.createDirectories(worktree.getParent());
                exec(List.of("git","-C",root.toString(),"worktree","add","-b",branch,worktree.toString(),"HEAD"),null);
            }
            members.add(Json.obj("task",Json.str(tasks.get(i)),"worktree",worktree.toString(),"branch",git?branch:"",
                    "status","pending","log",store.home().resolve("teams").resolve(id+"-"+(i+1)+".log").toString()));
        }
        Map<String,Object> record = Json.obj("id",id,"goal",goal,"root",root.toString(),"git",git,"members",members);
        save(id,record);
        System.out.println("Team " + id + " started. " + (git?"Agents use separate Git worktrees.":"No Git repository; agents run sequentially."));
        if (git) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i=0;i<2;i++) {
                    final int index=i; futures.add(pool.submit(() -> runMember(id,index,members.get(index),providerName,model)));
                }
                for (Future<?> future : futures) future.get();
            } finally { pool.shutdown(); }
        } else {
            for (int i=0;i<2;i++) runMember(id,i,members.get(i),providerName,model);
        }
        save(id,record);
        status(id);
    }

    private void runMember(String id, int index, Map<String,Object> member, String providerName, String model) {
        Path worktree = Path.of(Json.get(member,"worktree"));
        Path log = Path.of(Json.get(member,"log"));
        synchronized(member) { member.put("status","running"); }
        try {
            Files.createDirectories(log.getParent());
            try (PrintStream out = new PrintStream(Files.newOutputStream(log),true,StandardCharsets.UTF_8)) {
                Tools tools = new Tools(worktree,description -> approval.allow("Agent " + (index+1) + ": " + description),store);
                Session session = Session.create(worktree,providerName,model);
                new Agent(store,auth,tools,out).run(session,Json.get(member,"task"));
                member.put("session",session.id);
            }
            member.put("status","done");
        } catch (Exception e) {
            member.put("status","failed"); member.put("error",e.getMessage());
        }
    }

    void status(String id) throws Exception {
        Map<String,Object> record = store.read("teams/" + id + ".json");
        if (record.isEmpty() || !root.toString().equals(Json.get(record,"root"))) throw new IOException("Team not found in this project");
        System.out.println("Team " + id + ": " + Json.get(record,"goal"));
        int index=0;
        for (Object item : Json.list(record.get("members"))) {
            index++; Map<String,Object> member = Json.map(item);
            System.out.println(index + ". " + Json.get(member,"status") + " — " + Json.get(member,"task"));
            System.out.println("   log: " + Json.get(member,"log"));
            if (!Json.get(member,"branch").isBlank()) System.out.println("   worktree: " + Json.get(member,"worktree"));
            if (member.containsKey("error")) System.out.println("   error: " + member.get("error"));
        }
    }

    void apply(String id, int index) throws Exception {
        Map<String,Object> record = store.read("teams/" + id + ".json");
        if (record.isEmpty() || !root.toString().equals(Json.get(record,"root"))) throw new IOException("Team not found in this project");
        if (!Boolean.TRUE.equals(record.get("git"))) throw new IllegalStateException("This team worked directly in the project");
        List<Object> members=Json.list(record.get("members"));
        if (index<1 || index>members.size()) throw new IllegalArgumentException("Agent number must be 1 or 2");
        Map<String,Object> member=Json.map(members.get(index-1));
        if (!Json.get(member,"status").equals("done")) throw new IllegalStateException("Agent has not completed");
        Path worktree = Path.of(Json.get(member,"worktree"));
        exec(List.of("git","-C",worktree.toString(),"add","-N","."),null);
        byte[] patch = exec(List.of("git","-C",worktree.toString(),"diff","--binary","HEAD"),null);
        if (patch.length==0) { System.out.println("Agent made no file changes"); return; }
        System.out.println(new String(patch,StandardCharsets.UTF_8));
        exec(List.of("git","-C",root.toString(),"apply","--check","-"),patch);
        if (!approval.allow("Apply agent " + index + " changes to " + root + "?")) { System.out.println("Skipped"); return; }
        exec(List.of("git","-C",root.toString(),"apply","-"),patch);
        member.put("integrated",true); save(id,record);
        System.out.println("Applied changes. Worktree retained for review: " + worktree);
    }

    private boolean gitAvailable() {
        try { exec(List.of("git","-C",root.toString(),"rev-parse","--is-inside-work-tree"),null); return true; }
        catch (Exception e) { return false; }
    }
    private void save(String id, Object record) throws IOException { store.write("teams/" + id + ".json",record,false); }
    private static byte[] exec(List<String> command, byte[] input) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (input != null) { try (OutputStream out=process.getOutputStream()) { out.write(input); } }
        else process.getOutputStream().close();
        byte[] result = process.getInputStream().readAllBytes();
        if (!process.waitFor(60,TimeUnit.SECONDS) || process.exitValue()!=0)
            throw new IOException(String.join(" ",command) + " failed: " + new String(result,StandardCharsets.UTF_8));
        return result;
    }
}
