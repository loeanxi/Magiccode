// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.magiccode.tui.tea;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 基于 JLine 的 Bubble Tea 风格 TUI 运行时。
 *
 * 绝对定位渲染：
 *  - 首次渲染用 \033[6n 查询光标位置，记录为 viewStartRow
 *  - 每次重绘用 \033[row;1H 绝对定位到 view 起始行
 *  - 绘制完成后将光标绝对定位到输入行（Windows IME 修复）
 *  - 不依赖 cursor-up 相对移动，避免 IME 光标移动后的状态混乱
 */
public class Program {

    private final Model model;
    private final BlockingQueue<Message> msgQueue = new LinkedBlockingQueue<>();
    private Terminal terminal;
    private PrintWriter writer;
    private volatile boolean running;

    private String lastViewContent = "";

    // view 起始的终端行号（1-indexed），首次渲染时通过 \033[6n 查询
    private int viewStartRow = -1;

    public Program(Model model) {
        this.model = model;
    }

    public void send(Message msg) {
        msgQueue.offer(msg);
    }

    public int getAvailableHeight() {
        int h = terminal != null ? terminal.getSize().getRows() : 24;
        return Math.max(h - 1, 3);
    }

    public void run() {
        try {
            terminal = TerminalBuilder.builder()
                    .system(true)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to open terminal: " + e.getMessage(), e);
        }

        terminal.enterRawMode();
        // Windows cmd.exe 不尊重 enterRawMode()，需要显式关闭回显
        try {
            var attrs = terminal.getAttributes();
            attrs.setLocalFlag(org.jline.terminal.Attributes.LocalFlag.ECHO, false);
            terminal.setAttributes(attrs);
        } catch (Exception ignored) {}
        writer = terminal.writer();
        writer.flush();

        running = true;

        terminal.handle(Terminal.Signal.INT, sig ->
                msgQueue.offer(new KeyPressMessage("ctrl+c", null)));
        terminal.handle(Terminal.Signal.WINCH, sig -> {
            var size = terminal.getSize();
            msgQueue.offer(new WindowSizeMessage(size.getColumns(), size.getRows()));
        });

        // 先查询光标位置（\033[6n 响应通过 terminal.reader() 返回）
        // 必须在 keyReaderLoop 启动之前，否则响应会被当作按键消费
        int row = queryCursorRow();
        viewStartRow = (row > 0) ? row : 1;

        Thread.startVirtualThread(this::keyReaderLoop);
        executeCommand(model.init());
        renderView();

        try {
            while (running) {
                Message msg = msgQueue.poll(16, TimeUnit.MILLISECONDS);
                if (msg == null) continue;
                if (msg instanceof QuitMessage) { running = false; break; }

                var result = model.update(msg);
                if (result.command() != null) executeCommand(result.command());
                renderView();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
            clearView();
            writer.print("\033[?25h");
            writer.flush();
            try { terminal.close(); } catch (IOException ignored) {}
        }
    }

    // ── 渲染 ──────────────────────────────────────────────────────────

    private static final java.util.regex.Pattern ANSI_PATTERN =
            java.util.regex.Pattern.compile("\033\\[[0-9;]*[a-zA-Z]|\033][^\007\033]*(?:\007|\033\\\\)");

    /**
     * 计算字符串在终端中的显示宽度（CJK 全角字符占 2 列）。
     */
    public static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (isWide(cp)) w += 2; else w += 1;
            i += Character.charCount(cp);
        }
        return w;
    }

    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
            || (cp >= 0x2E80 && cp <= 0x303E)
            || (cp >= 0x3040 && cp <= 0x33BF)
            || (cp >= 0x3400 && cp <= 0x4DBF)
            || (cp >= 0x4E00 && cp <= 0x9FFF)
            || (cp >= 0xA000 && cp <= 0xA4CF)
            || (cp >= 0xAC00 && cp <= 0xD7AF)
            || (cp >= 0xF900 && cp <= 0xFAFF)
            || (cp >= 0xFE30 && cp <= 0xFE6F)
            || (cp >= 0xFF01 && cp <= 0xFF60)
            || (cp >= 0xFFE0 && cp <= 0xFFE6)
            || (cp >= 0x20000 && cp <= 0x2FA1F)
            || (cp >= 0x30000 && cp <= 0x3134F);
    }

    /**
     * 计算一组逻辑行占用的物理行数（考虑换行 + CJK 全角）。
     */
    private int physicalLineCount(String[] lines) {
        int cols = terminal != null ? terminal.getSize().getColumns() : 80;
        if (cols <= 0) cols = 80;
        int total = 0;
        for (String line : lines) {
            int w = displayWidth(ANSI_PATTERN.matcher(line).replaceAll(""));
            total += Math.max(1, (int) Math.ceil((double) w / cols));
        }
        return total;
    }

    /**
     * 查询终端当前光标行号（1-indexed）。使用 \033[6n DSR 序列。
     */
    private int queryCursorRow() {
        try {
            writer.print("\033[6n");
            writer.flush();
            NonBlockingReader reader = terminal.reader();
            StringBuilder buf = new StringBuilder();
            long deadline = System.currentTimeMillis() + 500;
            while (System.currentTimeMillis() < deadline) {
                int ch = reader.read(50);
                if (ch == -2) continue;
                if (ch == -1) break;
                buf.append((char) ch);
                if ((char) ch == 'R') break;
            }
            String resp = buf.toString();
            if (resp.startsWith("\033[") && resp.endsWith("R")) {
                String inner = resp.substring(2, resp.length() - 1);
                String[] parts = inner.split(";");
                if (parts.length >= 1) {
                    return Integer.parseInt(parts[0]);
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * 找到输入行在 lines 数组中的索引。
     * 输入行特征：包含 "Send a message" 或光标符 "█"，或以 prompt 开头且有内容。
     */
    private int findInputLine(String[] lines) {
        for (int i = lines.length - 1; i >= 0; i--) {
            String stripped = ANSI_PATTERN.matcher(lines[i]).replaceAll("");
            if (stripped.contains("Send a message") || stripped.contains("\u2588")) {
                return i;
            }
            // 匹配 prompt 字符 ❯ (U+276F)
            if (stripped.startsWith("\u276F ") && stripped.length() > 2) {
                return i;
            }
        }
        return -1;
    }

    private void renderView() {
        String view = model.view();
        if (view.equals(lastViewContent)) return;
        lastViewContent = view;

        // 去掉末尾换行
        if (view.endsWith("\n")) {
            view = view.substring(0, view.length() - 1);
        }

        String[] lines = view.split("\n", -1);
        int totalPhysicalLines = physicalLineCount(lines);
        int maxLines = terminal != null ? terminal.getSize().getRows() - 1 : 23;
        if (totalPhysicalLines > maxLines) totalPhysicalLines = maxLines;

        // 绝对定位到 view 起始行，列 1（viewStartRow 在 run() 中已查询）
        int startRow = viewStartRow > 0 ? viewStartRow : 1;
        writer.print("\033[" + startRow + ";1H");

        // 逐行写入，每行末尾 \033[K 清除该行残余字符
        // 去掉光标块 "█"，由终端光标位置单独指示输入点
        var sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replace("\u2588", "");
            sb.append(line).append("\033[K");
            if (i < lines.length - 1) sb.append("\n");
        }
        writer.print(sb);

        // 清除 view 下方的多余行
        writer.print("\033[J");

        // ─ Windows IME 修复：将光标移到输入行 ────────────────────────
        int inputLineIndex = findInputLine(lines);
        if (inputLineIndex >= 0) {
            int cols = terminal != null ? terminal.getSize().getColumns() : 80;
            if (cols <= 0) cols = 80;

            // 计算输入行之前的物理行数
            int linesBeforeInput = 0;
            for (int i = 0; i < inputLineIndex; i++) {
                int w = displayWidth(ANSI_PATTERN.matcher(lines[i]).replaceAll(""));
                linesBeforeInput += Math.max(1, (int) Math.ceil((double) w / cols));
            }

            // 计算输入行 prompt 前缀的显示宽度，将光标定位在 prompt 之后
            // 这样 IME 候选窗口固定在 prompt 位置，输入的文字向右延伸
            String inputPlain = ANSI_PATTERN.matcher(lines[inputLineIndex]).replaceAll("");
            int promptWidth = 0;
            if (inputPlain.startsWith("\u276F ")) {
                promptWidth = displayWidth("\u276F ");
            }
            int inputCol = promptWidth + 1; // 1-indexed 列号

            // 绝对定位到输入行的 prompt 之后
            int inputRow = viewStartRow + linesBeforeInput;
            writer.print("\033[" + inputRow + ";" + inputCol + "H");
        }

        writer.flush();
    }

    // 清除当前 view 区域
    private void clearView() {
        if (viewStartRow > 0) {
            writer.print("\033[" + viewStartRow + ";1H");
        }
        writer.print("\033[J");
        viewStartRow = -1;
        lastViewContent = "";
        writer.flush();
    }

    // ── 命令执行 ────────────────────────────────────────────────────────

    private void executeCommand(Command cmd) {
        if (cmd == null) return;
        switch (cmd) {
            case Command.Simple s -> {
                Message msg = s.fn().get();
                if (msg != null) msgQueue.offer(msg);
            }
            case Command.Tick t -> {
                Thread.startVirtualThread(() -> {
                    try { Thread.sleep(t.delay().toMillis()); }
                    catch (InterruptedException e) { return; }
                    if (!running) return;
                    Message msg = t.fn().apply(Instant.now());
                    if (msg != null) msgQueue.offer(msg);
                });
            }
            case Command.CheckWindowSize ignored -> {
                var size = terminal.getSize();
                msgQueue.offer(new WindowSizeMessage(size.getColumns(), size.getRows()));
            }
            case Command.Batch b -> {
                for (var c : b.commands()) executeCommand(c);
            }
            case Command.PrintLine p -> {
                // 清除 view，写 println 文本（留在终端 scrollback），重绘 view
                int oldStartRow = viewStartRow > 0 ? viewStartRow : 1;
                clearView();
                writer.print(p.text() + "\n");
                writer.flush();
                // 手动跟踪光标：打印文本后光标下移了文本的物理行数
                String[] textLines = p.text().split("\n", -1);
                int textPhysLines = physicalLineCount(textLines);
                viewStartRow = oldStartRow + textPhysLines;
                renderView();
            }
        }
    }

    // ─ 按键读取 ────────────────────────────────────────────────────────

    private void keyReaderLoop() {
        NonBlockingReader reader = terminal.reader();
        try {
            while (running) {
                int c = reader.read(50);
                if (c == -2) continue;
                if (c == -1) { msgQueue.offer(new QuitMessage()); return; }
                Message msg = parseInput(c, reader);
                if (msg != null) msgQueue.offer(msg);
            }
        } catch (IOException e) {
            if (running) msgQueue.offer(new QuitMessage());
        }
    }

    private Message parseInput(int c, NonBlockingReader reader) throws IOException {
        if (c == 0x1B) {
            int next = reader.peek(80);
            if (next == '[') { reader.read(); return parseCSI(reader); }
            if (next == 'O') { reader.read(); return parseSS3(reader); }
            return key("escape");
        }
        if (c == 0x0D || c == 0x0A) return key("enter");
        if (c == 0x09) return key("tab");
        if (c == 0x03) return key("ctrl+c");
        if (c == 0x08) return key("ctrl+h");
        if (c == 0x0F) return key("ctrl+o");
        if (c >= 1 && c <= 26) return key("ctrl+" + (char) ('a' + c - 1));
        if (c == 0x7F) return key("backspace");
        if (c == ' ') return new KeyPressMessage(" ", new char[]{' '});
        if (c >= 32) {
            char[] chars = Character.toChars(c);
            return new KeyPressMessage(new String(chars), chars);
        }
        return null;
    }

    // SS3 格式方向键：\x1bOA/B/C/D（Windows Terminal 等常用此格式）
    private Message parseSS3(NonBlockingReader reader) throws IOException {
        int ch = reader.read(80);
        if (ch == -2 || ch == -1) return key("escape");
        return switch ((char) ch) {
            case 'A' -> key("up");
            case 'B' -> key("down");
            case 'C' -> key("right");
            case 'D' -> key("left");
            case 'H' -> key("home");
            case 'F' -> key("end");
            default -> null;
        };
    }

    private Message parseCSI(NonBlockingReader reader) throws IOException {
        var buf = new StringBuilder();
        while (true) {
            int ch = reader.read(80);
            if (ch == -2 || ch == -1) break;
            buf.append((char) ch);
            if (ch >= 0x40 && ch <= 0x7E) break;
        }
        String seq = buf.toString();
        if (seq.isEmpty()) return key("escape");
        char fin = seq.charAt(seq.length() - 1);
        String params = seq.substring(0, seq.length() - 1);
        return switch (fin) {
            case 'A' -> key("up");
            case 'B' -> key("down");
            case 'C' -> key("right");
            case 'D' -> key("left");
            case 'H' -> key("home");
            case 'F' -> key("end");
            case 'Z' -> key("shift+tab");
            case '~' -> switch (params) {
                case "5" -> key("pgup"); case "6" -> key("pgdown");
                case "1","7" -> key("home"); case "4","8" -> key("end");
                default -> null;
            };
            case 'M','m' -> parseSGRMouse(params);
            default -> null;
        };
    }

    private Message parseSGRMouse(String params) {
        if (!params.startsWith("<")) return null;
        String[] parts = params.substring(1).split(";");
        if (parts.length < 3) return null;
        try {
            int btn = Integer.parseInt(parts[0]);
            if (btn == 64) return new MouseMessage(MouseMessage.MouseButton.MouseButtonWheelUp);
            if (btn == 65) return new MouseMessage(MouseMessage.MouseButton.MouseButtonWheelDown);
            return new MouseMessage(MouseMessage.MouseButton.OTHER);
        } catch (NumberFormatException e) { return null; }
    }

    private static KeyPressMessage key(String name) {
        return new KeyPressMessage(name, null);
    }
}
