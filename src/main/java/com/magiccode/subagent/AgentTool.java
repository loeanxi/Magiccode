package com.magiccode.subagent;

import com.magiccode.agent.Agent;
import com.magiccode.agent.AgentEvent;
import com.magiccode.config.ProviderConfig;
import com.magiccode.conversation.ConversationManager;
import com.magiccode.llm.LlmClient;
import com.magiccode.tool.Tool;
import com.magiccode.tool.ToolCategory;
import com.magiccode.tool.ToolRegistry;
import com.magiccode.tool.ToolResult;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

public class AgentTool implements Tool {

    private final LlmClient client;
    private final ToolRegistry parentRegistry;
    private final String protocol;
    private final ProviderConfig providerConfig;

    private Function<String, LlmClient> modelResolver;
    private Map<String, SubAgentSpec> agentSpecs;
    private Consumer<SubAgentProgress> progressListener;
    private SubAgentTaskManager taskManager;
    private ConversationManager parentConversation;

    /** Optional: worktree manager for isolation mode. */
    private com.magiccode.worktree.WorktreeManager worktreeManager;

    /** Optional: team manager for team_name registration. */
    private com.magiccode.teams.TeamManager teamManager;

    private com.magiccode.toolresult.ContentReplacementState parentReplacementState;
    private String querySource = "";

    private static final String FORK_BOILERPLATE_TAG = "<fork_boilerplate>";
    private static final String FORK_BOILERPLATE = FORK_BOILERPLATE_TAG + """

            You are a forked worker process. You are NOT the main agent.
            Rules (non-negotiable):
            1. Do NOT fork again.
            2. Do NOT converse, ask questions, or request confirmation.
            3. Use tools directly: read files, search code, make changes.
            4. Stay strictly within your assigned task scope.
            5. Final report must be under 500 characters, starting with "Scope:".
            </fork_boilerplate>""";

    private static final String FORK_QUERY_SOURCE = "agent:builtin:fork";

    public AgentTool(LlmClient client, ToolRegistry parentRegistry, String protocol, ProviderConfig providerConfig) {
        this.client = client;
        this.parentRegistry = parentRegistry;
        this.protocol = protocol;
        this.providerConfig = providerConfig;
    }

    public void setModelResolver(Function<String, LlmClient> modelResolver) { this.modelResolver = modelResolver; }
    public void setAgentSpecs(Map<String, SubAgentSpec> agentSpecs) { this.agentSpecs = agentSpecs; }
    public void setProgressListener(Consumer<SubAgentProgress> progressListener) { this.progressListener = progressListener; }
    public void setTaskManager(SubAgentTaskManager taskManager) { this.taskManager = taskManager; }
    public SubAgentTaskManager getTaskManager() { return taskManager; }
    public void setParentConversation(ConversationManager parentConversation) { this.parentConversation = parentConversation; }
    public void setParentReplacementState(com.magiccode.toolresult.ContentReplacementState state) { this.parentReplacementState = state; }
    public void setWorktreeManager(com.magiccode.worktree.WorktreeManager worktreeManager) { this.worktreeManager = worktreeManager; }
    public void setTeamManager(com.magiccode.teams.TeamManager teamManager) { this.teamManager = teamManager; }
    public String getQuerySource() { return querySource; }
    public void setQuerySource(String querySource) { this.querySource = querySource; }

    public AgentTool cloneWithQuerySource(String qs) {
        AgentTool clone = new AgentTool(this.client, this.parentRegistry, this.protocol, this.providerConfig);
        clone.modelResolver = this.modelResolver;
        clone.agentSpecs = this.agentSpecs;
        clone.progressListener = this.progressListener;
        clone.taskManager = this.taskManager;
        clone.parentConversation = this.parentConversation;
        clone.worktreeManager = this.worktreeManager;
        clone.teamManager = this.teamManager;
        clone.parentReplacementState = this.parentReplacementState;
        clone.querySource = qs;
        return clone;
    }

