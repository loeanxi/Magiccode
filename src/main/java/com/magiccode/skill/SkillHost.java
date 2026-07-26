package com.magiccode.skill;

import com.magiccode.tool.ToolRegistry;

import java.util.function.Predicate;

public interface SkillHost {
    void activateSkill(String name, String body);
    void setToolFilter(Predicate<String> filter);
    ToolRegistry toolRegistry();
    default void recordSkillInvocation(String name, String body) {}
}
