package dev.egoncode;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dependency-free alternate-screen dashboard for Unix terminals. */
final class TerminalUi implements AutoCloseable {
    record Tab(String id,String title) {}
    record Status(String project, String provider, String model, String mode, String session,
                  long inputTokens, long outputTokens, List<String> rateLimits,
                  List<String> codexWindows,
                  List<Tab> tabs, int activeTab, boolean newChat) {}
    private final InputStream terminalInput;
    private final PrintStream terminalOutput;
    private final PrintStream terminalError;
    private final PipedOutputStream appInputWriter;
    private final Map<String,ByteArrayOutputStream> captured=new HashMap<>();
    private final Map<String,StringBuilder> drafts=new HashMap<>();
    private final Map<String,Boolean> splash=new ConcurrentHashMap<>();
    private volatile String activeLog="startup";
    private volatile StringBuilder typed=new StringBuilder();
    private final BlockingQueue<Integer> keys=new LinkedBlockingQueue<>();
    private final String oldStty;
    private volatile boolean working;
    private volatile long lastInput=System.currentTimeMillis();
    private final AtomicBoolean closed=new AtomicBoolean();
    private volatile int scroll;
    private volatile String lastScreen="";
    private volatile String latestLink="";
    private static final Pattern URL=Pattern.compile("https?://[^\\s\\u001B]+",Pattern.CASE_INSENSITIVE);
    private int columns=100, rows=30;
    private volatile Picker picker;
    private volatile ApprovalDialog approval;
    private volatile boolean awaitingTabChange;
    private String escape="";
    private long escapeAt;
    private final ByteArrayOutputStream utf8=new ByteArrayOutputStream();
    private int utf8Remaining;
    private static final long SLEEP_AFTER_MS=90_000;
    private static final String BG="\033[48;2;16;20;28m";
    private static final String FG="\033[38;2;225;230;240m";
    private static final String USER_BG="\033[48;2;27;54;76m";
    private static final String USER_FG="\033[38;2;226;242;255m";
    private static final String ASSISTANT_BG="\033[48;2;39;43;58m";
    private static final String ASSISTANT_FG="\033[38;2;246;240;247m";
    private static final String TOOL_BG="\033[48;2;25;48;43m";
    private static final String TOOL_FG="\033[38;2;184;231;211m";
    private static final String ERROR_BG="\033[48;2;69;37;48m";
    private static final String ERROR_FG="\033[38;2;255;218;225m";
    private static final String ADD_BG="\033[48;2;23;55;41m";
    private static final String REMOVE_BG="\033[48;2;68;32;39m";
    private static final String HUNK_BG="\033[48;2;47;43;71m";
    private static final String FILE_BG="\033[48;2;26;54;69m";
    private enum Kind { SYSTEM, USER, ASSISTANT, TOOL, ERROR, SELECTED, ADDED, REMOVED, HUNK, FILE, CONTEXT, AUTO }
    private record ChatLine(String text,Kind kind) {}
    private static final class Picker {
        final List<ModelCatalog.Model> models;
        final CompletableFuture<String> choice=new CompletableFuture<>();
        final String recommended;
        final String error;
        final boolean canLogin;
        int selected;
        Picker(List<ModelCatalog.Model> models,String current,String recommended,String error,boolean canLogin) {
            this.models=List.copyOf(models); this.recommended=recommended; this.error=error; this.canLogin=canLogin;
            for (int i=0;i<models.size();i++) if (models.get(i).id().equals(current)) selected=i;
            if (current.isBlank()) for (int i=0;i<models.size();i++) if (models.get(i).id().equals(recommended)) selected=i;
        }
    }
    private static final class ApprovalDialog {
        final String description;
        final CompletableFuture<Boolean> choice=new CompletableFuture<>();
        int selected=1;
        int scroll;
        ApprovalDialog(String description) { this.description=description; }
    }