    @Override public String name() { return "Agent"; }

    @Override
    public String description() {
        var sb = new StringBuilder();
        sb.append("Launch a sub-agent to handle a complex task. Each agent runs independently with its own context.\n\n");
        sb.append("Use this when a task benefits from focused, isolated work -- e.g., ");
        sb.append("researching a question, implementing a component, or reviewing code. ");
        sb.append("The sub-agent cannot see the current conversation.\n\n");
        sb.append("Available agent types:");
        if (agentSpecs != null && !agentSpecs.isEmpty()) {
            for (String name : AgentLoader.listNames(agentSpecs)) {
                SubAgentSpec spec = agentSpecs.get(name);
                sb.append("\n- ").append(name).append(": ").append(spec.description());
            }
        } else {
            sb.append("\n- general-purpose: Full tool access for multi-step tasks (default)");
            sb.append("\n- plan: Read-only tools for designing implementation plans");
            sb.append("\n- explore: Read-only search agent for locating code");
        }
        sb.append("\n\nWrite a detailed prompt explaining what the agent should do and why -- it has no prior context.");
        return sb.toString();
    }

    @Override public ToolCategory category() { return ToolCategory.COMMAND; }

    @Override
    public Map<String, Object> schema() {
        List<String> agentTypes;
        if (agentSpecs != null && !agentSpecs.isEmpty()) {
            agentTypes = AgentLoader.listNames(agentSpecs);
        } else {
            agentTypes = List.of("general-purpose", "plan", "explore");
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("description", Map.of("type", "string", "description", "A short (3-5 word) description of the task"));
        properties.put("prompt", Map.of("type", "string", "description", "The task for the agent to perform. Be detailed -- the agent has no context from this conversation."));
        properties.put("subagent_type", Map.of("type", "string", "enum", agentTypes, "description", "The type of agent to use. Defaults to general-purpose."));
        properties.put("model", Map.of("type", "string", "enum", List.of("sonnet", "opus", "haiku"), "description", "Override the model for this agent."));
        properties.put("run_in_background", Map.of("type", "boolean", "description", "Set to true to run the agent in the background."));
        properties.put("isolation", Map.of("type", "string", "enum", List.of("worktree"), "description", "Isolation mode. 'worktree' creates a temporary git worktree."));
        properties.put("team_name", Map.of("type", "string", "description", "REQUIRED when creating team members. Spawns the agent as a long-running teammate under this team."));
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", List.of("description", "prompt"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", name());
        schema.put("description", description());
        schema.put("input_schema", inputSchema);
        return schema;
    }

    @Override public boolean shouldDefer() { return false; }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String description = getStringArg(args, "description");
        String prompt = getStringArg(args, "prompt");
        if (description == null || description.isEmpty() || prompt == null || prompt.isEmpty()) {
            return ToolResult.error("Error: description and prompt are required");
        }
        String subagentType = getStringArg(args, "subagent_type");
        String modelOverride = getStringArg(args, "model");
        String isolation = getStringArg(args, "isolation");
        String teamName = getStringArg(args, "team_name");

        // Team-member path: check BEFORE fork/subagent so team_name is never skipped.
        if (teamName != null && !teamName.isEmpty() && teamManager != null) {
            SubAgentSpec spec = (subagentType != null && !subagentType.isEmpty())
                    ? resolveSpec(subagentType) : resolveSpec("general-purpose");
            if (spec == null) spec = resolveSpec("general-purpose");
            return runAsTeammate(spec, teamName, description, prompt, modelOverride, isolation);
        }

        // Fork path: no subagent_type specified.
        if (subagentType == null || subagentType.isEmpty()) {
            return runFork(description, prompt, modelOverride);
        }

        SubAgentSpec spec = resolveSpec(subagentType);
        if (spec == null) {
            String available = (agentSpecs != null) ? String.join(", ", AgentLoader.listNames(agentSpecs)) : "general-purpose, plan, explore";
            return ToolResult.error("Error: unknown agent type '%s'. Available: %s".formatted(subagentType, available));
        }

