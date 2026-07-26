package com.magiccode.subagent;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class AgentLoader {

    private final Map<String, SubAgentSpec> agents = new LinkedHashMap<>();

    private AgentLoader() {}

    public static Map<String, SubAgentSpec> loadAll(Path projectRoot) {
        var loader = new AgentLoader();
        loader.loadBuiltins();
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            loader.loadDir(Path.of(home, ".magiccode", "agents"));
        }
        if (projectRoot != null) {
            loader.loadDir(projectRoot.resolve(".magiccode").resolve("agents"));
        }
        return Collections.unmodifiableMap(loader.agents);
    }

    public static List<String> listNames(Map<String, SubAgentSpec> agents) {
        var names = new ArrayList<>(agents.keySet());
        Collections.sort(names);
        return names;
    }

    private void loadBuiltins() {
        agents.put(SubAgentSpec.GENERAL_PURPOSE.name(), SubAgentSpec.GENERAL_PURPOSE);
        agents.put(SubAgentSpec.PLAN.name(), SubAgentSpec.PLAN);
        agents.put(SubAgentSpec.EXPLORE.name(), SubAgentSpec.EXPLORE);
    }

    private void loadDir(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) continue;
                try {
                    SubAgentSpec spec = parseAgentFile(path);
                    agents.put(spec.name(), spec);
                } catch (Exception e) {}
            }
        } catch (IOException e) {}
    }

    static SubAgentSpec parseAgentFile(Path path) throws IOException {
        String content = Files.readString(path);
        String trimmed = content.strip();
        String yamlBlock = null;
        String body = trimmed;
        if (trimmed.startsWith("---")) {
            int firstEnd = trimmed.indexOf("---", 3);
            if (firstEnd >= 0) {
                yamlBlock = trimmed.substring(3, firstEnd).strip();
                body = trimmed.substring(firstEnd + 3).strip();
            }
        }
        String name = null;
        String description = null;
        List<String> tools = List.of();
        List<String> disallowedTools = List.of();
        String model = null;
        int maxTurns = 0;
        if (yamlBlock != null && !yamlBlock.isEmpty()) {
            Yaml yaml = new Yaml();
            Map<String, Object> frontmatter = yaml.load(yamlBlock);
            if (frontmatter != null) {
                name = getString(frontmatter, "name");
                description = getString(frontmatter, "description");
                tools = getStringList(frontmatter, "tools");
                disallowedTools = getStringList(frontmatter, "disallowedTools");
                model = getString(frontmatter, "model");
                Object maxTurnsObj = frontmatter.get("maxTurns");
                if (maxTurnsObj instanceof Number n) maxTurns = n.intValue();
            }
        }
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Agent definition %s: missing required field 'name'".formatted(path));
        if (description == null || description.isEmpty()) throw new IllegalArgumentException("Agent definition %s: missing required field 'description'".formatted(path));
        if (model != null) {
            model = model.strip();
            if (model.equalsIgnoreCase("inherit")) model = "inherit";
        }
        String systemPrompt = body.isEmpty() ? null : body;
        return new SubAgentSpec(name, description, tools, disallowedTools, systemPrompt, maxTurns, model);
    }

    private static String getString(Map<String, Object> map, String key) {
        return map.get(key) instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            var result = new ArrayList<String>();
            for (Object item : list) {
                if (item instanceof String s) result.add(s);
            }
            return List.copyOf(result);
        }
        return List.of();
    }
}
