package dev.egoncode;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class TeamIntegrationTest {
    private static void git(Path root, String... args) throws Exception {
        List<String> command=new ArrayList<>(); command.add("git"); command.add("-C"); command.add(root.toString()); command.addAll(Arrays.asList(args));
        Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        if (process.waitFor()!=0) throw new AssertionError(command + ": " + output);
    }
    public static void main(String[] args) throws Exception {
        Path home=Files.createTempDirectory("egon-team-home-");
        Path root=Files.createTempDirectory("egon-team-root-");
        git(root,"init"); git(root,"config","user.email","test@example.com"); git(root,"config","user.name","Test");
        Files.writeString(root.resolve("hello.txt"),"before\n");
        git(root,"add","hello.txt"); git(root,"commit","-m","initial");
        Path worktree=home.resolve("worker");
        git(root,"worktree","add","-b","egon/test",worktree.toString(),"HEAD");
        Files.writeString(worktree.resolve("hello.txt"),"after\n");
        Files.writeString(worktree.resolve("new.txt"),"created\n");
        Store store=new Store(home);
        String id="test-team";
        Map<String,Object> member=Json.obj("task","edit files","worktree",worktree.toString(),"branch","egon/test","status","done","log","");
        store.write("teams/"+id+".json",Json.obj("id",id,"goal","test","root",root.toString(),"git",true,"members",List.of(member)),false);
        new Team(store,new ChatGptAuth(store),root,description -> true).apply(id,1);
        if (!Files.readString(root.resolve("hello.txt")).equals("after\n")) throw new AssertionError("tracked file not applied");
        if (!Files.readString(root.resolve("new.txt")).equals("created\n")) throw new AssertionError("new file not applied");
        if (!Boolean.TRUE.equals(Json.map(Json.list(store.read("teams/"+id+".json").get("members")).getFirst()).get("integrated")))
            throw new AssertionError("integration status missing");
        System.out.println("Team integration passed");
    }
}