        boolean runInBackground = Boolean.TRUE.equals(args.get("run_in_background"));
        if (runInBackground) {
            return runAsync(spec, description, prompt, modelOverride);
        }
        return runSync(spec, description, prompt, modelOverride, isolation);
    }

    private ToolResult runAsync(SubAgentSpec spec, String description, String prompt, String modelOverride) {
        if (taskManager == null) return ToolResult.error("Background execution not available (no task manager configured)");
        LlmClient subClient = selectClient(spec.model(), modelOverride);
        String taskId = taskManager.spawnSubAgent(subClient, parentRegistry, protocol, providerConfig, spec, prompt);
        return ToolResult.success("Agent \"%s\" launched in background (task %s). You will be notified when it completes.".formatted(description, taskId));
    }

    private ToolResult runFork(String description, String prompt, String modelOverride) {
        if (parentConversation == null) return ToolResult.error("Error: fork requires parent conversation context");
        if (taskManager == null) return ToolResult.error("Error: fork requires task manager for background execution");
        if (FORK_QUERY_SOURCE.equals(querySource)) return ToolResult.error("Error: cannot fork from a forked agent. Use subagent_type to spawn a definition-based agent instead.");
        for (var msg : parentConversation.getMessages()) {
            if (msg.getContent() != null && msg.getContent().contains(FORK_BOILERPLATE_TAG)) {
                return ToolResult.error("Error: cannot fork from a forked agent. Use subagent_type to spawn a definition-based agent instead.");
            }
        }
        ConversationManager forkedConv = buildForkedConversation(parentConversation, prompt);
        LlmClient subClient = selectClient(null, modelOverride);
        ToolRegistry forkedRegistry = ToolFilter.cloneForFork(parentRegistry);
        String taskId = taskManager.spawnForkAgent(subClient, forkedRegistry, protocol, providerConfig,
                FORK_BOILERPLATE + "\n\nYour task:\n" + prompt, parentReplacementState);
        return ToolResult.success("Forked agent \"%s\" launched in background (task %s). Results will arrive via task-notification.".formatted(description, taskId));
    }

    private static ConversationManager buildForkedConversation(ConversationManager parent, String task) {
        ConversationManager forked = new ConversationManager();
        for (var msg : parent.getMessages()) {
            if (msg.getToolUses() != null && !msg.getToolUses().isEmpty()
                    && (msg.getToolResults() == null || msg.getToolResults().isEmpty())) {
                forked.addAssistantFull(msg.getContent(), msg.getThinkingBlocks(), msg.getToolUses());
                var placeholders = msg.getToolUses().stream()
                        .map(tu -> new com.magiccode.conversation.ToolResultBlock(tu.toolUseId(), "(tool execution interrupted by fork)", false))
                        .toList();
                forked.addToolResultsMessage(placeholders);
            } else if (msg.getToolUses() != null && !msg.getToolUses().isEmpty()) {
                forked.addAssistantFull(msg.getContent(), msg.getThinkingBlocks(), msg.getToolUses());
            } else if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) {
                forked.addToolResultsMessage(msg.getToolResults());
            } else if ("assistant".equals(msg.getRole())) {
                forked.addAssistantMessage(msg.getContent());
            } else {
                forked.addUserMessage(msg.getContent());
            }
        }
        forked.addUserMessage(FORK_BOILERPLATE + "\n\nYour task:\n" + task);
        return forked;
    }

    private ToolResult runSync(SubAgentSpec spec, String description, String prompt, String modelOverride, String isolation) {
        ToolRegistry subRegistry = ToolFilter.filterForAgent(parentRegistry, spec);
        LlmClient subClient = selectClient(spec.model(), modelOverride);
        Agent subAgent = new Agent(subClient, subRegistry, protocol, providerConfig);
        int maxTurns = spec.maxTurns() > 0 ? spec.maxTurns() : 200;
        subAgent.setMaxIterations(maxTurns);

        // Worktree isolation via AgentWorktree API
        com.magiccode.worktree.AgentWorktree.Result wtResult = null;
        if ("worktree".equals(isolation) && worktreeManager != null) {
            byte[] rndBytes = new byte[4];
            new java.security.SecureRandom().nextBytes(rndBytes);
            String slug = "agent-a" + java.util.HexFormat.of().formatHex(rndBytes).substring(0, 7);
            try {
                wtResult = com.magiccode.worktree.AgentWorktree.create(
                        slug, worktreeManager.getProjectRoot(), worktreeManager.getSymlinkDirs());
                subAgent.setWorkDir(wtResult.worktreePath());
                String notice = com.magiccode.worktree.AgentWorktree.buildNotice(
                        System.getProperty("user.dir"), wtResult.worktreePath());
                prompt = notice + "\n\n" + prompt;
            } catch (Exception e) {
                return ToolResult.error("Error creating agent worktree: " + e.getMessage());
            }
        }

        ConversationManager conv = new ConversationManager();
        if (spec.systemPromptOverride() != null && !spec.systemPromptOverride().isEmpty()) conv.addSystemReminder(spec.systemPromptOverride());
        conv.addUserMessage(prompt);
        long startNanos = System.nanoTime();
        var output = new StringBuilder();
        int toolCount = 0;
        BlockingQueue<AgentEvent> queue = subAgent.run(conv);
        while (true) {
            AgentEvent event;
            try { event = queue.poll(60, TimeUnit.SECONDS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitProgress(description, spec.name(), true, true, toolCount, elapsedSeconds(startNanos));
                return ToolResult.error("Agent interrupted");
            }
            if (event == null) {
                emitProgress(description, spec.name(), true, true, toolCount, elapsedSeconds(startNanos));
                return ToolResult.error("Agent timed out waiting for events");
            }
            switch (event) {
                case AgentEvent.StreamText st -> output.append(st.text());
                case AgentEvent.ToolResultEvent tre -> {
                    toolCount++;
                    emitProgress(description, spec.name(), tre.toolName(), tre.output(), tre.isError(), false, toolCount, elapsedSeconds(startNanos));
                }
                case AgentEvent.ErrorEvent err -> {
                    emitProgress(description, spec.name(), true, true, toolCount, elapsedSeconds(startNanos));
                    return ToolResult.error("Agent failed: " + err.message());
                }
                case AgentEvent.LoopComplete lc -> {
                    double totalTime = elapsedSeconds(startNanos);
                    emitProgress(description, spec.name(), false, true, toolCount, totalTime);
                    String result = output.toString();
                    if (result.isEmpty()) result = "(agent produced no output)";

                    // Worktree cleanup via WorktreeChanges API (fail-closed)
                    String wtInfo = "";
                    if (wtResult != null) {
                        if (com.magiccode.worktree.WorktreeChanges.hasChanges(
                                wtResult.worktreePath(), wtResult.headCommit())) {
                            wtInfo = "\n\nWorktree kept at %s (branch %s) — has uncommitted changes or new commits."
                                    .formatted(wtResult.worktreePath(), wtResult.worktreeBranch());
                        } else {
                            com.magiccode.worktree.AgentWorktree.remove(
                                    wtResult.worktreePath(), wtResult.worktreeBranch(), wtResult.gitRoot());
                        }
                    }

                    long elapsedMs = Math.round(totalTime * 1000);
                    return ToolResult.success(
                            "Agent \"%s\" completed in %d.%03ds.\n\n%s%s".formatted(
                                    description, elapsedMs / 1000, elapsedMs % 1000, result, wtInfo));
                }
                default -> {}
            }
        }
    }

    private SubAgentSpec resolveSpec(String name) {
        if (agentSpecs != null) return agentSpecs.get(name);
        return switch (name) {
            case "general-purpose" -> SubAgentSpec.GENERAL_PURPOSE;
            case "plan" -> SubAgentSpec.PLAN;
            case "explore" -> SubAgentSpec.EXPLORE;
            default -> null;
        };
    }

    private ToolResult runAsTeammate(SubAgentSpec spec, String teamName,
                                     String description, String prompt, String modelOverride, String isolation) {
        var team = teamManager.getTeam(teamName);
        if (team == null) {
            return ToolResult.error("Error: team '%s' not found. Create it first with TeamCreate.".formatted(teamName));
        }

        // Deduplicate member name
        String memberName = description.replaceAll("\\s+", "-").toLowerCase();
        if (memberName.length() > 30) memberName = memberName.substring(0, 30);
        int suffix = 2;
        String base = memberName;
        while (team.hasMember(memberName)) {
            memberName = base + "-" + suffix++;
        }

        ToolRegistry subRegistry = ToolFilter.filterForAgent(parentRegistry, spec);
        // Add coordination tools for teammates
        subRegistry.register(new com.magiccode.teams.TeamTools.SendMessageTool(teamManager, memberName));

        LlmClient subClient = selectClient(spec.model(), modelOverride);

        // Gather peer names for addendum
        var otherMembers = team.memberNames();

        // Build addendum
        String addendum = com.magiccode.teams.TeammateRunner.buildTeammateAddendum(
                teamName, memberName, otherMembers);

        // Optional worktree isolation
        String workdir = null;
        if ("worktree".equals(isolation) && worktreeManager != null) {
            try {
                byte[] rndBytes = new byte[4];
                new java.security.SecureRandom().nextBytes(rndBytes);
                String slug = "agent-a" + java.util.HexFormat.of().formatHex(rndBytes).substring(0, 7);
                var wtResult = com.magiccode.worktree.AgentWorktree.create(
                        slug, worktreeManager.getProjectRoot(), worktreeManager.getSymlinkDirs());
                workdir = wtResult.worktreePath();
                String notice = com.magiccode.worktree.AgentWorktree.buildNotice(
                        System.getProperty("user.dir"), wtResult.worktreePath());
                prompt = notice + "\n\n" + prompt;
            } catch (Exception e) {
                return ToolResult.error("Error creating teammate worktree: " + e.getMessage());
            }
        }

        // Spawn teammate
        try {
            var spawnResult = com.magiccode.teams.SpawnDispatcher.spawnTeammate(
                    new com.magiccode.teams.SpawnDispatcher.SpawnConfig(
                            team, memberName, prompt, addendum,
                            subClient, subRegistry, protocol, providerConfig, workdir));

            return ToolResult.success(
                    "Teammate \"%s\" spawned in team \"%s\" (mode: %s). The teammate is now working on the assigned task."
                            .formatted(memberName, teamName, spawnResult.mode()));
        } catch (Exception e) {
            return ToolResult.error("Error spawning teammate: " + e.getMessage());
        }
    }

    private LlmClient selectClient(String specModel, String overrideModel) {
        String model = (overrideModel != null && !overrideModel.isEmpty()) ? overrideModel : specModel;
        if (model == null || model.isEmpty() || "inherit".equals(model)) return client;
        if (modelResolver != null) {
            LlmClient resolved = modelResolver.apply(model);
            if (resolved != null) return resolved;
        }
        return client;
    }

    private void emitProgress(String description, String agentType, boolean isError, boolean done, int toolCount, double totalTime) {
        emitProgress(description, agentType, null, null, isError, done, toolCount, totalTime);
    }

    private void emitProgress(String description, String agentType, String toolName, String toolOutput, boolean isError, boolean done, int toolCount, double totalTime) {
        if (progressListener != null) progressListener.accept(new SubAgentProgress(agentType, description, toolName, toolOutput, isError, done, toolCount, totalTime));
    }

    private static double elapsedSeconds(long startNanos) { return (System.nanoTime() - startNanos) / 1_000_000_000.0; }
    private static String getStringArg(Map<String, Object> args, String key) { return args.get(key) instanceof String s ? s : null; }
}
