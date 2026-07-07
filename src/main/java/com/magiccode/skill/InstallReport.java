package com.magiccode.skill;

public record InstallReport(String skillName, String targetDir, int fileCount, long totalBytes) {}
