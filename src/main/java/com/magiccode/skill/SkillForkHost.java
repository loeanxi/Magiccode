package com.magiccode.skill;

import com.magiccode.conversation.Message;

import java.util.List;

public interface SkillForkHost extends SkillHost {
    String runSubAgent(String body, List<Message> seed, String model);
    List<Message> snapshotParentMessages();
}
