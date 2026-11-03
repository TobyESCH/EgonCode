package dev.egoncode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only Codex app-server quota view from the user's existing Codex sign-in. */
final class CodexUsage {
    record Snapshot(List<String> windows,String message) {
        boolean available() { return !windows.isEmpty(); }
    }
    private static final long REFRESH_MS=300_000;
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("MMM d HH:mm z");
    private volatile Snapshot snapshot=new Snapshot(List.of(),"Codex usage has not loaded yet.");
    private volatile String accountEmail="";
    private volatile long lastAttempt;
    private final AtomicBoolean refreshing=new AtomicBoolean();

    Snapshot snapshot() { return snapshot; }

    void refreshAsync(String expectedEmail) {
        if (expectedEmail==null || expectedEmail.isBlank()) return;
        if (!expectedEmail.equalsIgnoreCase(accountEmail)) {
            accountEmail=expectedEmail; lastAttempt=0;
            snapshot=new Snapshot(List.of(),"Checking Codex account usage.");
        }
        long now=System.currentTimeMillis();
        if (now-lastAttempt<REFRESH_MS || !refreshing.compareAndSet(false,true)) return;
        lastAttempt=now;
        Thread worker=new Thread(() -> {
            try {
                Snapshot fetched=fetch(expectedEmail);
                if (expectedEmail.equalsIgnoreCase(accountEmail)) snapshot=fetched;
            } finally { refreshing.set(false); }
        },"egoncode-codex-usage");
        worker.setDaemon(true); worker.start();
    }

    Snapshot refreshNow(String expectedEmail) {
        if (expectedEmail==null || expectedEmail.isBlank())
            return new Snapshot(List.of(),"Connect a ChatGPT account first.");
        accountEmail=expectedEmail; lastAttempt=System.currentTimeMillis();
        Snapshot fetched=fetch(expectedEmail);
        snapshot=fetched;
        return fetched;
    }

    private static Snapshot fetch(String expectedEmail) {
        Process process=null;
        try {
            ProcessBuilder builder=new ProcessBuilder("codex","app-server","--stdio")
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().remove("OPENAI_API_KEY");
            builder.environment().remove("ANTHROPIC_API_KEY");
            process=builder.start();
            Process running=process;
            BlockingQueue<Map<String,Object>> replies=new LinkedBlockingQueue<>();
            Thread reader=new Thread(() -> {
                try (BufferedReader input=new BufferedReader(new InputStreamReader(
                        running.getInputStream(),StandardCharsets.UTF_8))) {
                    String line;
                    while ((line=input.readLine())!=null)
                        try { replies.offer(Json.map(Json.parse(line))); } catch (Exception ignored) {}
                } catch (IOException ignored) {}
            },"egoncode-codex-usage-reader");
            reader.setDaemon(true); reader.start();
            try (BufferedWriter output=new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(),StandardCharsets.UTF_8))) {
                send(output,Json.obj("jsonrpc","2.0","id",1,"method","initialize","params",
                        Json.obj("clientInfo",Json.obj("name","egoncode_usage","title","EgonCode usage","version","0.1.0"))));
                if (!reply(replies,1).containsKey("result"))
                    return unavailable("Codex app-server could not initialize.");
                send(output,Json.obj("jsonrpc","2.0","method","initialized","params",Json.obj()));
                send(output,Json.obj("jsonrpc","2.0","id",2,"method","account/read","params",
                        Json.obj("refreshToken",false)));
                Map<String,Object> account=Json.map(Json.map(reply(replies,2).get("result")).get("account"));
                if (account.isEmpty()) return unavailable("Sign in to Codex to show its quota.");
                if (!expectedEmail.equalsIgnoreCase(Json.get(account,"email")))
                    return unavailable("Codex is signed in with a different ChatGPT account.");
                send(output,Json.obj("jsonrpc","2.0","id",3,"method","account/rateLimits/read"));
                Map<String,Object> response=reply(replies,3);
                if (!response.containsKey("result"))
                    return unavailable("Codex did not return quota data.");
                return parse(Json.map(response.get("result")));
            }
        } catch (Exception e) {
            return unavailable("Codex quota is unavailable. Check that Codex is installed and signed in.");
        } finally {
            if (process!=null) {
                process.destroy();
                try {
                    if (!process.waitFor(1,TimeUnit.SECONDS)) process.destroyForcibly();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); process.destroyForcibly();
                }
            }
        }
    }

    static Snapshot parse(Map<String,Object> response) {
        Map<String,Object> buckets=Json.map(response.get("rateLimitsByLimitId"));
        Map<String,Object> codex=Json.map(buckets.get("codex"));
        if (codex.isEmpty()) codex=Json.map(response.get("rateLimits"));
        List<String> windows=new ArrayList<>();
        addWindow(windows,Json.map(codex.get("primary")));
        addWindow(windows,Json.map(codex.get("secondary")));
        return windows.isEmpty()?unavailable("No Codex quota window was returned."):
                new Snapshot(List.copyOf(windows),"Codex quota from a sign-in with matching email.");
    }

    private static void addWindow(List<String> lines,Map<String,Object> window) {
        Object raw=window.get("usedPercent");
        if (!(raw instanceof Number used)) return;
        long mins=Json.number(window.get("windowDurationMins"),0);
        String period=mins>0 && mins%1440==0?mins/1440+"d":
                mins>=60 && mins%60==0?mins/60+"h":mins>0?mins+"m":"Window";
        String line=period+": "+String.format(Locale.ROOT,"%.0f",used.doubleValue())+"% used";
        long reset=Json.number(window.get("resetsAt"),0);
        if (reset>0) line+=", resets "+DATE.format(Instant.ofEpochSecond(reset).atZone(ZoneId.systemDefault()));
        lines.add(line);
    }

    private static Snapshot unavailable(String message) { return new Snapshot(List.of(),message); }
    private static void send(BufferedWriter output,Map<String,Object> request) throws IOException {
        output.write(Json.write(request)); output.newLine(); output.flush();
    }
    private static Map<String,Object> reply(BlockingQueue<Map<String,Object>> replies,int id) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime()<deadline) {
            Map<String,Object> next=replies.poll(500,TimeUnit.MILLISECONDS);
            if (next!=null && Json.number(next.get("id"),-1)==id) return next;
        }
        return Map.of();
    }
}