    static TerminalUi start() throws Exception {
        if (System.console()==null) throw new IOException("Interactive EgonCode needs a terminal. Use --prompt for a one-shot request.");
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))
            throw new IOException("The terminal dashboard currently needs a Unix terminal.");
        return new TerminalUi();
    }
    private TerminalUi() throws Exception {
        terminalInput=System.in; terminalOutput=System.out; terminalError=System.err;
        oldStty=stty("-g").trim();
        if (oldStty.isBlank()) throw new IOException("No terminal mode");
        try {
            String size=stty("size").trim();
            String[] parts=size.split("\\s+");
            if (parts.length==2) { rows=Integer.parseInt(parts[0]); columns=Integer.parseInt(parts[1]); }
        } catch (Exception ignored) {}
        if (columns>0 && columns<70) throw new IOException("Terminal is too narrow for dashboard");
        stty("raw","-echo");
        PipedInputStream appInput=new PipedInputStream(16_384);
        appInputWriter=new PipedOutputStream(appInput);
        PrintStream capture=new PrintStream(new OutputStream() {
            @Override public void write(int b) { synchronized(captured) { log().write(b); trim(log()); } }
            @Override public void write(byte[] b,int off,int len) { synchronized(captured) { log().write(b,off,len); trim(log()); } }
            private ByteArrayOutputStream log() { return captured.computeIfAbsent(activeLog,k -> new ByteArrayOutputStream()); }
            private void trim(ByteArrayOutputStream log) {
                if (log.size()>200_000) {
                    byte[] bytes=log.toByteArray(); log.reset(); log.write(bytes,bytes.length-120_000,120_000);
                }
            }
        },true,StandardCharsets.UTF_8);
        System.setIn(appInput); System.setOut(capture); System.setErr(capture);
        terminalOutput.print("\033[?1049h\033[?25h\033[2J"); terminalOutput.flush();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { restore(); } catch (Exception ignored) {} }));
    }
    void working(boolean state) {
        if (working && !state) lastInput=System.currentTimeMillis();
        working=state;
    }
    void activate(String id) {
        synchronized(captured) {
            captured.computeIfAbsent(id,k -> new ByteArrayOutputStream());
            activeLog=id;
        }
        synchronized(drafts) { typed=drafts.computeIfAbsent(id,k -> new StringBuilder()); }
        splash.putIfAbsent(id,true);
        scroll=0; lastScreen=""; awaitingTabChange=false;
    }
    void drop(String id) {
        synchronized(captured) { captured.remove(id); }
        synchronized(drafts) { drafts.remove(id); }
        splash.remove(id);
    }
    String pickModel(List<ModelCatalog.Model> models,String current,String recommended,String error,boolean canLogin) throws Exception {
        Picker next=new Picker(models,current,recommended,error,canLogin);
        picker=next;
        return next.choice.get();
    }
    boolean approve(String description) {
        ApprovalDialog next=new ApprovalDialog(description);
        approval=next; lastScreen="";
        try { return next.choice.get(); }
        catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        } finally { approval=null; lastScreen=""; }
    }

    void runUntil(Thread app, Supplier<Status> status) throws Exception {
        Thread reader=new Thread(() -> {
            try {
                int key;
                while ((key=terminalInput.read())>=0) keys.offer(key);
            } catch (IOException ignored) {}
        },"egoncode-terminal-input");
        reader.setDaemon(true); reader.start();
        app.start();
        long lastSizeCheck=0;
        while (app.isAlive()) {
            Integer key=null;
            if (awaitingTabChange) Thread.sleep(30);
            else key=keys.poll(80,TimeUnit.MILLISECONDS);
            if (key!=null) {
                handleKey(key);
                while (!awaitingTabChange && (key=keys.poll())!=null) handleKey(key);
            }
            if (escapeAt!=0 && System.currentTimeMillis()-escapeAt>70) {
                escapeAt=0; escape=""; closePicker(); finishApproval(false);
            }
            long now=System.currentTimeMillis();
            if (now-lastSizeCheck>1_000) { updateSize(); lastSizeCheck=now; }
            draw(status.get(),now/150);
        }
        app.join();
    }
    private void submit(String line) throws IOException {
        if (!line.equals("/new") && !line.startsWith("/tab-") &&
                !line.equals("/mode-next") && !line.equals("/mode") && !line.startsWith("/mode "))
            splash.put(activeLog,false);
        if (line.equals("/new") || line.startsWith("/tab-")) awaitingTabChange=true;
        appInputWriter.write((line+"\n").getBytes(StandardCharsets.UTF_8)); appInputWriter.flush();
    }
    private void handleKey(int key) throws IOException {
        lastInput=System.currentTimeMillis();
        if (escapeAt!=0) {
            if (escape.isEmpty() && (key=='[' || key=='O')) { escape=Character.toString((char)key); return; }
            if (!escape.isEmpty()) {
                escape+=(char)key;
                if ((key>='A' && key<='Z') || key=='~' || escape.length()>8) {
                    handleEscape(escape); escape=""; escapeAt=0;
                }
                return;
            }
            escapeAt=0; closePicker();
        }
        if (key==27) { escapeAt=System.currentTimeMillis(); escape=""; return; }
        ApprovalDialog pending=approval;
        if (pending!=null) {
            if (key=='y' || key=='Y' || key=='1') { finishApproval(true); return; }
            if (key=='n' || key=='N' || key=='2' || key==3) { finishApproval(false); return; }
            if (key==9) { pending.selected=(pending.selected+1)%2; return; }
            if (key==13 || key==10) { finishApproval(pending.selected==0); return; }
            return;
        }
        Picker current=picker;
        if (current!=null) {
            if (key==3) { closePicker(); return; }
            if (key==13 || key==10) {
                picker=null;
                current.choice.complete(current.models.isEmpty()?(current.canLogin?"/login":null):current.models.get(current.selected).id());
                return;
            }
            if (current.models.isEmpty()) return;
            if (key==9) { closePicker(); submit("/tab-next"); return; }
            if (key=='j') { current.selected=(current.selected+1)%current.models.size(); return; }
            if (key=='k') { current.selected=(current.selected-1+current.models.size())%current.models.size(); return; }
            if (key>='1' && key<='9' && key-'1'<current.models.size()) current.selected=key-'1';
            return;
        }
        if (key==3) { submit("/quit"); return; }
        if (key==20) { submit("/new"); return; }
        if (key==23) { submit("/tab-close"); return; }
        if (key==4) { submit("/diff"); return; }
        if (key==9) { submit("/tab-next"); return; }
        if (key==15) { openLatestLink(); return; }
        if (key==13 || key==10) { submit(typed.toString()); typed.setLength(0); scroll=0; return; }
        if (key==127 || key==8) {
            if (!typed.isEmpty()) typed.delete(typed.offsetByCodePoints(typed.length(),-1),typed.length());
            return;
        }
        if (key>=128) { appendUtf8(key); return; }
        utf8.reset(); utf8Remaining=0;
        if (key>=32) typed.append((char)key);
    }
    private void appendUtf8(int value) {
        if (utf8Remaining==0) {
            utf8.reset();
            utf8Remaining=(value&0xe0)==0xc0?1:(value&0xf0)==0xe0?2:(value&0xf8)==0xf0?3:0;
            if (utf8Remaining==0) return;
        } else if ((value&0xc0)!=0x80) { utf8.reset(); utf8Remaining=0; return; }
        utf8.write(value);
        if (utf8Remaining>0 && utf8.size()>1) utf8Remaining--;
        if (utf8Remaining==0) {
            typed.append(utf8.toString(StandardCharsets.UTF_8));
            utf8.reset();
        }
    }
    private void handleEscape(String sequence) throws IOException {
        ApprovalDialog pending=approval;
        if (pending!=null) {
            if (sequence.equals("[A") || sequence.equals("[B") || sequence.equals("[Z"))
                pending.selected=(pending.selected+1)%2;
            else if (sequence.equals("[5~")) pending.scroll=Math.max(0,pending.scroll-10);
            else if (sequence.equals("[6~")) pending.scroll+=10;
            return;
        }
        Picker current=picker;
        if (current!=null) {
            if (sequence.equals("[Z")) { closePicker(); submit("/mode-next"); return; }
            if (current.models.isEmpty()) return;
            if (sequence.equals("[A")) current.selected=(current.selected-1+current.models.size())%current.models.size();
            else if (sequence.equals("[B")) current.selected=(current.selected+1)%current.models.size();
            return;
        }
        if (sequence.equals("[Z")) submit("/mode-next");
        else if (sequence.equals("[5~")) scroll+=10;
        else if (sequence.equals("[6~")) scroll=Math.max(0,scroll-10);
    }
    private void closePicker() {
        Picker current=picker;
        if (current!=null) { picker=null; current.choice.complete(null); }
    }
    private void finishApproval(boolean allowed) {
        ApprovalDialog pending=approval;
        if (pending!=null) { approval=null; pending.choice.complete(allowed); lastScreen=""; }
    }
    private void draw(Status status,long frame) {
        int width=Math.max(70,columns), height=Math.max(20,rows);
        int aside=35, mainWidth=width-aside-3;
        String all;
        synchronized(captured) {
            ByteArrayOutputStream log=captured.get(status.session());
            all=log==null?"":log.toString(StandardCharsets.UTF_8);
        }
        latestLink=findLatestLink(all);
        all=all.replaceAll("\\u001B\\[[0-9;]*[A-Za-z]","").replace('\r','\n');
        int bodyRows=height-5;
        ApprovalDialog pending=approval;
        boolean welcome=status.newChat() && splash.getOrDefault(status.session(),true) && picker==null && pending==null;
        List<String> welcomeRows=welcome?welcomeRows(status,bodyRows,width-2,(int)frame):List.of();
        List<ChatLine> lines=new ArrayList<>();
        if (pending!=null) lines=approvalLines(pending,bodyRows,mainWidth);
        else if (picker!=null) for (String line:modelLines(picker,bodyRows,mainWidth))
            lines.add(new ChatLine(line,line.startsWith(" › ")?Kind.SELECTED:Kind.SYSTEM));
        else if (!welcome) {
            Kind kind=Kind.SYSTEM;
            boolean diffActive=false;
            for (String line:all.split("\n",-1)) {
                String clean=line.replaceAll("[\\p{Cntrl}&&[^\\t]]","");
                if (clean.startsWith("[you] ")) { kind=Kind.USER; diffActive=false; }
                else if (clean.startsWith("[assistant] ")) { kind=Kind.ASSISTANT; diffActive=false; }
                else if (clean.startsWith("[tool] ") || clean.startsWith("[result] ")) { kind=Kind.TOOL; diffActive=false; }
                else if (clean.startsWith("Error:") || clean.startsWith("[approval] ")) { kind=Kind.ERROR; diffActive=false; }
                else if (clean.startsWith("[auto] ")) { kind=Kind.AUTO; diffActive=false; }
                else if (clean.startsWith("[diff] ") || clean.startsWith("[git] ") || clean.startsWith("diff --git ")) {
                    kind=Kind.FILE; diffActive=true;
                }
                else if (diffActive && clean.startsWith("@@")) kind=Kind.HUNK;
                else if (diffActive && clean.startsWith("+++")) kind=Kind.FILE;
                else if (diffActive && clean.startsWith("---")) kind=Kind.FILE;
                else if (diffActive && clean.startsWith("+")) kind=Kind.ADDED;
                else if (diffActive && clean.startsWith("-")) kind=Kind.REMOVED;
                else if (diffActive && clean.startsWith(" ")) kind=Kind.CONTEXT;
                if (clean.isEmpty()) { lines.add(new ChatLine("",Kind.SYSTEM)); continue; }
                int contentWidth=Math.max(12,mainWidth-3);
                for (int at=0;at<clean.length();at+=contentWidth)
                    lines.add(new ChatLine(clean.substring(at,Math.min(clean.length(),at+contentWidth)),kind));
            }
        }
        int from=pending!=null?0:Math.max(0,lines.size()-bodyRows-scroll);
        String state=activityState(working,System.currentTimeMillis()-lastInput);
        List<String> rabbit=Bunny.frame(state,(int)frame);
        List<String> side=new ArrayList<>();
        side.add(" " + bold("EGONCODE") + "  " + state.toUpperCase(Locale.ROOT));
        side.add("");
        side.add(" Project: " + cut(Path.of(status.project()).getFileName().toString(),24));
        side.add(" Provider: " + cut(status.provider(),23));
        side.add(" Model: " + cut(status.model().isBlank()?"select with /model":status.model(),26));
        side.add(" Mode: " + (status.mode().equals("auto")?"\033[38;2;129;236;181mAUTO\033[39m":
                status.mode().equals("plan")?"\033[38;2;192;166;255mPLAN\033[39m":"EDIT"));
        side.add(" Session: " + cut(status.session(),12));
        side.add(" Tokens: " + status.inputTokens()+" in / "+status.outputTokens()+" out");
        if (status.provider().equals("chatgpt")) {
            side.add(" \033[38;2;129;236;181mUsing ChatGPT plan\033[39m");
            if (!status.codexWindows().isEmpty()) {
                side.add(" Codex quota:");
                for (String window:status.codexWindows()) {
                    String[] pieces=window.split(", resets ",2);
                    side.add(" " + cut(pieces[0],32));
                    if (pieces.length==2) side.add("  Resets: " + cut(pieces[1],23));
                }
            }
            side.add(" " + hyperlink("https://chatgpt.com/settings/usage","View plan usage"));
        } else if (!status.rateLimits().isEmpty()) {
            side.add(" \033[38;2;129;236;181mAPI rate limits\033[39m");
            for (String limit:status.rateLimits()) side.add(" " + cut(limit,32));
        } else if (status.provider().equals("anthropic") || status.provider().equals("openai"))
            side.add(" API limits: send a message");
        side.add(""); side.addAll(rabbit);
        StringBuilder screen=new StringBuilder("\033[?25l\033[H");
        screen.append("\033[48;2;46;54;72m\033[38;2;240;244;250m ")
                .append(pad("EGONCODE  /  "+cut(status.project(),width-52)+"  /  "+status.provider()+"/"+status.model(),width-2))
                .append("\033[0K\r\n");
        screen.append(BG).append(FG).append(' ');
        int tabWidth=1;
        for (int i=0;i<status.tabs().size();i++) {
            String label=" "+(i+1)+":"+cut(status.tabs().get(i).title(),16)+" ";
            if (tabWidth+label.length()>width-2) break;
            screen.append(i==status.activeTab()?"\033[48;2;60;79;100m\033[1m":BG+FG).append(label);
            tabWidth+=label.length();
        }
        screen.append(BG).append(FG).append("\033[0K\r\n");
        for (int i=0;i<bodyRows;i++) {
            screen.append(BG).append(FG);
            if (welcome) screen.append(' ').append(welcomeRows.get(i));
            else {
                ChatLine log=from+i<lines.size()?lines.get(from+i):new ChatLine("",Kind.SYSTEM);
                screen.append(' ').append(renderChatLine(log,mainWidth)).append(BG).append(FG).append(" │");
                String s=i<side.size()?side.get(i):"";
                screen.append(s);
            }
            screen.append(BG).append(FG).append("\033[0K\r\n");
        }
        screen.append(BG).append(FG).append("─".repeat(width-1)).append("\033[0K\r\n");
        screen.append("\033[48;2;35;44;61m\033[38;2;168;221;255m").append(" › ")
                .append("\033[38;2;247;249;255m")
                .append(pending==null?cut(typed.toString(),width-5):"Review the request above")
                .append("\033[0K\r\n");
        screen.append(BG).append(FG);
        if (pending!=null) screen.append(" Approval: 1/Y approve once   2/N deny   ↑/↓ choose   PgUp/PgDn review");
        else {
            screen.append(" Tab next chat  Shift+Tab mode  Ctrl+T new  Ctrl+W close  Ctrl+D diff  ");
            if (latestLink.isBlank()) screen.append("Ctrl+O open link");
            else screen.append("Ctrl+O ").append(hyperlink(latestLink,"open link"));
        }
        screen.append(BG).append(FG).append("\033[0K");
        if (picker==null && pending==null) screen.append("\033[").append(height-1).append(';')
                .append(Math.min(width-1,4+typed.length())).append("H\033[?25h");
        String frameText=screen.toString();
        if (!frameText.equals(lastScreen)) {
            terminalOutput.print(frameText); terminalOutput.flush();
            lastScreen=frameText;
        }
    }
    private static String renderChatLine(ChatLine line,int width) {
        String bg,fg,accent;
        switch (line.kind()) {
            case USER -> { bg=USER_BG; fg=USER_FG; accent="\033[38;2;113;205;255m"; }
            case ASSISTANT -> { bg=ASSISTANT_BG; fg=ASSISTANT_FG; accent="\033[38;2;206;171;255m"; }
            case TOOL -> { bg=TOOL_BG; fg=TOOL_FG; accent="\033[38;2;105;218;174m"; }
            case ERROR -> { bg=ERROR_BG; fg=ERROR_FG; accent="\033[38;2;255;151;171m"; }
            case SELECTED -> { bg="\033[48;2;55;64;92m"; fg="\033[38;2;241;246;255m"; accent="\033[38;2;132;205;255m"; }
            case ADDED -> { bg=ADD_BG; fg="\033[38;2;176;247;197m"; accent="\033[38;2;70;210;126m"; }
            case REMOVED -> { bg=REMOVE_BG; fg="\033[38;2;255;193;199m"; accent="\033[38;2;255;108;125m"; }
            case HUNK -> { bg=HUNK_BG; fg="\033[38;2;210;198;255m"; accent="\033[38;2;159;132;239m"; }
            case FILE -> { bg=FILE_BG; fg="\033[38;2;170;229;255m"; accent="\033[38;2;88;195;232m"; }
            case CONTEXT -> { bg=TOOL_BG; fg=TOOL_FG; accent="\033[38;2;84;137;124m"; }
            case AUTO -> { bg=TOOL_BG; fg=TOOL_FG; accent="\033[38;2;105;218;174m"; }
            default -> { return BG+FG+pad(line.text(),width); }
        }
        return accent+bg+"▌"+fg+bg+" "+pad(line.text(),Math.max(0,width-2));
    }
    private static List<ChatLine> approvalLines(ApprovalDialog dialog,int height,int width) {
        List<ChatLine> details=new ArrayList<>();
        boolean diff=false;
        for (String raw:dialog.description.split("\n",-1)) {
            Kind kind=Kind.TOOL;
            if (raw.startsWith("[diff] ") || raw.startsWith("diff --git ")) { kind=Kind.FILE; diff=true; }
            else if (diff && raw.startsWith("@@")) kind=Kind.HUNK;
            else if (diff && raw.startsWith("+")) kind=Kind.ADDED;
            else if (diff && raw.startsWith("-")) kind=Kind.REMOVED;
            else if (diff && raw.startsWith(" ")) kind=Kind.CONTEXT;
            else if (diff && raw.isBlank()) kind=Kind.SYSTEM;
            int contentWidth=Math.max(12,width-3);
            if (raw.isEmpty()) details.add(new ChatLine("",kind));
            else for (int at=0;at<raw.length();at+=contentWidth)
                details.add(new ChatLine(raw.substring(at,Math.min(raw.length(),at+contentWidth)),kind));
        }
        int viewport=Math.max(1,height-7);
        int start=Math.min(dialog.scroll,Math.max(0,details.size()-viewport));
        List<ChatLine> result=new ArrayList<>();
        result.add(new ChatLine(" APPROVAL REQUIRED",Kind.HUNK));
        result.add(new ChatLine(" Review this action before EgonCode continues",Kind.SYSTEM));
        result.add(new ChatLine("",Kind.SYSTEM));
        for (int i=0;i<viewport;i++)
            result.add(start+i<details.size()?details.get(start+i):new ChatLine("",Kind.SYSTEM));
        result.add(new ChatLine("",Kind.SYSTEM));
        result.add(new ChatLine((dialog.selected==0?" › ":"   ")+"1. Approve once",dialog.selected==0?Kind.SELECTED:Kind.TOOL));
        result.add(new ChatLine((dialog.selected==1?" › ":"   ")+"2. Deny",dialog.selected==1?Kind.SELECTED:Kind.TOOL));
        result.add(new ChatLine(" ↑/↓ select  Enter confirm  Esc deny  PgUp/PgDn scroll",Kind.SYSTEM));
        return result;
    }
    private static List<String> modelLines(Picker picker,int height,int width) {
        List<String> lines=new ArrayList<>();
        lines.add("Select model  ↑/↓ move   Enter choose   Esc close   Tab next chat   Shift+Tab mode");
        lines.add("");
        if (picker.models.isEmpty()) {
            lines.add("No available models could be loaded.");
            if (!picker.error.isBlank()) lines.add(cut(picker.error,width));
            lines.add(picker.canLogin?"Press Enter to sign in with ChatGPT, or Esc to close.":"Check your provider configuration, then reopen /model.");
            return lines;
        }
        int visible=Math.max(1,height-3);
        int first=Math.max(0,Math.min(picker.selected-visible/2,picker.models.size()-visible));
        for (int i=first;i<Math.min(picker.models.size(),first+visible);i++) {
            ModelCatalog.Model model=picker.models.get(i);
            String line=(i==picker.selected?" › ":"   ")+(i+1)+". "+model.name()+"  ("+model.id()+")";
            if (model.id().equals(picker.recommended)) line+="  recommended";
            lines.add(cut(line,width));
        }
        return lines;
    }
    private static List<String> welcomeRows(Status status,int height,int width,int frame) {
        String[] art={
                "███████╗ ██████╗  ██████╗ ███╗   ██╗",
                "██╔════╝██╔════╝ ██╔═══██╗████╗  ██║",
                "█████╗  ██║  ███╗██║   ██║██╔██╗ ██║",
                "██╔══╝  ██║   ██║██║   ██║██║╚██╗██║",
                "███████╗╚██████╔╝╚██████╔╝██║ ╚████║",
                "╚══════╝ ╚═════╝  ╚═════╝ ╚═╝  ╚═══╝",
                "                C O D E"
        };
        List<String> content=new ArrayList<>();
        if (height>=31) {
            for (String line:art) content.add("\033[38;2;192;166;255m"+line);
            content.add("");
        } else if (height>=18) {
            content.add("\033[1;38;2;192;166;255mE G O N C O D E");
            content.add("");
        }
        for (String line:Bunny.frame("sleeping",frame)) content.add(line.substring(3));
        if (height>=18) {
            content.add("");
            content.add("\033[38;2;168;221;255mWhat would you like to build?");
        }
        if (height>=20) content.add("Type a task, /model to choose a model, or /login to connect ChatGPT.");
        if (height>=31) {
            content.add("");
            content.add("Provider: "+status.provider()+"   Model: "+
                    (status.model().isBlank()?"choose with /model":status.model())+"   Mode: "+status.mode());
        }
        List<String> rows=new ArrayList<>(Collections.nCopies(height,centerWelcome("",width)));
        int start=Math.max(0,(height-content.size())/2);
        for (int i=0;i<content.size() && start+i<height;i++)
            rows.set(start+i,centerWelcome(content.get(i),width));
        return rows;
    }
    private static String centerWelcome(String value,int width) {
        String plain=value.replaceAll("\\u001B\\[[0-9;]*[A-Za-z]","");
        int visible=plain.codePointCount(0,plain.length());
        if (visible>width) { value=cut(plain,width); visible=value.codePointCount(0,value.length()); }
        int left=Math.max(0,(width-visible)/2);
        return " ".repeat(left)+value+BG+FG+" ".repeat(Math.max(0,width-left-visible));
    }
    private static String bold(String s) { return "\033[1m"+s+"\033[22m"; }
    static String activityState(boolean working,long idleMillis) {
        return working?"running":idleMillis>=SLEEP_AFTER_MS?"sleeping":"idle";
    }
    private static String hyperlink(String url,String label) {
        return "\033]8;;"+url+"\033\\"+label+"\033]8;;\033\\";
    }
    private static String findLatestLink(String content) {
        Matcher matcher=URL.matcher(content);
        String latest="";
        while (matcher.find()) {
            String candidate=matcher.group().replaceAll("[),.;]+$","");
            try {
                URI uri=URI.create(candidate);
                if (uri.getHost()!=null && (uri.getScheme().equals("https") || uri.getScheme().equals("http"))) latest=candidate;
            } catch (IllegalArgumentException ignored) {}
        }
        return latest;
    }
    void openLatestLink() {
        String url=latestLink;
        if (url.isBlank()) { System.out.println("No link to open yet."); return; }
        Thread opener=new Thread(() -> {
            if (Browser.open(url)) System.out.println("Opening link in browser: " + url);
            else System.err.println("Could not open link in a browser. Copy this URL: " + url);
        },"egoncode-open-link");
        opener.setDaemon(true); opener.start();
    }
    private static String cut(String s,int width) { return s.length()<=width?s:s.substring(0,Math.max(0,width-1))+"…"; }
    private static String pad(String s,int width) { return cut(s,width)+" ".repeat(Math.max(0,width-Math.min(width,s.length()))); }
    private static String stty(String... args) throws Exception {
        List<String> command=new ArrayList<>(); command.add("stty"); command.addAll(Arrays.asList(args));
        Process process=new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.INHERIT).start();
        String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        if (process.waitFor()!=0) throw new IOException("stty failed");
        return output;
    }
    private void updateSize() {
        try {
            String[] size=stty("size").trim().split("\\s+");
            if (size.length==2) {
                int newRows=Integer.parseInt(size[0]), newColumns=Integer.parseInt(size[1]);
                if (newRows>=20 && newColumns>=70) { rows=newRows; columns=newColumns; }
            }
        } catch (Exception ignored) {}
    }
    private void restore() throws Exception {
        if (!closed.compareAndSet(false,true)) return;
        System.setIn(terminalInput); System.setOut(terminalOutput); System.setErr(terminalError);
        stty(oldStty); terminalOutput.print("\033[?1049l\033[?25h"); terminalOutput.flush();
    }
    @Override public void close() { try { restore(); } catch (Exception ignored) {} }
}
