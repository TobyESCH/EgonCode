package dev.egoncode;

import java.util.*;

final class Diff {
    private Diff() {}
    static String preview(String before, String after) {
        List<String> oldLines = lines(before);
        List<String> newLines = lines(after);
        int prefix=0;
        while (prefix<oldLines.size() && prefix<newLines.size() && oldLines.get(prefix).equals(newLines.get(prefix))) prefix++;
        int suffix=0;
        while (suffix<oldLines.size()-prefix && suffix<newLines.size()-prefix &&
                oldLines.get(oldLines.size()-1-suffix).equals(newLines.get(newLines.size()-1-suffix))) suffix++;
        if (prefix==oldLines.size() && prefix==newLines.size()) return "(no change)";
        int beforeContext=Math.min(2,prefix), afterContext=Math.min(2,suffix);
        int oldCount=beforeContext+oldLines.size()-prefix-suffix+afterContext;
        int newCount=beforeContext+newLines.size()-prefix-suffix+afterContext;
        int oldStart=oldCount==0?prefix:prefix-beforeContext+1;
        int newStart=newCount==0?prefix:prefix-beforeContext+1;
        StringBuilder out=new StringBuilder("@@ -").append(oldStart).append(',').append(oldCount)
                .append(" +").append(newStart).append(',').append(newCount).append(" @@\n");
        for (int i=prefix-beforeContext;i<prefix;i++) out.append(' ').append(oldLines.get(i)).append('\n');
        for (int i=prefix;i<oldLines.size()-suffix;i++) out.append('-').append(oldLines.get(i)).append('\n');
        for (int i=prefix;i<newLines.size()-suffix;i++) out.append('+').append(newLines.get(i)).append('\n');
        for (int i=oldLines.size()-suffix;i<oldLines.size()-suffix+afterContext;i++)
            out.append(' ').append(oldLines.get(i)).append('\n');
        if (out.length()>8000) return out.substring(0,8000)+"\n[preview truncated]";
        return out.toString();
    }
    private static List<String> lines(String content) {
        if (content.isEmpty()) return List.of();
        List<String> lines=new ArrayList<>(Arrays.asList(content.split("\n",-1)));
        if (content.endsWith("\n")) lines.removeLast();
        return lines;
    }
}
