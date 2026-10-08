package com.natestechstuff.jarvis;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.3: just enough Markdown for chat bubbles (no libraries): **bold**, *italic*, `inline code`,
 * ``` code blocks ```, # headings, and "-"/"*" bullets (→ •). Numbered lists stay as typed.
 * {@link #parse} is pure Java (unit-tested); {@link #render} turns it into Android spans.
 */
public final class Markdown {
    private Markdown() {}

    public static final int BOLD = 1, ITALIC = 2, CODE = 3, CODE_BLOCK = 4, HEADING = 5;

    public static final class Span {
        public final int start, end, type;
        Span(int start, int end, int type) { this.start = start; this.end = end; this.type = type; }
        @Override public String toString() { return type + "@" + start + "-" + end; }
    }

    public static final class Result {
        public final String text;
        public final List<Span> spans;
        Result(String text, List<Span> spans) { this.text = text; this.spans = spans; }
    }

    private static final Pattern INLINE = Pattern.compile(
            "`([^`\n]+)`|\\*\\*([^*\n]+?)\\*\\*|__([^_\n]+?)__|(?<![*\\w])\\*(?!\\s)([^*\n]+?)(?<!\\s)\\*(?![*\\w])");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+");
    private static final Pattern HEAD = Pattern.compile("^#{1,6}\\s+");

    public static Result parse(String md) {
        StringBuilder out = new StringBuilder();
        List<Span> spans = new ArrayList<>();
        if (md == null) return new Result("", spans);
        String[] lines = md.replace("\r\n", "\n").split("\n", -1);
        boolean inCode = false;
        int codeStart = 0;
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            boolean last = li == lines.length - 1;
            if (line.trim().startsWith("```")) {
                if (!inCode) {
                    inCode = true;
                    codeStart = out.length();
                } else {
                    inCode = false;
                    int end = out.length();
                    if (end > codeStart && out.charAt(end - 1) == '\n') end--;   // the block's own last newline
                    if (end > codeStart) spans.add(new Span(codeStart, end, CODE_BLOCK));
                    if (last && out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.setLength(out.length() - 1);
                }
                continue;   // fence lines themselves are not shown
            }
            if (inCode) {
                out.append(line);
                if (!last) out.append('\n');
                continue;
            }
            int lineStart = out.length();
            boolean heading = false;
            Matcher h = HEAD.matcher(line);
            if (h.find()) { line = line.substring(h.end()); heading = true; }
            Matcher b = BULLET.matcher(line);
            if (!heading && b.find() && !line.trim().startsWith("**")) line = b.group(1) + "• " + line.substring(b.end());
            inline(line, out, spans);
            if (heading && out.length() > lineStart) spans.add(new Span(lineStart, out.length(), HEADING));
            if (!last) out.append('\n');
        }
        if (inCode) {   // unclosed block (still streaming / cut off)
            int end = out.length();
            if (end > codeStart) spans.add(new Span(codeStart, end, CODE_BLOCK));
        }
        return new Result(out.toString(), spans);
    }

    private static void inline(String line, StringBuilder out, List<Span> spans) {
        Matcher m = INLINE.matcher(line);
        int at = 0;
        while (m.find()) {
            out.append(line, at, m.start());
            int type = m.group(1) != null ? CODE : (m.group(2) != null || m.group(3) != null) ? BOLD : ITALIC;
            String inner = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            int s = out.length();
            out.append(inner);
            spans.add(new Span(s, out.length(), type));
            at = m.end();
        }
        out.append(line, at, line.length());
    }

    /** Spanned text for a TextView. codeBg = background color behind code. */
    public static CharSequence render(String md, int codeBg) {
        Result r = parse(md);
        SpannableStringBuilder sb = new SpannableStringBuilder(r.text);
        for (Span s : r.spans) {
            if (s.end <= s.start || s.end > sb.length()) continue;
            int f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
            switch (s.type) {
                case BOLD: sb.setSpan(new StyleSpan(Typeface.BOLD), s.start, s.end, f); break;
                case ITALIC: sb.setSpan(new StyleSpan(Typeface.ITALIC), s.start, s.end, f); break;
                case HEADING:
                    sb.setSpan(new StyleSpan(Typeface.BOLD), s.start, s.end, f);
                    sb.setSpan(new RelativeSizeSpan(1.12f), s.start, s.end, f);
                    break;
                case CODE:
                    sb.setSpan(new TypefaceSpan("monospace"), s.start, s.end, f);
                    sb.setSpan(new BackgroundColorSpan(codeBg), s.start, s.end, f);
                    break;
                case CODE_BLOCK:
                    sb.setSpan(new TypefaceSpan("monospace"), s.start, s.end, f);
                    sb.setSpan(new RelativeSizeSpan(0.88f), s.start, s.end, f);
                    sb.setSpan(new BackgroundColorSpan(codeBg), s.start, s.end, f);
                    break;
                default:
            }
        }
        return sb;
    }
}
