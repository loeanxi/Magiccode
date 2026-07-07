package com.magiccode.subagent;

import com.magiccode.tool.Tool;
import com.magiccode.tool.ToolRegistry;

import java.util.HashSet;
import java.util.Set;

public final class ToolFilter {

    private static final Set<String> ALWAYS_DISALLOWED = Set.of(
            "TaskOutput", "ExitPlanMode", "EnterPlanMode",
            "Agent", "AskUserQuestion", "TaskStop", "Workflow"
    );

    private static final Set<String> CUSTOM_AGENT_DISALLOWED = Set.of(
            "TaskOutput", "ExitPlanMode", "EnterPlanMode",
            "Agent", "AskUserQuestion", "TaskStop", "Workflow"
    );

    private static final Set<String> ASYNC_ALLOWED = Set.of(
            "ReadFile", "WebSearch", "TodoWrite", "Grep", "WebFetch", "Glob",
            "Bash", "EditFile", "WriteFile", "NotebookEdit", "Skill", "LoadSkill",
            "SyntheticOutput", "ToolSearch", "EnterWorktree", "ExitWorktree"
    );

    private static final Set<String> IN_PROCESS_TEAMMATE_ALLOWED = Set.of(
            "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "SendMessage",
            "CronCreate", "CronDelete", "CronList"
    );

    private ToolFilter() {}

    public static ToolRegistry filterForAgent(ToolRegistry source, SubAgentSpec spec) {
        return filterForAgent(source, spec, false, false, false);
    }

    public static ToolRegistry filterForAgent(ToolRegistry source, SubAgentSpec spec,
                                              boolean isAsync, boolean isCustom,
                                              boolean isInProcessTeammate) {
        Set<String> disallowed = new HashSet<>(spec.disallowedTools());
        boolean hasWhitelist = spec.tools() != null && !spec.tools().isEmpty()
                && !(spec.tools().size() == 1 && "*".equals(spec.tools().get(0)));
        Set<String> allowed = hasWhitelist ? new HashSet<>(spec.tools()) : Set.of();

        ToolRegistry filtered = new ToolRegistry();
        for (Tool tool : source.listTools()) {
            String name = tool.name();
            if (isMcpTool(name)) { filtered.register(tool); continue; }
            if (ALWAYS_DISALLOWED.contains(name)) continue;
            if (isCustom && CUSTOM_AGENT_DISALLOWED.contains(name)) continue;
            if (isAsync) {
                boolean async_allowed = ASYNC_ALLOWED.contains(name);
                if (!async_allowed) {
                    if (isInProcessTeammate && ("Agent".equals(name) || IN_PROCESS_TEAMMATE_ALLOWED.contains(name))) {
                        // permitted
                    } else {
                        continue;
                    }
                }
            }
            if (disallowed.contains(name)) continue;
            if (hasWhitelist && !allowed.contains(name)) continue;
            filtered.register(tool);
        }
        return filtered;
    }

    public static ToolRegistry cloneForFork(ToolRegistry source) {
        ToolRegistry forked = new ToolRegistry();
        for (Tool tool : source.listTools()) {
            if (tool instanceof AgentTool at) {
                forked.register(at.cloneWithQuerySource("agent:builtin:fork"));
            } else {
                forked.register(tool);
            }
        }
        return forked;
    }

    private static boolean isMcpTool(String name) {
        return name.startsWith("mcp__");
    }
}
