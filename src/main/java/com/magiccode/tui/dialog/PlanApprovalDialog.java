package com.magiccode.tui.dialog;

import com.magiccode.tui.Styles;
import com.magiccode.tui.tea.Style;
import com.magiccode.tui.tea.ANSI256Color;

public class PlanApprovalDialog {

    private int cursor;
    private final StringBuilder feedbackInput = new StringBuilder();
    private boolean active;

    private static final Style HEADER_STYLE = Style.newStyle().foreground(new ANSI256Color(99)).bold(true);
    private static final Style CURSOR_STYLE = Style.newStyle().foreground(new ANSI256Color(99));
    private static final Style DIM_STYLE = Style.newStyle().foreground(new ANSI256Color(242));
    private static final Style BOLD_STYLE = Style.newStyle().bold(true);

    private static final String[] OPTIONS = {
            "Yes, enter YOLO mode (auto-approve all)",
            "Yes, manually approve edits",
            "Tell MagicCode what to change",
    };

    public enum Result { YOLO, MANUAL, FEEDBACK, CANCEL }
    public record DialogResult(Result type, String feedback) {}

    public void activate() { active = true; cursor = 0; feedbackInput.setLength(0); }
    public boolean isActive() { return active; }

    public DialogResult handleKey(String key) {
        switch (key) {
            case "up", "k" -> { if (cursor > 0) cursor--; }
            case "down", "j" -> { if (cursor < 2) cursor++; }
            case "enter" -> {
                if (cursor == 2 && feedbackInput.isEmpty()) return null;
                active = false;
                return switch (cursor) {
                    case 0 -> new DialogResult(Result.YOLO, "");
                    case 1 -> new DialogResult(Result.MANUAL, "");
                    case 2 -> new DialogResult(Result.FEEDBACK, feedbackInput.toString());
                    default -> null;
                };
            }
            case "shift+tab" -> { if (cursor == 2 && !feedbackInput.isEmpty()) { active = false; return new DialogResult(Result.FEEDBACK, feedbackInput.toString()); } }
            case "escape" -> { active = false; return new DialogResult(Result.CANCEL, ""); }
            case "backspace" -> { if (cursor == 2 && !feedbackInput.isEmpty()) feedbackInput.deleteCharAt(feedbackInput.length() - 1); }
            default -> {
                if (cursor == 2 && key.length() == 1) { char ch = key.charAt(0); if (ch >= 32 && ch <= 126) feedbackInput.append(ch); }
                else if (cursor == 2 && " ".equals(key)) feedbackInput.append(' ');
            }
        }
        return null;
    }

    public String render() {
        var sb = new StringBuilder();
        sb.append(HEADER_STYLE.render(" MagicCode has written up a plan and is ready to execute. Would you like to proceed?"));
        sb.append("\n\n");
        for (int i = 0; i < OPTIONS.length; i++) {
            String prefix = (i == cursor) ? CURSOR_STYLE.render(" ❯ ") : "   ";
            String label = (i == cursor) ? BOLD_STYLE.render(OPTIONS[i]) : DIM_STYLE.render(OPTIONS[i]);
            sb.append(prefix).append(String.format("%d. %s", i + 1, label)).append('\n');
            if (i == 2) {
                String inputLine = feedbackInput.toString();
                if (cursor == 2) inputLine += "█";
                if ((cursor == 2 && inputLine.equals("█")) || inputLine.isEmpty()) {
                    if (cursor == 2) { sb.append("      ").append(DIM_STYLE.render("Type feedback here...")).append('\n'); }
                } else {
                    sb.append("      ").append(inputLine).append('\n');
                }
                sb.append(DIM_STYLE.render("      shift+tab to approve with this feedback")).append('\n');
            }
        }
        sb.append('\n');
        return sb.toString();
    }
}
